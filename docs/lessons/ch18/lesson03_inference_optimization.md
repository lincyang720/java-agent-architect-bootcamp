# 第十八章第3节：推理优化：量化、KV Cache、vLLM 与成本治理

大家好，我是秋天。

这是整个训练营的最后一节，我们聊一个离"钱"最近的话题：**推理优化**。前面你训好了 LoRA（lesson02），模型也接进 Agent 了。但一上线发现：一个 7B 模型占满整张 A100，并发一来就排队，单次推理 ¥0.02，一天几万次调用账单吓死人。这时候，"推理优化"就是直接省钱、提吞吐、降延迟的硬功夫。

这节课讲什么、学完掌握什么：

- 讲什么：① 量化（INT8/FP8/GPTQ/AWQ）怎么砍显存降成本；② KV Cache 是什么、为什么它常是显存和速度的命门；③ vLLM 的 PagedAttention 与连续批处理；④ 成本治理（模型路由/缓存/量化选型）；⑤ **Java 工程师怎么调用一个 vLLM 部署的模型**（OpenAI 兼容接口）。
- 学完掌握：你理解"推理成本 = 显存占用 × 吞吐 × 单价"这层账；你能说出 KV Cache 为什么关键；你能用 Java 17 的 HttpClient 调一个 vLLM 服务；你知道怎么把推理账单压下来。

<callout emoji="💡"><p>招聘映射：JD 加分项明确列"推理优化（量化 / vLLM / KV Cache）"。在"降本增效"成为 2026 主旋律的当下，能"把模型跑得又便宜又快"的工程师极稀缺。这一节是第十八章的收口，也是你从"会用模型"跨到"会经营模型"的最后一跃。</p></callout>

---

## 一、先算账：推理成本到底花在哪

很多同学以为"调大模型就按 token 付费"，但自建部署时成本构成更复杂：

```text
推理成本账本（自建 GPU 部署）：
- 显存成本：模型权重 + KV Cache + 激活，占满几张卡 → 卡贵
- 吞吐成本：同样一张卡，每秒能处理多少请求 → 吞吐低=要更多卡
- 延迟成本：用户等多久 → 慢了要加实例
- 单价：云 GPU 按时计费（A100 ≈ ¥10-20/小时，H100 更贵）

核心公式（直觉版）：
  单次成本 ≈ 模型显存占用 / 吞吐 × GPU 单价 + 调度开销
  → 降显存(量化) + 提吞吐(vLLM批处理) + 降冗余(缓存/路由) = 省钱
```

<callout emoji="⚠️"><p>最容易忽视的隐性成本：KV Cache 显存。一个 7B 模型权重可能只占 14GB(FP16)，但高并发长上下文下 KV Cache 能再吃掉几十 GB。不优化 KV Cache，你买的显存大半喂给了"上下文缓存"而非"模型本身"。</p></callout>

---

## 二、量化：用精度换显存和速度

量化 = 把模型权重/激活从高精度（FP16/BF16）降到低精度（INT8/FP8/INT4），显存和算力需求随之下降。

```text
精度档位与影响（7B 模型权重占用，近似）：
FP16/BF16   ≈ 14 GB     基准，精度最好，最占显存
INT8        ≈ 7 GB      显存减半，精度损失小，几乎零成本首选
FP8         ≈ 7 GB      H100 原生，精度优于 INT8，需新硬件
INT4(GPTQ/AWQ) ≈ 3.5 GB 显存再砍半，精度轻微损，推理最快
```

各方法定位（呼应 lesson02）：

```text
- INT8：通用、安全、几乎无损 → 默认首选部署量化
- FP8 ：有 H100 等硬件时优先，精度/速度双优
- GPTQ：4-bit 经典方案，需校准集，质量稳
- AWQ ：激活感知，4-bit 常更优且对 vLLM 友好 → 本课推荐
```

```python
# 用 AutoAWQ 做 4-bit 量化（部署侧，非训练侧），示例
from awq import AutoAWQForCausalLM
from transformers import AutoTokenizer

model_id = "Qwen/Qwen2.5-7B-Instruct"
model = AutoAWQForCausalLM.from_pretrained(model_id)
tokenizer = AutoTokenizer.from_pretrained(model_id)

# 量化并保存（只需跑一次）
model.quantize(tokenizer, quant_config={"w_bit":4, "q_group_size":128})
model.save_quantized("./qwen25-7b-awq-4bit")
tokenizer.save_pretrained("./qwen25-7b-awq-4bit")
# 产出 4-bit 权重 ≈ 3.5GB，可直接被 vLLM/llama.cpp 加载
```

<callout emoji="✅"><p>量化选型口诀：没特殊硬件 → INT8 起步（稳）；有 H100 → FP8；要极致省显存/提吞吐 → AWQ 4-bit。落地前用评测集（第十六章）确认量化后质量不掉到不可接受——通常 4-bit 在多数任务上只掉 1-2 个点，完全可接受。</p></callout>

---

## 三、KV Cache：被忽视的显存吞噬者

理解 KV Cache 是理解推理优化的关键。自回归生成时，每生成一个 token 都要用到前面所有 token 的 Key/Value 矩阵。如果每次都重算，复杂度爆炸。所以推理框架会**缓存**这些 K/V：

```
生成第 N 个 token 时：
  朴素做法：重算 1..N-1 所有 K/V → O(N²)，极慢
  KV Cache：缓存 1..N-1 的 K/V，只算第 N 个 → O(N)，快

代价：KV Cache 随上下文长度、并发数线性增长显存
  显存(KV) ≈ 2 × 层数 × 隐藏维度 × 序列长度 × 并发 × 每元素字节数
  → 长上下文 + 高并发 → KV Cache 能比权重还大
```

优化 KV Cache 的两条主路：

```text
路1：省空间 —— 量化 KV（KV Cache INT8/FP8）、分组查询注意力(GQA，模型结构层面已支持)
路2：省浪费 —— PagedAttention（vLLM）：像操作系统分页一样管理 KV，
             不再为每个请求预留连续大块，显存利用率从 ~20% 提到 ~90%
```

<callout emoji="💡"><p>一句话：KV Cache 是"用空间换时间"，但空间管理不好就爆显存。vLLM 的 PagedAttention 解决了"KV 块碎片"问题——这是 vLLM 吞吐远超原生 HuggingFace 推理的核心原因之一。</p></callout>

---

## 四、vLLM：把吞吐拉满的推理引擎

vLLM 是当前最主流的高性能推理框架，三个杀手锏：

```text
1. PagedAttention：KV Cache 分页管理，显存利用率 ~90%（原生推理仅 ~20%）
2. 连续批处理(Continuous Batching)：请求来一个进一个，不等满批，
   彻底解决"短请求等长请求"的木桶效应 → 吞吐数倍提升
3. 原生多 LoRA 服务：一个底座动态挂多个 LoRA（呼应 lesson02 多 LoRA 架构）
```

启动一个 vLLM 服务（AWQ 4-bit + 多 LoRA 示例）：

```bash
# 启动 vLLM OpenAI 兼容服务（端口 8000）
python -m vllm.entrypoints.openai.api_server \
  --model ./qwen25-7b-awq-4bit \
  --tensor-parallel-size 1 \
  --gpu-memory-utilization 0.9 \
  --max-model-len 8192 \
  --enable-lora \
  --lora-modules lora_t=./adapters/tickets \
                 lora_c=./adapters/customersvc \
  --port 8000
# 启动后即可用 OpenAI 兼容接口调用（Java 侧见第五节）
```

vLLM 暴露的是 **OpenAI 兼容 API**（`/v1/chat/completions`），这意味着你在 Java 里用的 OpenAI SDK / LangChain4j / Spring AI 几乎不用改，换个 `base-url` 即可。

```text
vLLM 吞吐对比（经验值，7B 模型，A100）：
原生 HuggingFace 推理(TGI 之前) ≈ 基准 1x
vLLM(连续批处理+PagedAttention) ≈ 2-4x 吞吐，P99 延迟更低
```

<callout emoji="⚠️"><p>vLLM 不是银弹：它吃显存优化和批处理，但单请求首 token 延迟（TTFT）未必比小模型快；且对很长的单请求，KV Cache 仍是瓶颈。生产要根据流量形态（高并发短请求 vs 低并发长请求）调 `max-num-seqs`、`max-model-len` 等参数。</p></callout>

---

## 五、Java 工程师怎么调用 vLLM（OpenAI 兼容）

重点来了——你的 Agent 是 Java 写的，怎么调这个 vLLM 服务？因为 vLLM 暴露 OpenAI 兼容接口，所以**改个 base-url 就行**。

### 5.1 用 Spring AI（最省事，base-url 指向 vLLM）

```yaml
# application.yml：把 OpenAI 的 base-url 指向你的 vLLM
spring:
  ai:
    openai:
      base-url: http://${VLLM_HOST:localhost}:8000/v1   # vLLM 地址
      api-key: ${VLLM_API_KEY:EMPTY}                    # vLLM 可不设 key
      chat:
        options:
          model: qwen25-7b-awq-4bit    # vLLM 加载的模型名
```

```java
package com.example.ch18.infer;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/agent")
public class VllmAgentController {

    private final OpenAiChatModel chatModel;

    public VllmAgentController(OpenAiChatModel chatModel) {
        this.chatModel = chatModel;   // base-url 已指向 vLLM，调用透明
    }

    @PostMapping("/chat")
    public String chat(@RequestBody String message) {
        return chatModel.call(message);
    }

    // 指定某个 LoRA 时，可加自定义 header（vLLM 用 X-Lora 之类扩展）
}
```

### 5.2 用原生 HttpClient 直调（更可控，含 LoRA 指定）

```java
package com.example.ch18.infer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** 用 Java 17 HttpClient 直调 vLLM 的 OpenAI 兼容接口 */
public class VllmClient {

    private final HttpClient http = HttpClient.newHttpClient();
    private final String baseUrl;

    public VllmClient(String baseUrl) { this.baseUrl = baseUrl; }

    public String chat(String model, String userMsg, String loraName) throws Exception {
        // OpenAI 兼容请求体
        String body = """
        {
          "model": "%s",
          "messages": [{"role":"user","content":"%s"}],
          "max_tokens": 512,
          "temperature": 0.3
        }
        """.formatted(model, userMsg.replace("\"", "\\\""));

        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(60));
        // 指定 LoRA（vLLM 扩展 header，具体名以版本为准）
        if (loraName != null) req.header("X-Lora-Name", loraName);

        HttpResponse<String> resp = http.send(req.build(),
                HttpResponse.BodyHandlers.ofString());
        return resp.body();   // 解析 JSON 取 choices[0].message.content
    }

    public static void main(String[] args) throws Exception {
        VllmClient c = new VllmClient("http://localhost:8000");
        System.out.println(c.chat("qwen25-7b-awq-4bit", "订单A123物流延迟怎么办", "lora_t"));
    }
}
```

<callout emoji="✅"><p>关键认知：推理优化在"服务端"（vLLM/量化），Java 侧几乎零改动——只要 OpenAI 兼容接口。这也意味着你前期用 OpenAI/DashScope 写的 Agent 代码，切换到自建 vLLM 只需改 base-url。架构上把"模型供应商"抽象成可替换的 endpoint，是生产必备的灵活性。</p></callout>

---

## 六、成本治理：把账单压下来

推理优化是技术，成本治理是工程纪律。结合 lesson04 平台工程，落地四招：

```text
成本治理四招（按 ROI 排序）：
1. 缓存命中：相同/相似 prompt 复用结果（Prompt 缓存，第十五章）
   → 重复问题零推理成本，常见省 30%-50%
2. 模型路由：简单任务走小/便宜模型，复杂才上大/贵模型
   → 多数客服问答用 7B 足够，省 5-10x
3. 量化：INT8/AWQ 4-bit，显存和单价双降
   → 4-bit 比 FP16 显存省 4x，吞吐更高
4. 批处理/扩缩：vLLM 连续批处理 + 按负载 HPA，避免空转浪费
```

```java
// 概念：模型路由（简单任务走小模型），对应 lesson04 成本治理
public String routeAndCall(String userMsg) {
    boolean simple = intentClassifier.isSimple(userMsg);  // 意图分类
    String model = simple ? "qwen2.5-3b-awq" : "qwen2.5-7b-awq";
    return vllmClient.chat(model, userMsg, null);
}
```

<callout emoji="💡"><p>成本治理最大的杠杆往往是"缓存"和"路由"，不是"换更便宜的硬件"。我见过一个团队把 70B 模型换成 7B + 路由 + 缓存后，成本降 90%、延迟还更低——因为大部分请求本来就不该问大模型。先治理流量结构，再谈硬件优化。</p></callout>

---

## 七、量化与质量的权衡：用评测兜住

任何优化都可能有质量代价，必须量化：

```text
量化前后评测对比（示例，RAGAS/业务评测集）：
精度       准确率   成本/次   显存   结论
FP16       0.91     ¥0.020   14GB   基准
INT8       0.90     ¥0.011   7GB    ✅ 几乎无损，首选
AWQ-4bit   0.88     ¥0.006   3.5GB  ✅ 精度微降，成本省 3x，可接受
```

掉到 0.88 是否可接受？看业务：客服闲聊可接受，医疗诊断不可接受——所以**量化粒度要按业务敏感度分级**，核心业务留 FP16/INT8，边缘业务放 4-bit。

<callout emoji="⚠️"><p>反模式："为了省钱全量 4-bit"，结果核心业务质量暴跌被投诉。优化必须配评测闭环（第十六章）——每次量化/换模型都跑评测集，质量红线以下的不许上线。省钱不能省到质量崩。</p></callout>

---

## 八、企业落地：一个月省下六位数

某电商客服 Agent，原架构：直接调云厂商 70B API，按 token 计费，月账单 ¥30 万+，且高峰期限速。优化后：

```text
优化动作：
1. 意图路由：70% 简单问答路由到自建 7B-AWQ(vLLM)，仅 30% 复杂走云端大模型
2. 量化：7B 用 AWQ-4bit，单卡 A100 吞吐 3x
3. 缓存：高频政策问答走 Prompt 缓存，命中率 40%
4. vLLM 连续批处理 + HPA 按负载伸缩
结果：月成本 ¥30万 → ¥4万（降 ~87%），P99 延迟 2.1s → 0.8s
```

这个案例说明：推理优化不是"学术玩具"，是实打实的利润。作为 Java 架构师，你能设计这套"路由 + 量化 + 缓存 + 弹性"的推理治理体系，就是稀缺的"既懂模型又懂工程"的人。

---

## 八（续）、生产部署拓扑：vLLM + Java Agent 怎么摆

把前面所有点收进一张生产拓扑，看清 Java 工程师你在哪一层：

```text
                    ┌─────────────────────────────────────────────┐
   用户/前端         │              Kubernetes 集群                │
      │             │                                              │
      ▼             │   ┌──────────────┐    ┌──────────────────┐   │
  ┌────────┐  HTTP  │   │ Gateway      │───▶│ Agent 服务(Java)  │   │
  │  Web   │───────▶│   │(限流/路由/   │    │ Spring AI/ Lang  │   │
  └────────┘        │   │ 鉴权)        │    │ 4j 编排 + 缓存    │   │
                   │   └──────────────┘    │ 模型路由决策       │   │
                   │                       └────────┬─────────┘   │
                   │                                │ OpenAI 兼容   │
                   │                                ▼              │
                   │                       ┌──────────────────┐   │
                   │                       │ vLLM 推理集群     │   │
                   │                       │ 底座(量化 AWQ)    │   │
                   │                       │ + 多 LoRA 动态挂载 │   │
                   │                       │ PagedAttention    │   │
                   │                       │ 连续批处理        │   │
                   │                       │ (GPU 节点池)      │   │
                   │                       └──────────────────┘   │
                   │                                              │
                   │   Prometheus 抓 vLLM + Agent 指标 → Grafana  │
                   └─────────────────────────────────────────────┘
```

你的 Java Agent 服务**不直接碰 GPU**，只通过 OpenAI 兼容 HTTP 调 vLLM。这种解耦让"推理引擎升级（换 vLLM 版本/换量化）"和"Agent 业务逻辑"互不影响——这也是 lesson01 讲的"关注点分离"。

<callout emoji="✅"><p>架构纪律：Java 业务层永远通过标准接口（OpenAI 兼容）消费模型能力，绝不在业务进程里加载模型权重。这样量化策略、LoRA 组合、GPU 扩缩都能在推理层独立演进，业务代码零改动。这是生产级 Agent 的基本素养。</p></callout>

## 八（再续）、vLLM 关键监控指标（配合 lesson04 看板）

推理层也要被看见，否则成本优化是盲调。vLLM 暴露的核心指标：

```text
vLLM / 推理层关键指标：
- vllm:gpu_cache_usage_perc     KV Cache 占用率（>90% 说明显存吃紧，该降 max-len 或加卡）
- vllm:num_requests_waiting     排队请求数（突增=吞吐不足，需扩副本）
- vllm:time_to_first_token      TTFT 首 token 延迟（长上下文或批满时升）
- vllm:inter_token_latency      逐 token 延迟
- vllm:request_throughput       每秒请求数（吞吐核心指标）
- GPU 利用率                    （长期 < 40% 说明批处理没吃满，浪费钱）
```

```yaml
# Prometheus 抓取 vLLM（vLLM 自带 /metrics 端点）
scrape_configs:
  - job_name: 'vllm'
    static_configs:
      - targets: ['vllm-service:8000']
# 告警：KV Cache 占用持续 >90% 或 排队请求 >50 → 扩副本
```

<callout emoji="💡"><p>一个常被忽略的优化信号：GPU 利用率长期低但吞吐也低 → 多半是"请求太短、批没攒起来"或"max-num-seqs 太小"。调大连续批处理的并发上限往往比加卡更省钱。先读指标再决策，别盲目堆硬件。</p></callout>

## 八（补）、常见推理优化陷阱

```text
陷阱1：盲目 4-bit，核心业务质量崩
  解：按业务敏感度分级量化，核心留 INT8/FP16，配合评测闭环

陷阱2：vLLM 参数不调，KV 占用爆显存
  解：按真实上下文长度设 --max-model-len，监控 gpu_cache_usage

陷阱3：没做缓存/路由，全量打大模型
  解：先治流量结构（缓存+路由），再谈硬件，往往省 80%+

陷阱4：Java 业务进程里直接加载模型
  解：模型永远在推理层(vLLM)，业务走 OpenAI 兼容接口

陷阱5：忽视 TTFT，用户觉得"卡"
  解：长上下文场景重点压 TTFT（KV 量化、预填充优化）
```

<callout emoji="⚠️"><p>推理优化最大的坑是"为优化而优化"——不看指标、不配评测、盲目追 4-bit 或盲目堆卡。正确姿势：先用第十六章评测量化质量底线，再用 vLLM 指标找到真实瓶颈（显存？吞吐？延迟？），针对性优化。每一步都要能量化收益。</p></callout>

---

## 八（部）、本地与边缘轻量推理：Ollama / llama.cpp

不是所有场景都要上 vLLM 集群。轻量、本地、边缘、隐私场景，用 **Ollama / llama.cpp**：

```text
推理方案选型：
vLLM            ：高并发服务端、多 LoRA、连续批处理，吃 GPU
llama.cpp       ：CPU/GPU 通吃、2-bit~8-bit 量化、极省资源，适合边缘
Ollama          ：llama.cpp 的友好封装，一行起模型，开发/单机首选
云端 API        ：零运维，按量付费，适合起步/弹性峰谷

典型分工：
  开发调试 / 单机 Demo     → Ollama 起一个量化模型
  边缘设备 / 隐私不出域    → llama.cpp 跑 2-4bit 量化
  生产高并发 Agent 服务    → vLLM 集群 + 多 LoRA
  快速验证 / 流量峰谷大    → 云端 API（如 DashScope / OpenAI）
```

Ollama 起一个 AWQ/GGUF 量化模型（开发态）：

```bash
# 拉起一个 4-bit 量化的 7B 模型（本地，CPU 也能跑小模型）
ollama run qwen2.5:7b-instruct-q4_K_M
# 它同样暴露 OpenAI 兼容接口（默认 http://localhost:11434/v1）
# Java 侧 base-url 指过去即可，零代码改动
```

<callout emoji="💡"><p>架构灵活性：因为 vLLM / Ollama / 云端 API 都是 OpenAI 兼容接口，你的 Java Agent 通过"换 base-url"就能在本地开发、边缘部署、云端生产之间无缝切换。这就是 lesson01 强调的"供应商抽象"——别把模型供应商焊死在业务代码里。</p></callout>

---

## 九、面试自测

1. **推理成本花在哪？** —— 显存（权重+KV Cache）、吞吐、延迟、GPU 单价。
2. **KV Cache 为什么关键？** —— 缓存历史 K/V 把生成从 O(N²) 降到 O(N)，但占大量显存；PagedAttention 解决碎片。
3. **量化怎么选？** —— INT8 默认稳、FP8 有硬件优先、AWQ-4bit 省显存提吞吐。
4. **vLLM 三杀手锏？** —— PagedAttention、连续批处理、多 LoRA。
5. **Java 怎么调 vLLM？** —— OpenAI 兼容接口，改 base-url 即可（Spring AI/HttpClient）。
6. **成本治理四招？** —— 缓存、模型路由、量化、弹性批处理。

---

## 十、小结

这一节给整个训练营收了尾，也把"模型经营"的能力交给你：

1. **算账先行**：推理成本 = 显存 × 吞吐 × 单价；KV Cache 是常被忽视的显存大头。
2. **量化**：INT8 稳、FP8 有硬件优先、AWQ-4bit 省显存提吞吐；用评测兜住质量。
3. **KV Cache**：空间换时间，PagedAttention（vLLM）把利用率从 ~20% 拉到 ~90%。
4. **vLLM**：连续批处理 + PagedAttention + 多 LoRA，吞吐数倍提升，OpenAI 兼容接口。
5. **Java 调用**：base-url 指向 vLLM 即可（Spring AI / HttpClient），零改动切换供应商。
6. **成本治理**：缓存 > 路由 > 量化 > 弹性，先治流量结构再谈硬件；配合评测闭环守质量红线。

到这里，《Java 版 Agent 架构师训练营》十八章全部讲完。你从 Agent 基础、框架、多智能体、RAG、上下文工程、评测、部署平台工程，一路走到了微调与推理优化——一条完整的"能上线、能面试、能交付"的 Java Agent 架构师成长路径。恭喜你走到这里。

---

## 延伸阅读 / 引用文档

1. vLLM 官方文档（PagedAttention / 连续批处理 / 多 LoRA）：https://docs.vllm.ai/
2. vLLM 论文（PagedAttention）：https://arxiv.org/abs/2309.06180
3. HuggingFace 量化（GPTQ/AWQ/INT8）指南：https://huggingface.co/docs/transformers/main/en/quantization/overview
4. AutoAWQ 仓库（4-bit 量化）：https://github.com/casper-hansen/AutoAWQ
5. KV Cache 与推理优化综述：https://arxiv.org/abs/2312.11544
6. Spring AI OpenAI（兼容 endpoint 配置）：https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html

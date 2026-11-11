# 第十章第六节：【新增】成本治理：Token 计费、提示词缓存、模型路由与预算控制

大家好，我是秋天。

前面五节把 Agent 做稳了（可观测、可测、可控、可降级、安全），但还有个老板最关心的事没讲——**钱**。LLM 按 token 计费，一个没治理的 Agent，可能上线一个月账单惊掉下巴：有人用长上下文反复灌大文档、有人把 GPT-4o 当打字机随便调、有人 prompt 越写越胖导致每次多烧几倍 token。这节课讲**成本治理（Cost Governance）**：token 计费模型、提示词缓存、模型路由、预算控制四板斧。

学完这节课，你能掌握：

1. **Token 计费模型**：怎么算钱、输入/输出/cache 命中各自单价。
2. **提示词缓存（Prompt Caching）**：复用前缀 token，省 50%~90% 成本。
3. **模型路由（Model Routing）**：简单任务用小模型、复杂任务用大模型。
4. **预算控制**：按用户/按日设上限、超预算降级或拦截。
5. **Java 落地**：LangChain4j / Spring AI 的 token 统计与缓存接入、预算中间件。

<callout emoji="💡"><p>招聘映射：JD 里"成本优化""模型路由""Token 预算管理"是 2025–26 企业落地的高频新项。很多团队 Agent Demo 做得漂亮，一算账发现"单个对话成本 2 元、日均 10 万对话 = 月烧 600 万"，直接被砍。会成本治理的人，是老板眼里的"省钱英雄"。</p></callout>

---

## 一、钱是怎么烧掉的

```
单次对话成本 = 输入token × 输入单价 + 输出token × 输出单价
             (若命中缓存) 缓存部分按 cache 单价(通常 1/10)

成本放大器(隐蔽烧钱点)：
  1. 超长系统提示: 每次请求都重复发送大段 system prompt → 输入 token 膨胀
  2. 全用贵模型: 分类/摘要也用 GPT-4o, 其实 mini 就够
  3. 无缓存: 相同前缀反复付费
  4. 无限迭代: 评估-优化循环不设上限(第九章第五节)
  5. 上下文腐烂: 往对话里塞越来越多历史(第十五章)
```

<callout emoji="✅"><p>判断框架：凡是"每次请求都重复发送的固定内容"（系统提示、知识库前缀、工具定义），一律该上**提示词缓存**；凡是"任务有难易之分"的，一律该上**模型路由**。</p></callout>

---

## 二、Token 计费与统计（Java 接入）

LangChain4j 的 `OpenAiChatModel` 响应里带 `tokenUsage`，可用来统计成本。

```java
// ch10-engineering/src/main/java/demo/cost/TokenAccounting.java
package demo.cost;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.message.UserMessage;

public class TokenAccounting {

    // 单价(示意, 真实以官网为准, 单位: 美元/千token)
    static final double IN_PRICE = 0.000005;   // $5 / 1M input
    static final double OUT_PRICE = 0.000015;  // $15 / 1M output

    static double cost(Response<AiMessage> resp) {
        var u = resp.tokenUsage();
        return (u.inputTokenCount() * IN_PRICE) + (u.outputTokenCount() * OUT_PRICE);
    }

    public static void main(String[] args) {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini")
                .build();
        Response<AiMessage> resp = model.generate(UserMessage.from("你好"));
        System.out.println("本次输入token=" + resp.tokenUsage().inputTokenCount());
        System.out.println("本次输出token=" + resp.tokenUsage().outputTokenCount());
        System.out.printf("本次成本=$%.6f%n", cost(resp));
    }
}
```

把每次调用的 token 数上报到第一节的 `agent.tokens.total` 指标，就能在 Grafana 看"成本趋势"和"哪个意图最贵"，这是成本治理的数据底座。

---

## 三、提示词缓存（Prompt Caching）

很多模型的 API 支持 **prompt caching**：把请求里"稳定不变的前缀"（系统提示、工具定义、长文档）标记为可缓存，命中缓存的前缀 token 按 1/10 价格计费。

```
无缓存(每次都付费全量):
   请求 = [系统提示(500tok) + 用户问题(50tok)]
   每次成本 = 550tok × 单价

有缓存(前缀复用):
   请求 = [CACHE 系统提示(500tok, 命中) + 用户问题(50tok)]
   系统提示按 cache 价(1/10), 用户问题按正常价
   → 节省约 90% 的固定前缀成本
```

OpenAI 的 `prompt caching` 是自动的（相同前缀自动命中），Anthropic 用 `cache_control` 显式标记。LangChain4j 通过 chat 参数可传：

```java
// ch10-engineering/src/main/java/demo/cost/PromptCacheDemo.java
package demo.cost;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.data.message.UserMessage;

public class PromptCacheDemo {

    // 把"稳定系统提示"放前面, 模型自动把相同前缀缓存(OpenAI)
    static final String STABLE_SYSTEM = """
        你是某电商客服助手。公司政策: 退款在3个工作日内原路返回,
        运费由买家承担除非质量问题, 会员享优先客服。
        以下是标准话术库(固定, 很长)......
        """;

    public static void main(String[] args) {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini")
                .build();
        // 大量用户问题都带相同的 STABLE_SYSTEM 前缀 → 自动命中缓存
        for (String q : new String[]{"怎么退款", "运费谁出", "会员有啥特权"}) {
            model.generate(UserMessage.from(STABLE_SYSTEM + "\n用户:" + q));
        }
        System.out.println("相同系统提示前缀已自动缓存, 后续请求省成本");
    }
}
```

<callout emoji="⚠️"><p>缓存命中前提是"前缀完全一致且足够长（通常 ≥1024 token）"。如果你每次把变化的用户问题拼在系统提示前面，前缀就变了，缓存永远不命中。结构要"稳定前缀在前、变化内容在后"。</p></callout>

---

## 四、模型路由（Model Routing）：按任务难度选模型

不同任务用不同模型，是成本治理最有效的一招。

```
任务难度 → 模型选择：
   分类/抽取/简单摘要  → gpt-4o-mini / qwen-turbo (便宜, 几倍省)
   复杂推理/生成/代码  → gpt-4o / qwen-max        (贵但强)
   极高合规/安全       → 自建小模型专调            (可控)

效果: 80% 简单请求走 mini, 仅 20% 走 4o
     → 整体成本可能降到原来的 1/3 ~ 1/5
```

```java
// ch10-engineering/src/main/java/demo/cost/ModelRouter.java
package demo.cost;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.data.message.UserMessage;

import java.util.Map;
import java.util.function.Function;

public class ModelRouter {

    // 按难度映射不同模型(实际是 ChatModel 实例)
    static final Map<Difficulty, ChatLanguageModel> MODELS = Map.of(
            Difficulty.EASY, OpenAiChatModel.builder()
                    .apiKey(System.getenv("OPENAI_API_KEY")).modelName("gpt-4o-mini").build(),
            Difficulty.HARD, OpenAiChatModel.builder()
                    .apiKey(System.getenv("OPENAI_API_KEY")).modelName("gpt-4o").build());

    enum Difficulty { EASY, HARD }

    // 简单分类器: 用正则/关键词判断难度(生产可用小模型)
    static Difficulty assess(String task) {
        if (task.length() < 30 && !task.contains("代码") && !task.contains("分析"))
            return Difficulty.EASY;
        return Difficulty.HARD;
    }

    public static void main(String[] args) {
        String[] tasks = {"查订单状态", "分析竞品并给出战略建议"};
        for (String t : tasks) {
            Difficulty d = assess(t);
            ChatLanguageModel m = MODELS.get(d);
            String ans = m.generate(UserMessage.from(t)).content().text();
            System.out.println("[" + d + "] " + t + " => " + ans.substring(0, Math.min(20, ans.length())));
        }
    }
}
```

这正是第九章第二节"路由"在**成本维度**的应用——路由不只是选处理路径，也是选成本档位。

<callout emoji="💡"><p>模型路由和第九章的路由天然互补：第九章按"业务域"路由到不同 Agent；本节按"成本/能力"路由到不同模型。两者叠加，既专业又省钱。</p></callout>

---

## 五、预算控制：上限 + 降级 + 拦截

成本治理的最后一公里是**预算墙**：超预算就拦，别等月底账单。

```
预算控制层级：
   全局日预算: 今天 token 花费达 $X → 全站降级到 mini / 限流
   按用户预算: 单用户单日超 $Y → 该用户转规则/转人工
   按意图预算: 某贵意图(如长文生成)超量 → 限制频次
   硬上限:     token 总量触及红线 → 拒绝新请求并告警
```

```java
// ch10-engineering/src/main/java/demo/cost/BudgetGuard.java
package demo.cost;

import java.util.concurrent.atomic.AtomicLong;

public class BudgetGuard {

    private final AtomicLong spent = new AtomicLong(0);
    private final double dailyBudget; // 美元

    public BudgetGuard(double dailyBudget) { this.dailyBudget = dailyBudget; }

    /** 在调用前检查, 返回是否放行; 调用后调用 charge 扣费 */
    synchronized boolean allow(double estimatedCost) {
        if (spent.get() + estimatedCost > dailyBudget) {
            return false; // 超预算, 拦截或降级
        }
        return true;
    }

    void charge(double cost) { spent.addAndGet(cost); }

    public static void main(String[] args) {
        BudgetGuard g = new BudgetGuard(10.0); // 日预算 $10
        double est = 0.01;
        for (int i = 0; i < 2000; i++) {
            if (!g.allow(est)) {
                System.out.println("预算耗尽, 第" + i + "次请求被拦截/降级");
                break;
            }
            g.charge(est);
        }
        System.out.println("当日已花费=$" + g.spent.get());
    }
}
```

```
预算超限的降级策略(和第四节呼应)：
   超 80% → 告警 + 新请求强制走最便宜模型
   超 100% → 拦截高成本意图, 简单意图走规则, 复杂意图转人工
   配合监控(第一节): 预算消耗曲线 + 告警
```

<callout emoji="⚠️"><p>预算控制必须在"调用前"估算并拦截，而不是"调用后记账"。调用后才知道超预算，钱已经花了。估算可用历史平均 token 数 × 单价做保守预估。</p></callout>

---

## 六、提示词瘦身：少一个 token 少一份钱

提示词越长，每次输入成本越高，且挤占上下文（第十五章"有效上下文"）。治理要从 prompt 本身下手。

```
瘦身手段：
   1. 删废话: "作为一个非常有经验的、专业的、资深的..." → 直接说角色
   2. 用结构化: 列表/JSON 比散文省 token 且更准
   3. 抽公共前缀: 稳定内容做缓存(第三节)
   4. 动态注入: 只在需要时把知识库内容拼进(第七章 RAG), 不要全塞系统提示
   5. 版本控制: 改 prompt 后比对 token 数(第三节 Prompt as Code)
```

```yaml
# ch10-engineering/src/main/resources/cost-config.yml
cost:
  budget:
    daily-usd: 50
    per-user-daily-usd: 0.5
    warn-ratio: 0.8
  model-routing:
    easy: gpt-4o-mini
    hard: gpt-4o
  cache:
    enabled: true
    min-prefix-tokens: 1024
  token-limit:
    max-input-per-request: 8000
    max-output-per-request: 2000
```

---

## 七、把成本治理串进统一中间件

把计费、缓存提示、模型路由、预算全部做成一层"成本治理中间件"，业务代码无感接入。

```java
// ch10-engineering/src/main/java/demo/cost/CostAwareClient.java
package demo.cost;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.data.message.UserMessage;

public class CostAwareClient {

    private final ChatLanguageModel easyModel;
    private final ChatLanguageModel hardModel;
    private final BudgetGuard budget;

    public CostAwareClient(ChatLanguageModel easy, ChatLanguageModel hard, BudgetGuard budget) {
        this.easyModel = easy; this.hardModel = hard; this.budget = budget;
    }

    public String chat(String task) {
        // 1. 预算检查: 超了走便宜模型或拦截
        if (!budget.allow(0.01)) return "【降级】预算紧张，已转规则回复";
        // 2. 模型路由: 简单走便宜, 复杂走强
        ChatLanguageModel m = ModelRouter.assess(task) == ModelRouter.Difficulty.EASY
                ? easyModel : hardModel;
        // 3. 调用(稳定前缀在前, 自动缓存)
        String ans = m.generate(UserMessage.from(PromptCacheDemo.STABLE_SYSTEM + "\n用户:" + task))
                .content().text();
        // 4. 记账(真实从 tokenUsage 算)
        budget.charge(0.01);
        return ans;
    }
}
```

```
请求 ─▶ [预算检查] ─(超预算→降级)─▶ [模型路由] ─▶ [缓存前缀调用] ─▶ [记账+上报指标]
   (成本治理中间件, 业务侧只调 chat())
```

---

## 八、企业场景：日均百万对话的账单保卫战

```
场景: 客服 Agent 日均 100 万对话
   无治理: 全用 gpt-4o, 平均 1500 tok/次 → 月成本 ≈ 百万×1500×($15+$5)/1e6 ×30 ≈ $900k
   治理后:
     - 70% 走 mini(路由)         → 成本降 ~3x
     - 系统提示缓存命中(前缀)    → 输入成本降 ~50%
     - 提示词瘦身(-30% token)    → 再降
     - 预算墙防突发              → 不爆雷
   最终月成本可能压到 $100k 以内, 省下 80%+
```

```text
成本治理四板斧(记牢):
   计费 ── 看清钱花哪(指标化)
   缓存 ── 固定前缀复用, 省 50%-90%
   路由 ── 简单用便宜模型, 省 3-5x
   预算 ── 设墙防爆雷, 超限降级
```

---

## 九、与全课程的连接

```
成本治理 衔接：
   ── 第九章路由/编排: 路由选模型是成本治理的执行点
   ── 第九章评估-优化: 迭代上限是成本护栏(防死循环烧钱)
   ── 第十章可观测: token 指标驱动预算告警(第一节)
   ── 第十章 Prompt as Code: prompt 版本对比 token 数(第三节)
   ── 第十五章上下文工程: 有效上下文/上下文腐烂=成本控制的核心
   ── 第十八章推理优化: vLLM/量化/自托管进一步降本
```

---

## 十、成本归因：钱到底花在哪

治理的前提是"看清"。把成本按维度拆开，才能精准优化。

```
成本归因维度(在指标里打 tag)：
   按模型     gpt-4o vs gpt-4o-mini 各花多少
   按意图     退款类/技术类/投诉类 各自成本
   按用户     哪个用户/租户烧钱最多(防滥用)
   按环节     输入 token / 输出 token / 缓存命中 占比
```

```java
// ch10-engineering/src/main/java/demo/cost/CostAttribution.java
package demo.cost;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;

public class CostAttribution {
    private final MeterRegistry meter;

    public CostAttribution(MeterRegistry meter) { this.meter = meter; }

    /** 按 模型+意图+环节 记录成本, 便于 Grafana 下钻 */
    void record(double usd, String model, String intent, String phase) {
        Counter.builder("agent.cost.usd")
               .tag("model", model)
               .tag("intent", intent)
               .tag("phase", phase) // input/output/cache
               .register(meter)
               .increment(usd);
    }

    public static void main(String[] args) {
        // 示意: 一次调用成本拆解
        System.out.println("记录: 模型=gpt-4o, 意图=退款, 环节=input, $0.002");
    }
}
```

<callout emoji="💡"><p>归因后常见发现：80% 成本来自 20% 的"长尾复杂意图"。针对性地把这 20% 做模型路由/缓存/提示词瘦身，ROI 最高。</p></callout>

---

## 十一、自托管与推理优化（衔接第十八章）

当调用量足够大，走 API 可能不如**自托管开源模型**划算；推理优化进一步降本。

```
成本拐点示意：
   云端 API:  固定单价 × token, 量越大越贵(线性)
   自托管:    一次性 GPU 成本 + 运维, 但边际成本趋近 0
   → 日调用超阈值(如千万 token/日)后, 自托管更省

推理优化降本:
   - 量化(INT8/FP8): 显存减半, 吞吐翻倍
   - vLLM/KV Cache: 吞吐提升数倍
   - 小模型蒸馏: 用 7B 模型替代调 API 的 70B 任务
```

```yaml
# 成本治理的"混合部署"策略
cost:
  routing:
    # 简单/高频/隐私敏感 → 自托管小模型
    self-hosted: [分类, 摘要, 脱敏]
    # 复杂/低频/要求高 → 云端强模型
    cloud: [复杂推理, 代码生成]
  inference-optim:
    quantization: fp8
    engine: vllm
```

<callout emoji="⚠️"><p>自托管不是"更便宜"的万能解——它带来 GPU 运维、模型更新、质量保证的额外成本。量没到拐点前，先做好 API 侧的路由+缓存+预算，性价比最高。</p></callout>

---

## 小结

- 成本 = 输入×输入价 + 输出×输出价；隐蔽烧钱点：超长系统提示、全用贵模型、无缓存、无限迭代、上下文膨胀。
- 提示词缓存：稳定前缀在前、变化内容在后，命中后前缀按 1/10 计费，省 50%~90%。
- 模型路由：简单任务走 mini/小模型、复杂走强模型，整体成本可降 3~5 倍，是第九章路由在成本维度的应用。
- 预算控制：全局/按用户/按意图设上限，调用前估算拦截，超限降级到规则/人工，配合监控告警。
- 提示词瘦身 + 成本中间件把计费/缓存/路由/预算统一封装，业务无感接入；对日均百万对话省下 80% 成本。

## 延伸阅读 / 引用文档

1. OpenAI 定价与 Prompt Caching：https://platform.openai.com/docs/guides/prompt-caching
2. Anthropic Prompt Caching 文档：https://docs.anthropic.com/en/docs/build-with-claude/prompt-caching
3. LangChain4j Token Usage：https://docs.langchain4j.dev
4. Spring AI 模型选型（模型路由）：https://docs.spring.io/spring-ai/reference/api/chatclient.html
5. 上下文工程（第十五章）：https://www.anthropic.com/research/long-context
6. vLLM 推理优化（降本）：https://docs.vllm.ai

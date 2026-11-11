# 第十六章第3节：LLM-as-Judge——校准、偏差与对抗（EvalScope 实战）

大家好，我是秋天。

上节课的 RAGAS，本质上就是"请一个 LLM 当裁判"给你打分。这节课把这个思路挑明、讲透：**LLM-as-Judge**——用大模型替代或辅助人类做评测判断。它便宜、可规模化，但**有偏见、会翻车**。你不校准、不防偏，评测结果就是个笑话。

这节课讲什么、学完掌握什么：

- 讲什么：LLM-as-Judge 的原理与适用边界；三大典型偏差（位置偏差 / 长度偏差 / 自我偏好偏差）及对策；**校准（calibration）** 怎么做；用 **EvalScope**（国产评测框架）做实战，含 Java 侧集成与校准流程。
- 学完掌握：你能设计一个带偏差防护的 LLM 裁判；你能跑通 EvalScope 出报告；你知道怎么用"人类标注对齐"校准裁判模型。

<callout emoji="💡"><p>招聘映射：JD 原话"熟悉 LLM-as-Judge 并了解其偏差与校准""有 EvalScope / Prompt 评测落地经验""能设计多模型交叉评测降低偏差"。这节课直接命中"评测体系"的高级要求，是和初级"会调 RAGAS"拉开差距的关键。</p></callout>

---

## 一、LLM-as-Judge 是什么、为什么要用

### 1.1 定义

**LLM-as-Judge**：用另一个 LLM（裁判模型）对"待评测模型"的输出做质量判断（打分/打标/排名）。它替代昂贵缓慢的人工标注，能规模化、可重复、零边际成本。

```
人工评测：慢（小时级/条）、贵、不可规模化
规则评测：快但只能评确定性问题（格式/解析）
LLM-as-Judge：快、便宜、能评"语义质量"（忠实度/相关性/安全性）
```

### 1.2 它在评测金字塔的位置

```
离线①：RAGAS 三大指标 ← 本质就是 LLM-as-Judge
在线③：采样打分、安全巡检 ← 也常用 LLM 裁判
红蓝④：攻击是否成功判定 ← 常用 LLM 裁判
```

<callout emoji="⚠️"><p>LLM-as-Judge 不是"银弹"。它相关性判断强、绝对打分弱；对"是否编造"敏感、对"事实真实性"未必可靠（裁判模型也可能幻觉）。正确用法是"裁判做初筛 + 人类抽检校准"，而不是"裁判说啥就是啥"。</p></callout>

### 1.3 一个最小可运行的 LLM 裁判（Python）

```python
# ch16-evaluation/judge/simple_judge.py
# 最朴素的 LLM-as-Judge：给裁判模型一个评分任务
import os
from openai import OpenAI

client = OpenAI(api_key=os.environ["OPENAI_API_KEY"])

def judge_faithfulness(question, context, answer):
    prompt = f"""你是严格的评测裁判。只判断【回答】是否完全基于【上下文】，
不引入上下文之外的信息。输出 JSON：{{"faithful": true/false, "reason": "..."}}

【问题】{question}
【上下文】{context}
【回答】{answer}
"""
    resp = client.chat.completions.create(
        model="gpt-4o-mini",
        messages=[{"role": "user", "content": prompt}],
        temperature=0,
        response_format={"type": "json_object"},
    )
    return resp.choices[0].message.content

# 示例
print(judge_faithfulness(
    "退款多久",
    "退款审核通过后24小时内原路退回。",
    "退款24小时内到账，换货需寄回。"   # "换货"无依据 → 应判 false
))
```

---

## 二、三大偏差（必须知道的坑）

LLM 裁判**不是客观尺子**，它有自己的"人性弱点"。三个最致命的偏差：

### 2.1 位置偏差（Position Bias）

**现象**：裁判模型倾向于给"排在第一位"的候选打高分，无论它实际好坏。多答案对比评测（A/B）时尤其明显。

```
实验：把同一对答案 A/B 交换顺序让裁判评，
      顺序 (A,B) → 判 A 好
      顺序 (B,A) → 判 B 好   ← 同一对，结论翻转 = 位置偏差
```

**对策**：对比评测时**交换顺序跑两次，取一致才算数**（或做位置平衡平均）。

```python
# ch16-evaluation/judge/debias_position.py
def judge_pair_balanced(judge, a, b):
    # 正序
    r1 = judge(f"哪个更好？\n候选1：{a}\n候选2：{b}")
    # 反序
    r2 = judge(f"哪个更好？\n候选1：{b}\n候选2：{a}")
    # 两次结论一致才采信
    return r1 == r2
```

### 2.2 长度偏差（Length Bias / Verbosity Bias）

**现象**：裁判模型倾向于给"更长、更啰嗦"的答案打更高分，即使短的更准。这对"简洁回答"类任务极不公平。

```
实验：同一正确结论，写成 50 字 vs 300 字，
      裁判给 300 字打的分平均高 0.2
```

**对策**：① 在裁判提示里明确要求"忽略长度，只看正确性"；② 用"成对对比"而非"绝对打分"（对比时长度偏差被削弱）；③ 对答案做长度归一化后再评。

### 2.3 自我偏好偏差（Self-Enhancement / Self-Preference Bias）

**现象**：裁判模型倾向于给"和自己风格/用词相似的答案"打高分。用 gpt-4o 当裁判评 gpt-4o 的输出，分数系统性偏高。

```
实验：用模型 X 当裁判，
      评 X 自己的输出 → 均分 8.5
      评模型 Y 的同等质量输出 → 均分 7.8   ← 自我偏好
```

**对策**：① 裁判模型与被评模型**不同源**（如用 Claude 评 GPT 输出）；② 多裁判交叉取平均；③ 人类校准（见 2.4）。

<callout emoji="💡"><p>面试高频：怎么降低 LLM 裁判偏差？标准答法三件套：① 位置平衡（交换顺序）；② 裁判与被评模型异源 + 多裁判交叉；③ 人类标注对齐做校准（calibration）。这三句能把"你会用 LLM 裁判"升级成"你懂怎么让裁判可信"。</p></callout>

### 2.4 其他偏差速览

| 偏差 | 现象 | 对策 |
|------|------|------|
| 权威偏差 | 答案提到"专家说"就给高分 | 提示强调"只看事实不论述" |
| 格式偏差 | 带 markdown/分点就高分 | 要求忽略排版只看内容 |
| 品牌偏差 | 提到知名公司名就高分 | 匿名化实体后再评 |
| 顺序-核对偏差 | 让裁判"找不同"易漏 | 用结构化 checklist |

---

## 三、校准（Calibration）：让裁判可信

### 3.1 什么是校准

**校准** = 用一批"人类标注的真值"去验证裁判模型的判断，量化它的偏差程度，然后**要么修正阈值、要么换更强裁判、要么只把裁判用于初筛**。

```
校准流程：
  1. 取 N 条样本，请人类专家标注"真实质量分/标签"（gold labels）
  2. 用 LLM 裁判对同一批打标签
  3. 算一致性（如 Cohen's Kappa / 准确率 / 相关系数）
  4. 若一致性 < 阈值 → 裁判不可信，要么换模型、要么只做初筛
  5. 记录"裁判 vs 人类"的偏差方向（如裁判普遍偏高 0.3）→ 后续扣分修正
```

### 3.2 一致性度量（Cohen's Kappa）

```python
# ch16-evaluation/judge/calibration.py
# 用 Kappa 衡量裁判与人类的一致性
from sklearn.metrics import cohen_kappa_score

# 人类标注（0/1 二分类：是否忠实）
human = [1, 0, 1, 1, 0, 1, 0, 1, 1, 0]
# LLM 裁判结果
judge = [1, 0, 1, 0, 0, 1, 1, 1, 1, 0]

kappa = cohen_kappa_score(human, judge)
print("Kappa =", round(kappa, 3))
# Kappa > 0.8 优秀；0.6~0.8 可接受；<0.6 裁判不可信
```

### 3.3 Java 侧：裁判一致性校验服务

```java
// ch16-evaluation/src/main/java/demo/judge/CalibrationService.java
package demo.judge;

import java.util.List;

/** 校准服务：对比 LLM 裁判与人工标注，算一致率 */
public class CalibrationService {

    public record CalibrationResult(double agreement, boolean trusted) {}

    public static CalibrationResult check(List<Integer> human,
                                           List<Integer> judge,
                                           double threshold) {
        int n = human.size();
        long agree = 0;
        for (int i = 0; i < n; i++) {
            if (human.get(i).equals(judge.get(i))) agree++;
        }
        double agreement = (double) agree / n;
        return new CalibrationResult(agreement, agreement >= threshold);
    }

    // 使用：若 agreement < 0.8，则裁判只用于初筛，最终由人类抽检
}
```

<callout emoji="⚠️"><p>校准不是一次性的。模型升级、提示词改了、业务域变了，裁判的偏差都会漂移。建议**每月重新校准一次**，并在 CI 里固化"校准数据集"。这是评测体系能否长期可信的关键。</p></callout>

---

## 四、EvalScope 实战

EvalScope 是魔搭（ModelScope）出品的国产评测框架，原生支持 LLM-as-Judge、RAGAS、多模型竞技场，对国内模型（Qwen / DeepSeek / 通义）支持好，且有可视化报告。

### 4.1 安装与最小运行（Python）

```bash
pip install evalscope
# 可选：用算子（内置 judge 模型）
```

### 4.2 用 EvalScope 跑 LLM-as-Judge

```python
# ch16-evaluation/evalscope/judge_demo.py
from evalscope import TaskConfig, run_task

# 配置一次 LLM-as-Judge 任务
task_config = TaskConfig(
    model="qwen2.5-72b-instruct",          # 被评模型
    api_url="https://dashscope.aliyuncs.com/compatible-mode/v1",
    api_key="os.environ['DASHSCOPE_API_KEY']",
    datasets=["general_qa"],               # 评测数据集
    judge_model="gpt-4o-mini",              # 裁判模型（异源！防自我偏好）
    eval_type="judge",                     # LLM-as-Judge 模式
    outputs_dir="./eval_results",
)

run_task(task_config=task_config)
# 输出：各维度分数 + 报告（含偏差防护参数）
```

### 4.3 EvalScope 的偏差防护参数

EvalScope 内置了对抗偏差的机制，关键是这些开关：

```python
# ch16-evaluation/evalscope/debias_config.py
task_config = TaskConfig(
    model="qwen2.5-72b-instruct",
    judge_model="gpt-4o-mini",
    eval_type="judge",
    # —— 偏差防护 ——
    pairwise_position_balance=True,   # 位置平衡：交换顺序跑两次
    judge_max_tokens=512,
    # 多裁判交叉
    judge_models=["gpt-4o-mini", "qwen-max"],  # 多裁判取均值
)
```

### 4.4 Java 侧集成 EvalScope（调用其服务接口）

Java 团队不一定要写 Python。EvalScope 可独立部署为评测服务，Java 通过 HTTP 触发并取结果：

```java
// ch16-evaluation/src/main/java/demo/judge/EvalScopeClient.java
package demo.judge;

import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;
import java.util.Map;

/** Java 调用 EvalScope 评测服务（HTTP），把评测接入 Java CI/平台 */
public class EvalScopeClient {

    private final String baseUrl;
    private final RestTemplate rt = new RestTemplate();

    public EvalScopeClient(String baseUrl) { this.baseUrl = baseUrl; }

    /** 触发一次评测任务，返回任务 id */
    public String submit(String model, String dataset, String judgeModel) {
        Map<String, Object> body = Map.of(
                "model", model,
                "dataset", dataset,
                "judge_model", judgeModel,
                "pairwise_position_balance", true);   // 位置平衡
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> resp = rt.postForEntity(
                baseUrl + "/v1/eval/submit",
                new HttpEntity<>(body, h), Map.class);
        return (String) resp.getBody().get("task_id");
    }

    /** 拉取评测报告 */
    public Map<?, ?> fetchReport(String taskId) {
        return rt.getForObject(baseUrl + "/v1/eval/report/" + taskId, Map.class);
    }
}
```

```bash
# Java 侧触发 → EvalScope 算分 → CI 读报告卡点
curl -X POST http://evalscope-svc/v1/eval/submit \
  -H 'Content-Type: application/json' \
  -d '{"model":"qwen2.5-72b","dataset":"general_qa","pairwise_position_balance":true}'
```

<callout emoji="✅"><p>EvalScope 的实战价值：① 内置偏差防护（位置平衡、多裁判），不用你自己造轮子；② 对国产模型支持好，国内团队友好；③ 可服务化，Java 栈通过 HTTP 集成，不必迁到 Python。这是"评测平台工程"的现成拼图。</p></callout>

---

## 五、校准完整流程（把第三节串起来）

给你一个能直接落地的校准 SOP：

```
┌────────────────────────────────────────────────────────────┐
│              LLM-as-Judge 校准流程 SOP                        │
├────────────────────────────────────────────────────────────┤
│ 1. 抽样 200 条（覆盖核心/边界/对抗）                         │
│ 2. 人类专家标注 gold labels（忠实/相关/安全）               │
│ 3. 用裁判模型（异源+多裁判）对同一批标注                     │
│ 4. 算 Kappa / 一致率                                         │
│ 5. Kappa>0.8 → 裁判可信，上线                               │
│    Kappa 0.6~0.8 → 仅初筛，人类抽检 20%                      │
│    Kappa<0.6 → 换更强裁判 / 改裁判提示，重校                 │
│ 6. 记录"裁判 vs 人类"偏差方向，必要时扣分修正               │
│ 7. 每月重校一次（模型/业务漂移）                            │
└────────────────────────────────────────────────────────────┘
```

```yaml
# 校准配置（落进评测平台）
judge-calibration:
  sample-size: 200
  human-label-source: expert-pool
  judge-models: [gpt-4o-mini, qwen-max]   # 多裁判
  position-balance: true
  kappa-threshold: 0.8
  recheck-cadence: monthly
  on-low-kappa: switch-judge-or-human-sampling
```

---

## 六、对抗：怎么"骗过"裁判与怎么防

红蓝对抗（lesson01 ④层）里，红队常攻击评测本身。常见手法与防御：

| 攻击手法 | 说明 | 防御 |
|----------|------|------|
| 答案注水 | 加无关长文本拉长度分 | 长度归一化 + 成对对比 |
| 权威背书 | 塞"专家说"骗权威偏差 | 匿名化 + 强调只看事实 |
| 顺序博弈 | 在 A/B 中抢第一位置 | 位置平衡（交换跑两次） |
| 同源作弊 | 用同模型当裁判自抬 | 异源裁判 + 多裁判交叉 |

<callout emoji="💡"><p>一个残酷事实：如果你的评测只靠一个同源 LLM 裁判，红队能轻松"刷分"。生产级评测必须"异源 + 位置平衡 + 人类校准 + 多裁判交叉"四件套。这也是为什么评测工程师值钱——不是会调 API，是能让"分数"经得起红队推敲。</p></callout>

---

## 七、企业落地：金融问答的 LLM 裁判校准

某银行政策问答的 LLM-as-Judge 落地：

```
配置：
  • 裁判：gpt-4o-mini + qwen-max（双裁判异源，防自我偏好）
  • 位置平衡：开启（A/B 各跑一次）
  • 每月抽 200 条人工标注校准

校准结果（首月）：
  • Faithfulness 判断 Kappa = 0.86 → 可信，直接采用
  • Safety 判断 Kappa = 0.72 → 仅初筛，人工抽检 30%

效果：
  • 评测成本从"人工 200 条/周"降到"自动 + 人类抽检 40 条/周"
  • 一次模型升级后 Kappa 跌到 0.65 → 触发重校，发现新模型更啰嗦
    → 开启长度归一化后 Kappa 回到 0.81
```

**为什么这样设计？** 金融场景"错一句即合规事故"，裁判必须可信。用双异源裁判+位置平衡降低偏差，用月度校准兜住漂移，用 Kappa 阈值分级决定"裁判能否直接采用"。这是 LLM-as-Judge 的生产级用法，不是玩具。

---

## 八、面试自测

1. **LLM-as-Judge 三大偏差？** —— 位置（偏好首位）、长度（偏好长答案）、自我偏好（偏好同模型）。
2. **怎么降低偏差？** —— 位置平衡、异源+多裁判、人类校准三件套。
3. **校准用什么度量？** —— Cohen's Kappa / 一致率，>0.8 可信。
4. **EvalScope 怎么用？** —— 配置 judge_model 异源、开位置平衡、多裁判，可服务化供 Java 调用。

---

## 九、小结

这节课把"用 LLM 当裁判"讲成了可信工程：

1. **LLM-as-Judge 本质**：用 LLM 替代人工做语义质量判断，便宜可规模化，但非银弹（绝对打分弱、可能幻觉）。
2. **三大偏差**：位置偏差（偏好首位）、长度偏差（偏好长答案）、自我偏好偏差（偏好同模型输出），各有对策。
3. **校准（calibration）**：用人类标注集算 Kappa，>0.8 可信、0.6~0.8 仅初筛、<0.6 换裁判；每月重校防漂移。
4. **EvalScope 实战**：内置位置平衡、多裁判异源等偏差防护；可服务化让 Java 栈通过 HTTP 集成，不迁 Python。
5. **对抗**：红队能刷分，生产级评测必须"异源+位置平衡+校准+多裁判"四件套。

下一节（lesson04）我们把整章收口：评测-归因-优化-回归闭环与生产监控。

---

## 延伸阅读 / 引用文档

1. EvalScope 官方文档（LLM-as-Judge 实战）：https://evalscope.readthedocs.io/
2. *Judging LLM-as-a-Judge*（偏差与校准论文）：https://arxiv.org/abs/2402.10669
3. Anthropic 评测工程（含裁判偏差讨论）：https://www.anthropic.com/engineering/evaluating-agentic-systems
4. RAGAS（LLM 裁判的 RAG 应用）：https://docs.ragas.io/
5. Cohen's Kappa 解释（校准度量）：https://en.wikipedia.org/wiki/Cohen%27s_kappa
6. ModelScope EvalScope GitHub：https://github.com/modelscope/evalscope

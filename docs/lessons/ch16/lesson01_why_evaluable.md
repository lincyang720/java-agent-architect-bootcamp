# 第十六章第1节：为什么 Agent 必须可评测——评测金字塔与四层指标

大家好，我是秋天。

第十五章我们讲怎么把上下文管好，这一章讲怎么证明"你的 Agent 真的行"。很多团队把 Agent  Demo 跑通就觉得万事大吉，上线一周被业务方一句"你这玩意儿准吗"问哑了——**不能量化"准不准"的 Agent，在生产里就是一颗定时炸弹**。

这节课讲什么、学完掌握什么：

- 讲什么：**评测金字塔**（离线基准 → 沙箱仿真 → 在线生产 → 红蓝对抗）和它解决的问题域；**四层指标**（性能 / 质量 / 效果 / 业务）各自衡量什么、怎么选。
- 学完掌握：你能给一个 Agent 设计一套分层评测方案；你能区分"性能达标但业务不达标"的陷阱；你知道这套能力在 JD 里怎么写。

<callout emoji="💡"><p>招聘映射：JD 几乎必写"能搭建 Agent 评测与质量闭环""熟悉 RAGAS/EvalScope/LLM-as-Judge""有离线+在线评测落地经验"。评测闭环是和"上下文工程"并列的 2025–26 头号招聘要求。本章四节全部围绕它，这节课是总纲。</p></callout>

---

## 一、为什么 Agent 必须可评测

### 1.1 LLM 输出的"不可控"是本质问题

传统软件：输入确定 → 输出确定，单元测试能证明对错。Agent：同样的输入，模型可能今天对、明天错、换个措辞又错。**不可控 ≠ 不可评测**，但必须用"统计化、规模化"的方式去评测，而不是"跑通一个 happy path 就上线"。

### 1.2 不评测的真实后果

```
不评测的 Agent 上线后：
  • 业务方问"准确率多少" → 答不上来，信任崩塌
  • 改一次提示词/模型 → 不知道是变好还是变差，靠运气
  • 线上出一个 badcase → 无法归因，无法防止复发
  • 监管问"你们怎么保证回答合规" → 无证据，被罚
```

<callout emoji="⚠️"><p>一个反直觉的真相：Agent 不上评测，不是"省了时间"，是"把不确定性债务藏到上线后集中爆发"。生产事故的成本是 Demo 期评测成本的 100 倍。资深工程师的第一反应是"先测再上"，不是"先上再救"。</p></callout>

### 1.3 可评测 = 可迭代的前提

评测给你两样东西：**基线（baseline）** 和 **回归护栏（regression guard）**。没有基线你不知道改进有没有效，没有护栏你改进 A 却搞坏 B 而不自知。这正是 lesson04 要讲的"闭环"。

---

## 二、评测金字塔：四层，各解决不同问题

我给你一个自底向上、成本递增、保真度递增的金字塔。每一层回答一个不同的问题：

```
                       ┌─────────────┐
                       │ ④ 红蓝对抗   │  最真实、最贵、最难自动化
                       │ Red/Blue     │  问题："有没有人能搞垮它？"
                       ├─────────────┤
                   ③  │ 在线生产评测  │  真实流量、真实用户
                      │ Online Prod  │  问题："真实用户满意吗？"
                      ├─────────────┤
                  ②  │ 沙箱仿真评测  │  模拟环境、可控故障
                     │ Sandbox Sim  │  问题："复杂多步任务稳吗？"
                     ├─────────────┤
                 ①  │ 离线基准评测  │  固定数据集、可重复、便宜
                    │ Offline Bench │  问题："单轮/组件准不准？"
                    └─────────────┘
```

### 2.1 层①离线基准评测（Offline Benchmark）

- **回答的问题**：单个组件 / 单轮问答准不准？检索召回够不够？答案忠实不忠实？
- **怎么做**：固定数据集（黄金集），自动跑，出指标。见 lesson02（RAGAS、单元测试、组件级）。
- **特点**：便宜、可重复、CI 友好、但离真实远。
- **适用**：每次改代码/提示词/模型都跑。

### 2.2 层②沙箱仿真评测（Sandbox Simulation）

- **回答的问题**：多步任务（Plan-Execute-Retrieval-Tool）连贯吗？遇到工具失败会自救吗？
- **怎么做**：在隔离沙箱里模拟用户、模拟工具（mock 外部 API），跑完整 Agent 链路。常用框架如 LangSmith Evaluations、自研仿真器。
- **特点**：可控、可注入故障、比离线更接近真实，但仍非真实流量。
- **适用**：上线前回归、复杂工作流验证。

```yaml
# 沙箱仿真配置示例
sandbox-eval:
  user-simulator: gpt-4o-mini      # 模拟用户提需求
  tool-mocks:                      # 把真实工具换成可控 mock
    - refund-api: { fail-rate: 0.2, latency: 300ms }
    - kb-search: { recall-noise: 0.3 }
  scenarios:                       # 多步任务剧本
    - name: 退款全流程
      steps: [查询订单, 调用退款, 通知用户]
  pass-criteria:
    task-success-rate: 0.9
    tool-fallback-rate: <0.1       # 工具失败时能否自救
```

### 2.3 层③在线生产评测（Online Production）

- **回答的问题**：真实用户满意吗？线上指标（采纳率、解决率、投诉率）如何？
- **怎么做**：线上采样 + 用户反馈（点赞/点踩、显式评分）+ 自动指标（如线上 RAGAS 抽样）。见 lesson04 在线遥测。
- **特点**：最真实，但慢、受噪声干扰、需 AB。
- **适用**：灰度期 + 常态化监控。

### 2.4 层④红蓝对抗（Red/Blue Teaming）

- **回答的问题**：有没有人（或自动化）能故意搞垮它？提示词注入、越狱、边界滥用。
- **怎么做**：红队（攻击方）用已知攻击手法批量试探，蓝队（防御方）看是否被突破。
- **特点**：最贵、最难自动化、但发现"长尾致命问题"。
- **适用**：安全要求高的场景（金融、政企）上线前必做。

<callout emoji="💡"><p>金字塔使用心法：①层是"每日必跑的体温计"，④层是"季度体检的核磁共振"。别指望只做离线就高枕无忧（离线通过不代表线上不翻车），也别一上来就搞红蓝对抗（成本高、没基线）。按金字塔从下往上建。</p></callout>

---

## 三、四层指标：性能 / 质量 / 效果 / 业务

光有金字塔还不够，得知道"看什么数"。我把 Agent 的指标分成四层，越往下越贴近商业价值：

```
层4 业务指标 (Business)    ── 商业价值：成本、营收、投诉率、NPS
层3 效果指标 (Effect)      ── 任务是否真的解决：解决率、采纳率、人工接管率
层2 质量指标 (Quality)     ── 输出好不好：忠实度、相关性、格式合规、安全
层1 性能指标 (Perf)        ── 跑得快不快：延迟、吞吐、成本、可用性
```

### 3.1 性能指标（Performance）——"跑得动吗"

| 指标 | 含义 | 关注点 |
|------|------|--------|
| 首 token 延迟 (TTFT) | 用户等多久看到第一个字 | 体验 |
| 端到端延迟 (E2E) | 一次完整回答耗时 | 体验 |
| 吞吐 (QPS) | 单位时间处理量 | 容量 |
| 单请求成本 | token 成本 + 调用成本 | 成本治理（第十章/十五章） |
| 可用性 | 成功率 / 5xx 率 | SLA |

```java
// ch16-evaluation/src/main/java/demo/metrics/PerfProbe.java
package demo.metrics;

import java.time.Duration;
import java.time.Instant;

/** 性能探针：记录一次 Agent 调用的延迟与成本 */
public class PerfProbe {
    public record PerfResult(Duration ttft, Duration e2e, long promptTokens,
                             long completionTokens, double costUsd) {}

    public static PerfResult measure(Runnable agentCall,
                                      long promptTokens, long completionTokens,
                                      double pricePer1k) {
        Instant t0 = Instant.now();
        agentCall.run();                       // 实际调用
        Instant t1 = Instant.now();
        double cost = (promptTokens + completionTokens) / 1000.0 * pricePer1k;
        return new PerfResult(Duration.ZERO, Duration.between(t0, t1),
                promptTokens, completionTokens, cost);
    }
}
```

### 3.2 质量指标（Quality）——"输出好不好"

| 指标 | 含义 | 典型方法 |
|------|------|----------|
| Faithfulness（忠实度） | 答案是否基于给定上下文、不编造 | RAGAS / LLM-as-Judge（lesson02/03） |
| Answer Relevancy | 答案是否切题 | RAGAS |
| 格式合规 | 是否按约定 JSON/Schema 输出 | 解析校验 |
| 安全/合规 | 有无违规输出 | 护栏/分类器（第十二章） |

### 3.3 效果指标（Effect）——"任务解决没"

| 指标 | 含义 |
|------|------|
| 任务解决率 (Task Success) | 多步任务是否走完 |
| 用户采纳率 (Adoption) | 用户是否采用建议 |
| 人工接管率 (Handoff) | 多少比例转人工 |
| 工具调用正确率 | 选对工具、参数对（lesson02） |

### 3.4 业务指标（Business）——"值不值"

| 指标 | 含义 |
|------|------|
| 单次服务成本 | 替代人工省了多少 |
| 客诉率 / 投诉下降 | 对业务 KPI 的贡献 |
| NPS / 满意度 | 用户体验 |
| 转化率 | 营销类 Agent 的商业产出 |

<callout emoji="⚠️"><p>最常见的陷阱：性能全绿、质量还行，但业务指标拉胯。比如一个客服 Agent 答得快、格式对，但"人工接管率 60%"——说明它没真解决问题，只是把用户敷衍走了。四层指标必须一起看，不能只看性能。面试官最爱问"你怎么定义 Agent 成功"，正确答案就是"四层都要有指标，且最终对齐业务"。</p></callout>

---

## 四、指标怎么选：按场景

不同 Agent 关注不同层：

```
客服问答 Agent：质量(Faithfulness) + 效果(解决率) + 业务(投诉率)
代码生成 Agent：质量(可编译/单测通过) + 效果(任务完成)
对账 Agent：质量(金额零幻觉) + 业务(差错率)
营销 Agent：效果(采纳) + 业务(转化/成本)
安全敏感 Agent：质量(安全合规) + 红蓝对抗(④层)
```

### 4.1 一个最小可运行的"四层指标采集"骨架

```java
// ch16-evaluation/src/main/java/demo/metrics/MetricCollector.java
package demo.metrics;

import java.util.EnumMap;
import java.util.Map;

/** 四层指标采集器：把一次运行的结果归并到四层 */
public class MetricCollector {

    public enum Layer { PERF, QUALITY, EFFECT, BUSINESS }

    private final Map<Layer, Map<String, Double>> store = new EnumMap<>(Layer.class);

    public void record(Layer layer, String name, double value) {
        store.computeIfAbsent(layer, k -> new java.util.HashMap<>()).put(name, value);
    }

    /** 导出给 Prometheus / 看板 */
    public Map<Layer, Map<String, Double>> snapshot() {
        return store;
    }

    // 示例：一次评测后
    // record(PERF, "e2e_ms", 1200);
    // record(QUALITY, "faithfulness", 0.92);
    // record(EFFECT, "task_success", 0.88);
    // record(BUSINESS, "handoff_rate", 0.12);
}
```

---

## 五、评测金字塔 × 四层指标 的映射

把两件事叠起来看，才是完整的评测视图：

```
            性能      质量      效果      业务
离线①       ✓        ✓        △(单轮)   ✗
沙箱②       ✓        ✓        ✓         △
在线③       ✓        ✓        ✓         ✓
红蓝④       △        ✓(安全)   ✗         ✗
（✓完整 △部分 ✗不直接）
```

<callout emoji="✅"><p>一张表记住：①层管"准不准"（质量+性能），②层管"稳不稳"（效果+质量），③层管"值不值"（业务），④层管"崩不崩"（安全）。四层指标在金字塔各层有不同覆盖度，组合起来才是全景。</p></callout>

---

## 六、企业落地场景：对账 Agent 的评测方案

某金融对账 Agent 的真实评测设计：

```
离线①：1000 条历史对账黄金集，跑 Faithfulness + 金额零幻觉 + 工具选择正确率
沙箱②：模拟"银行接口超时""返回脏数据"，看 Agent 能否重试/降级
在线③：灰度 5% 流量，监控"差错率""人工复核率""单笔成本"
红蓝④：红队尝试"注入假流水指令""诱导绕过校验规则"

决策门槛：
  • 离线 Faithfulness < 0.95 或 金额幻觉 > 0 → 禁止上线
  • 在线差错率 > 0.1% → 自动回滚（接 lesson04 闭环）
  • 红蓝突破核心校验 → 整改后才上线
```

**为什么这样设计？** 对账场景"错一笔就是钱"。质量（零幻觉）是生命线，所以离线门槛卡死；健壮性（沙箱故障注入）保证生产不崩；红蓝保证不被恶意绕过。四层指标全部对齐"差错率"这个业务命门。

---

## 七、常见反模式

1. **只做离线、不看线上**：离线 Faithfulness 0.95，线上用户却大量点踩——真实分布和黄金集有偏。
2. **只看性能、不看质量**：答得快但瞎编，业务指标崩。
3. **没有基线**：改了模型不知道变好变差，纯靠感觉。
4. **红蓝走过场**：只在 Demo 里试几个明显攻击，长尾攻击漏掉。
5. **指标不告警**：采了指标但没阈值、没告警，等于没采。

<callout emoji="💡"><p>评测体系最小可行（MVP）：① 一个 50 条的黄金集 + RAGAS 跑通（lesson02）；② 上线基础性能+质量监控（lesson04）；③ 一个 CI 卡点（lesson04）。先有这三样，再逐步加沙箱和红蓝。别一上来追求完美，但要"先有再优"。</p></callout>

---

## 八、评测的代价与 ROI（为什么值得投入）

很多团队犹豫"评测看起来很贵，要不要做"。我们用一笔账说清楚——**不评测的隐性成本远高于评测投入**。

### 8.1 成本结构对比

```
不评测的隐性成本（生产后爆发）：
  • 一次线上幻觉/错误 → 客诉 + 合规风险 + 舆情
  • 改一次模型/提示词靠运气 → 反复回滚，人力浪费
  • 无法量化效果 → 业务方不信任，项目被砍

评测投入（持续但可控）：
  • 离线：黄金集建设 1 人周 + 每次 CI 几分钱
  • 在线：采样裁判 5% 流量，成本线性可控
  • 红蓝：季度一次性，按需
```

### 8.2 ROI 估算（经验值）

```
评测 ROI ≈ (避免的线上事故成本 + 迭代提速收益) / 评测建设维护成本
  • 金融/政企：一次合规事故损失 >> 全年评测投入 → ROI 极高
  • 客服：人工接管率降 5% 省的人力 >> 评测成本 → ROI 高
  • 内部工具类：事故影响小 → ROI 中等，做轻量评测即可
```

```java
// ch16-evaluation/src/main/java/demo/metrics/EvalRoi.java
package demo.metrics;

/** 评测 ROI 粗算：用可避免损失对比评测成本 */
public class EvalRoi {
    public record Roi(double avoidedLoss, double evalCost, double roi) {}

    public static Roi estimate(double incidentCost, double incidentAvoidedRate,
                                double evalCost) {
        double avoided = incidentCost * incidentAvoidedRate;
        double roi = (avoided - evalCost) / evalCost;
        return new Roi(avoided, evalCost, roi);
    }
    // 例：单次事故均损 50万，评测规避 60%，年评测投入 20万
    //   avoided=30万, roi=(30-20)/20=0.5 → 投 1 块赚 1.5 块
}
```

<callout emoji="💡"><p>向老板申请评测资源时，别讲"技术先进"，讲 ROI：用"避免的事故损失"对比"评测投入"。金融/政企场景这个数字极其好看，一算就批。这是把技术语言翻译成商业语言的能力，架构师必备。</p></callout>

---

## 九、如何向业务方汇报评测结果

评测数据若只躺在仪表盘上，价值为零。你要能把它讲成业务方听得懂的"信心"。

### 9.1 汇报三原则

1. **对齐业务指标**：别只报"Faithfulness 0.92"，要报"回答可信率 92%，对应客诉风险下降 X%"。
2. **给趋势不给绝对值**："本月 0.88 → 本月 0.92"比"0.92"更有信息量。
3. **标红风险**：明确说"哪类问题仍不达标、正在修复"，建立信任。

### 9.2 一份汇报模板（YAML）

```yaml
eval-report-monthly:
  headline: "客服Agent回答可信率92%，较上月+4pt，客诉风险下降"
  layers:                      # 四层指标（lesson 三）
    perf: { e2e_p95: "1.2s", cost_per_call: "$0.003" }
    quality: { faithfulness: 0.92, answer_relevancy: 0.95 }
    effect: { task_success: 0.88, handoff_rate: 0.12 }
    business: { complaint_rate: -18%, nps: +6 }
  risks:
    - "退货政策类 Recall 偏低(0.75)，已排期优化检索"
  actions:
    - "优化退货政策切分 → 预计 Recall 提升至 0.85"
    - "红蓝对抗发现1个越狱路径 → 已加固护栏"
```

<callout emoji="✅"><p>评测的终点不是"出分"，是"驱动决策"。能写一份业务方看得懂、能签字的评测月报，你的评测体系才算真正闭环——也从"工程师"升级成"能对齐业务的架构师"。</p></callout>

---

## 十、面试自测

1. **评测金字塔四层？** —— 离线基准 → 沙箱仿真 → 在线生产 → 红蓝对抗，成本递增、保真度递增。
2. **四层指标？** —— 性能(跑得动)/质量(输出好)/效果(解决没)/业务(值不值)，必须一起看。
3. **为什么不能只做离线？** —— 离线通过 ≠ 线上不翻车，真实分布有偏，需在线+红蓝补全。
4. **性能全绿但业务拉胯说明什么？** —— 没真解决问题（如高接管率），只看性能是陷阱。

---

## 十一、小结

这节课搭建了评测的总纲：

1. **为什么必须评测**：LLM 输出不可控，不评测=把不确定性债务藏到上线后爆发；可评测才有基线与回归护栏，才能迭代。
2. **评测金字塔**：离线基准（准不准）→ 沙箱仿真（稳不稳）→ 在线生产（值不值）→ 红蓝对抗（崩不崩），从下往上建、各层解决不同问题。
3. **四层指标**：性能 / 质量 / 效果 / 业务，越往下越贴商业价值，必须组合看，不能只看性能。
4. **落地**：按场景选指标重点，对账类卡死质量门槛、客户类看解决率、安全类必做红蓝。

下一节（lesson02）我们下钻到金字塔最底层——离线评测：RAGAS、单元测试、组件级评测，给你能跑的代码。

---

## 延伸阅读 / 引用文档

1. RAGAS 官方文档（离线评测标准）：https://docs.ragas.io/
2. Anthropic 评测工程实践（含红蓝对抗思路）：https://www.anthropic.com/engineering/evaluating-agentic-systems
3. LangSmith Evaluations（沙箱/在线评测平台）：https://docs.smith.langchain.com/evaluation
4. EvalScope 官方文档（国产评测框架，含 LLM-as-Judge）：https://evalscope.readthedocs.io/
5. Google  Agents 评测白皮书（Vertex AI）：https://cloud.google.com/vertex-ai/generative-ai/docs/agent-builder
6. OpenAI 评测指南（Evals）：https://platform.openai.com/docs/guides/evals

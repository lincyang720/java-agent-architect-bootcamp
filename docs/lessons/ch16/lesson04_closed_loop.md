# 第十六章第4节：评测-归因-优化-回归闭环与生产监控

大家好，我是秋天。

前三节我们讲了"怎么评"（金字塔/指标）、"离线怎么测"（RAGAS/组件）、"裁判怎么可信"（校准）。这节课收口——**把评测变成一条会自我驱动的闭环**，并把它接进生产监控。否则评测只是"上线前的一次性体检"，上线后照样烂。

这节课讲什么、学完掌握什么：

- 讲什么：**评测 → 归因 → 优化 → 回归** 的闭环怎么转；**badcase 回流**机制；**黄金集**的持续演进；**CI/CD 卡点**怎么设；**在线遥测**怎么监控。
- 学完掌握：你能画出一个 Agent 质量闭环架构；你能写出一个 badcase 自动回流的 Java 服务；你知道在线指标怎么告警、怎么和生产可观测性（第十章）接上。

<callout emoji="💡"><p>招聘映射：JD 原话"能搭建评测-优化闭环""有 badcase 回流与黄金集管理经验""能把评测接进 CI/CD 卡点与生产监控"。这是评测体系落地的"最后一公里"，也是架构师面试里最能拉开差距的一节。</p></callout>

---

## 一、为什么需要闭环（不是一次性评测）

一次性评测的问题：上线后模型/知识/用户分布都在变，今天 0.92 的 Faithfulness，三个月后可能 0.7。没有闭环，评测的价值随时间衰减到零。

闭环的核心思想：**每一次线上 badcase、每一次优化，都回流成新的评测样本，驱动下一轮评测与回归。** 质量不是"测出来的"，是"转出来的"。

```
一次性评测：  评测 ──► 上线 ──► (烂了才发现)
闭环：       评测 ─► 上线 ─► 监控 ─► badcase ─► 归因 ─► 优化 ─► 回归评测 ─┐
                                                                         │
                                                                         └─ 回到上线
```

---

## 二、闭环四步：评测 → 归因 → 优化 → 回归

### 2.1 评测（Measure）

用前三节的所有手段：离线 RAGAS/组件、在线采样、红蓝对抗。关键是**每次都有基线数字**。

### 2.2 归因（Attribute）—— 闭环的灵魂

badcase 发生了，必须能回答"是哪个环节烂了"：是检索没召回？生成幻觉？工具选错？还是上下文腐烂（十五章 lesson03）？

```java
// ch16-evaluation/src/main/java/demo/loop/Attributor.java
package demo.loop;

/** badcase 归因：定位失败发生在哪一层 */
public class Attributor {

    public enum FailureLayer { RETRIEVAL, GENERATION, TOOL, CONTEXT, POLICY }

    public record Attribution(FailureLayer layer, String reason, String evidence) {}

    /**
     * 简单归因逻辑（生产里接可观测性追踪，见第十/十五章血缘）
     */
    public static Attribution attribute(BadCase bc) {
        if (bc.retrievedContexts().isEmpty() || bc.contextRecall() < 0.5)
            return new Attribution(FailureLayer.RETRIEVAL,
                    "检索未召回关键文档", bc.evidence());
        if (bc.faithfulness() < 0.9)
            return new Attribution(FailureLayer.GENERATION,
                    "生成出现幻觉/不忠实", bc.evidence());
        if (!bc.toolCorrect())
            return new Attribution(FailureLayer.TOOL,
                    "工具选择/参数错误", bc.evidence());
        if (bc.rotRisk())
            return new Attribution(FailureLayer.CONTEXT,
                    "上下文腐烂/超窗口", bc.evidence());
        return new Attribution(FailureLayer.POLICY, "策略/合规越界", bc.evidence());
    }

    public record BadCase(String id, java.util.List<String> retrievedContexts,
                          double contextRecall, double faithfulness,
                          boolean toolCorrect, boolean rotRisk, String evidence) {}
}
```

### 2.3 优化（Optimize）

按归因结果改对应环节：

```
归因 → 优化动作映射：
  RETRIEVAL  → 调切分/embedding/召回数/重排（第七章）
  GENERATION → 改提示词/加引用约束/换模型（第三/十五章）
  TOOL       → 改工具描述/路由逻辑（第四/九章）
  CONTEXT    → 调 Select/Compress/缓存（十五章）
  POLICY     → 改策略上下文/护栏（十二章）
```

<callout emoji="⚠️"><p>归因错 = 优化错。很多团队 badcase 一来就改提示词，但如果根因是检索没召回，改提示词毫无作用。闭环的价值 80% 在归因这一步——必须接血缘/追踪（十五章 lesson04 + 第十章可观测性），而不是拍脑袋。</p></callout>

### 2.4 回归（Regression Guard）

优化后必须**重跑全量黄金集 + 关键 badcase**，确认：① 修好了这个目标；② 没搞坏别的地方。

```java
// ch16-evaluation/src/main/java/demo/loop/RegressionGuard.java
package demo.loop;

import java.util.List;

/** 回归护栏：优化后比对新旧基线，防止改 A 坏 B */
public class RegressionGuard {

    public record RegressionReport(boolean passed,
                                    double newScore, double oldScore,
                                    double delta) {}

    public static RegressionReport guard(String metric,
                                          double newScore, double oldScore,
                                          double minDelta) {
        double delta = newScore - oldScore;
        boolean passed = delta >= minDelta;   // 不允许显著下降
        return new RegressionReport(passed, newScore, oldScore, delta);
    }

    // CI 用法：若 faithfulness 从 0.92 降到 0.88（delta=-0.04 < 0）
    //          则阻断合并，防止回归
    public static boolean blockIfRegressed(List<RegressionReport> reports) {
        return reports.stream().anyMatch(r -> !r.passed());
    }
}
```

---

## 三、badcase 回流机制

### 3.1 什么是 badcase 回流

把生产中的失败案例（用户点踩、人工复核标记、自动检测到的低分回答）**自动收集、标注、入库**，变成下一轮评测的黄金样本。这是黄金集"越滚越大、越贴近真实"的关键。

```
badcase 来源：
  • 用户显式反馈（点踩/差评/转人工）
  • 在线评测低分（Faithfulness < 0.9 自动标）
  • 人工抽检标记
  • 红蓝对抗命中
        │
        ▼ 回流管线
  ┌─────────────────────────────────────┐
  │ 1. 采集 → 2. 去重 → 3. 归因标注      │
  │ 4. 入黄金集（版本化）→ 5. 触发回归   │
  └─────────────────────────────────────┘
```

### 3.2 Java：badcase 自动回流服务

```java
// ch16-evaluation/src/main/java/demo/loop/BadCaseSink.java
package demo.loop;

import demo.loop.Attributor.Attribution;
import demo.loop.Attributor.BadCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

/** badcase 回流：采集 → 归因 → 写入黄金集（JSONL，版本化） */
public class BadCaseSink {

    private final Path goldenDir;
    private final Attributor attributor;

    public BadCaseSink(Path goldenDir, Attributor attributor) {
        this.goldenDir = goldenDir;
        this.attributor = attributor;
    }

    /** 处理一条线上 badcase：归因后追加进当日黄金集文件 */
    public void ingest(BadCase bc) {
        Attribution a = Attributor.attribute(bc);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", bc.id());
        row.put("question", bc.evidence());
        row.put("failure_layer", a.layer().name());   // 归因标签，便于分桶评测
        row.put("reason", a.reason());
        row.put("date", LocalDate.now().toString());

        // 按失败层分桶，便于"修复后针对性回归"
        Path target = goldenDir.resolve(a.layer().name().toLowerCase() + "_golden.jsonl");
        try {
            Files.writeString(target, toJson(row) + "\n",
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String toJson(Map<String, Object> m) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(m);
    }
}
```

<callout emoji="✅"><p>分桶回流是精髓：把 badcase 按"失败层"分桶（检索/生成/工具/上下文/策略），修复某个环节后，只用对应桶做回归，又快又准。这比"每次跑全量"高效得多，也是评测工程化的体现。</p></callout>

---

## 四、黄金集（Golden Set）的持续演进

黄金集不是写一次就完。它的生命周期：

```
golden-v1 (500条) ── 上线后回流 50 badcase ──► golden-v2 (550条)
                                          ├── 其中 30 条修好 → 留作回归
                                          └── 20 条仍 fail → 继续跟踪
   每次版本变更（提示词/模型/知识）都基于最新 golden 跑，保证可比
```

```yaml
# 黄金集版本管理
golden-set:
  current-version: v2
  size: 550
  buckets:
    retrieval: 180
    generation: 150
    tool: 90
    context: 80
    policy: 50
  evolve:
    on-badcase: append-to-bucket
    monthly-review: human-verify-new-entries
    regression: per-bucket-on-fix
```

<callout emoji="⚠️"><p>黄金集的坑：只增不减会膨胀到跑不动。要定期"毕业"——已稳定通过的样本降权或移出主集，只保留边界/对抗样本作核心集。否则 CI 越跑越慢，团队就不跑了。黄金集要"精"不要"肿"。</p></callout>

---

## 五、CI/CD 卡点：把评测焊死在合并门禁

### 5.1 为什么必须卡点

评测不接 CI，等于没评测——人会忘、会懒、会赶工期跳过。把评测变成"合并必过"的硬卡点，质量才有保障。

### 5.2 CI 流程设计

```yaml
# .github/workflows/agent-eval.yml  或 GitLab CI
name: agent-eval-gate
on: [pull_request]

jobs:
  offline-eval:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: 跑 Agent 导出评测样本
        run: mvn -q test -Dtest=RagEvalExportTest
      - name: RAGAS 算分
        run: python ragas_score.py eval.jsonl
      - name: 读指标卡点
        run: |
          FAITH=$(python read_metric.py faithfulness)
          RECALL=$(python read_metric.py context_recall)
          if (( $(echo "$FAITH < 0.9" | bc -l) )); then
            echo "Faithfulness $FAITH < 0.9 阻断合并"; exit 1
          fi
          if (( $(echo "$RECALL < 0.8" | bc -l) )); then
            echo "ContextRecall $RECALL < 0.8 阻断合并"; exit 1
          fi
      - name: 跑回归护栏
        run: mvn -q test -Dtest=RegressionGuardTest
```

### 5.3 Java：CI 卡点断言测试

```java
// ch16-evaluation/src/test/java/demo/loop/CiGateTest.java
package demo.loop;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

/** CI 卡点：读 RAGAS 产出的指标文件，不达标就 fail（阻断合并） */
class CiGateTest {

    @Test
    void faithfulness_must_be_above_threshold() throws Exception {
        double faith = readMetric("build/eval/faithfulness.txt");
        assertThat(faith).isGreaterThanOrEqualTo(0.90);  // 金融零幻觉红线
    }

    @Test
    void context_recall_must_be_above_threshold() throws Exception {
        double recall = readMetric("build/eval/context_recall.txt");
        assertThat(recall).isGreaterThanOrEqualTo(0.80);
    }

    private double readMetric(String path) throws Exception {
        return Double.parseDouble(Files.readString(Path.of(path)).trim());
    }
}
```

<callout emoji="💡"><p>卡点设几条有讲究：太多（faithfulness≥0.99）永远过不了、团队会绕过；太少（≥0.5）等于没卡。建议"核心红线卡死 + 其余趋势监控"。金融类 faithfulness 卡 0.9 合理，营销类可放松到 0.8。卡点要"疼但不致命"。</p></callout>

---

## 六、在线遥测与生产监控

### 6.1 在线指标采集

离线管"准不准"，在线管"真实用户满不满意"。把第十五章 lesson03 的上下文监控和第十六章 lesson01 的四层指标接起来：

```yaml
# 在线监控配置
online-monitoring:
  metrics:
    - name: faithfulness_online       # 在线采样 LLM 裁判打分
      sample-rate: 0.05
      alert-below: 0.88
    - name: user_thumb_down_rate       # 用户点踩率
      alert-above: 0.05
    - name: human_handoff_rate         # 转人工率（效果指标）
      alert-above: 0.20
    - name: context_utilization        # 十五章 lesson03 利用率
      alert-below: 0.5
    - name: p95_latency                # 性能指标
      alert-above: 3000ms
  dashboards: grafana
  tracing: opentelemetry               # 接第十章可观测性
```

### 6.2 Java：在线指标埋点（OpenTelemetry 风格）

```java
// ch16-evaluation/src/main/java/demo/loop/OnlineMetrics.java
package demo.loop;

import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.DoubleHistogram;

/** 在线评测指标埋点：每条回答记录质量与反馈 */
public class OnlineMetrics {

    private final LongCounter thumbDown;
    private final LongCounter handoff;
    private final DoubleHistogram faithfulness;

    public OnlineMetrics(Meter meter) {
        this.thumbDown = meter.counterBuilder("user_thumb_down").build();
        this.handoff = meter.counterBuilder("human_handoff").build();
        this.faithfulness = meter.histogramBuilder("faithfulness_online").build();
    }

    public void recordAnswer(double faithScore, boolean userDown, boolean toHuman) {
        faithfulness.record(faithScore);
        if (userDown) thumbDown.add(1);
        if (toHuman) handoff.add(1);
        // 这些指标进 Prometheus → Grafana 告警（见第十章）
    }
}
```

### 6.3 告警 → 触发闭环

```
在线告警（如 faithfulness_online < 0.88）
   │
   ▼ 自动采集近期低分样本
   ▼ 触发 badcase 回流（3.2 的 BadCaseSink）
   ▼ 归因（2.2）→ 定位失败层
   ▼ 通知对应负责人优化
   ▼ 优化后跑回归（2.4）+ CI 卡点（5.2）
   ▼ 灰度发布，监控恢复 → 闭环完成
```

<callout emoji="✅"><p>闭环的"自动化程度"决定它的生命力。最高级形态是：告警自动采集 → 自动归因分桶 → 自动触发对应回归集 → 出对比报告给负责人。半自动（告警+人工归因）也比"全靠人想起才评"强十倍。</p></callout>

---

## 七、完整闭环架构图

把整章 + 第十五章血缘串起来：

```
┌──────────────────────────────────────────────────────────────────┐
│                       Agent 质量闭环                                │
│                                                                    │
│   ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐   │
│   │ ① 评测   │───►│ ② 归因   │───►│ ③ 优化   │───►│ ④ 回归   │   │
│   │ Measure  │    │ Attribute │    │ Optimize │    │ Regression│   │
│   └────┬─────┘    └────┬─────┘    └────┬─────┘    └────┬─────┘   │
│        │               │               │               │        │
│   离线RAGAS/组件     血缘追踪        改提示词/检索    黄金集回归   │
│   在线采样/红蓝      (十五章L4)      (上下文工程)     CI卡点     │
│        │               │               │               │        │
│        └───────────────┴───────┬───────┴───────────────┘        │
│                                ▼                                 │
│                    ┌──────────────────┐                          │
│                    │  badcase 回流     │◄── 在线告警(⑥)          │
│                    │  → 黄金集演进(④) │                          │
│                    └──────────────────┘                          │
│  横切：可观测性(OTel/Prometheus) + 血缘(十五章) + CI 卡点        │
└──────────────────────────────────────────────────────────────────┘
```

---

## 八、企业落地：客服 Agent 的闭环生产

某电商客服 Agent 闭环落地：

```
离线（CI 卡点）：
  • RAGAS Faithfulness ≥ 0.9，ContextRecall ≥ 0.8，否则阻断合并
  • 黄金集 v3（620条，5 个失败层分桶）

在线（生产监控）：
  • 5% 流量采样 LLM 裁判打分（异源双裁判，接 lesson03）
  • 用户点踩率 > 5% 告警，转人工率 > 20% 告警

闭环实例：
  • 某日点踩率升到 8% → 自动采集低分样本 40 条
  • 归因：RETRIEVAL 层（新上架商品知识未进索引）
  • 优化：修复知识库同步，重跑 retrieval 桶回归（全过）
  • 灰度发布，点踩率回到 3% → 闭环完成
  • 这 40 条中 12 条稳定 fail 的留下作黄金集 v4（632条）

效果：
  • 线上问题从"人工发现、平均 3 天修复"→ "自动告警、平均 6 小时修复"
  • 黄金集从 v1 500 条滚到 v4 632 条，越测越准
```

**为什么这样设计？** 客服场景"坏体验即流失"。闭环把"被动救火"变"主动自愈"：告警自动采集→归因→优化→回归→灰度，全程数据驱动。badcase 回流让评测集始终贴近真实分布，不会"离线满分、线上翻车"。

<callout emoji="💡"><p>这一节是全课程"工程化"的集大成：它把第十五章（上下文工程、血缘）、第十章（可观测性）、第十一/十二章（协议/治理）全部咬合成一个"质量自驱动系统"。能设计这样闭环的人，不是"写 Agent 的"，是"让 Agent 持续可靠的架构师"。</p></callout>

---

## 九、常见反模式

| 反模式 | 后果 | 解法 |
|--------|------|------|
| 评测不接 CI | 忘跑、绕过，质量失控 | 合并门禁硬卡 |
| 只跑离线不看在线 | 线上翻车才发现 | 在线采样+告警 |
| badcase 不回流 | 黄金集 stale，偏离真实 | 自动回流分桶 |
| 归因靠拍脑袋 | 改错环节，白费劲 | 接血缘/追踪 |
| 回归只跑全量 | 慢，团队放弃 | 按失败层分桶回归 |
| 黄金集只增不减 | CI 越来越慢 | 定期毕业稳定样本 |

---

## 十、面试自测

1. **闭环四步？** —— 评测→归因→优化→回归，核心是 badcase 回流驱动演进。
2. **归因为什么关键？** —— 归因错=优化错，必须接血缘/追踪而非拍脑袋。
3. **CI 卡点怎么设？** —— 核心红线卡死（faithfulness≥0.9）+ 其余趋势监控，疼但不致命。
4. **在线怎么接？** —— OTel 埋点 + Prometheus/Grafana 告警 → 自动回流 → 回归。

---

## 十一、小结

这节课把评测收口成自我驱动的闭环，并与全课程咬合：

1. **闭环四步**：评测（基线）→ 归因（定位失败层，接血缘）→ 优化（改对应环节）→ 回归（防改 A 坏 B）。归因是灵魂。
2. **badcase 回流**：采集→去重→归因分桶→入黄金集→触发回归，让评测集越滚越贴近真实。
3. **黄金集演进**：版本化、按失败层分桶、定期"毕业"稳定样本防膨胀。
4. **CI/CD 卡点**：离线指标焊死在合并门禁，核心红线卡死、其余趋势监控。
5. **在线遥测**：OTel 埋点 + 采样裁判打分 + 告警，自动触发回流闭环。

至此第十六章（Agent 评测与质量闭环）四节全部讲完。上下文工程（十五章）+ 评测闭环（十六章）是 2025–26 招聘两大头号技能，务必联合掌握、落地到你能拿出代码的程度。

---

## 延伸阅读 / 引用文档

1. LangSmith 评测与监控（在线遥测+闭环）：https://docs.smith.langchain.com/observability
2. EvalScope 闭环与回归（国产框架）：https://evalscope.readthedocs.io/
3. RAGAS 评测（离线指标）：https://docs.ragas.io/
4. OpenTelemetry LLM 可观测性（在线埋点）：https://opentelemetry.io/docs/specs/otel/metrics/
5. Prometheus + Grafana 告警（生产监控）：https://prometheus.io/docs/alerting/latest/overview/
6. Anthropic 评测 Agentic 系统（含闭环思想）：https://www.anthropic.com/engineering/evaluating-agentic-systems

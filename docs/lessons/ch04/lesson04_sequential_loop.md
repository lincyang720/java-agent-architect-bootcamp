# 第四章第4节：SequentialAgent 与 LoopAgent：顺序执行与循环执行的实现

大家好，我是秋天。

上一节讲了中心化的 Supervisor。这一节我们讲两个更"轻量"但高频的模式：**SequentialAgent（顺序执行）**和 **LoopAgent（循环执行）**。它们没有 Supervisor 那么重的"调度大脑"，而是用最朴素的"线性流"和"带退出条件的反复流"解决大量真实业务。很多同学一上来就想搞复杂编排，结果 bug 一堆——其实大部分企业流程就是"先 A 后 B 再 C"或者"反复试直到通过"，这两个模式就够了。

这节课讲什么、学完掌握什么：

- 讲什么：SequentialAgent 的线性流水线语义、用 Graph 的 `addEdge` 串联节点实现；LoopAgent 的"循环 + 退出条件"语义、用条件边指回自身 + 计数器实现；两者与 lesson01 分发策略 2（流水线）的对应。
- 学完掌握：你能用 Graph 写出一个"检索→分析→生成→校验"的 Sequential 流水线，和一个"生成→自审→不达标就重写"的 Loop（带最大重试上限）；你理解什么时候用顺序、什么时候用循环。

<callout emoji="💡"><p>招聘映射：JD 写"熟悉顺序/循环编排""Plan-and-Execute""ReAct 循环"。Sequential 是流水线，Loop 是 ReAct 的骨架。这两个模式是面试"多 Agent 编排"题的默认起手式。</p></callout>

---

## 一、SequentialAgent：线性流水线

Sequential = 节点按固定顺序排成一条线，上一个的输出是下一个的输入。对应 lesson01 的"策略 2：流水线分发"。

```
输入 ─▶ [节点A] ─▶ [节点B] ─▶ [节点C] ─▶ 输出
        (产出x)    (消费x产y)  (消费y产z)
```

用 Graph 实现最简单——一串 `addEdge` 即可：

```java
package com.example.ch04.seq;

import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.GraphStateException;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class SequentialGraph {

    // A：检索知识
    static class RetrieveNode implements NodeAction {
        @Override
        public Map<String, Object> apply(OverAllState s) {
            return Map.of("docs", "相关法条：……；案例：……");
        }
    }

    // B：分析（消费 docs）
    static class AnalyzeNode implements NodeAction {
        private final ChatClient chatClient;
        public AnalyzeNode(ChatClient c) { this.chatClient = c; }
        @Override
        public Map<String, Object> apply(OverAllState s) {
            String docs = s.value("docs", "").toString();
            String analysis = chatClient.prompt()
                    .system("你是基于检索内容做分析的律师助理。")
                    .user("根据以下资料分析风险：\n" + docs)
                    .call().content();
            return Map.of("analysis", analysis);
        }
    }

    // C：生成报告（消费 analysis）
    static class ReportNode implements NodeAction {
        private final ChatClient chatClient;
        public ReportNode(ChatClient c) { this.chatClient = c; }
        @Override
        public Map<String, Object> apply(OverAllState s) {
            String analysis = s.value("analysis", "").toString();
            String report = chatClient.prompt()
                    .system("把分析整理成正式报告。").user(analysis)
                    .call().content();
            return Map.of("report", report);
        }
    }

    public static CompiledGraph build(ChatClient chatClient) throws GraphStateException {
        var ksf = (com.alibaba.cloud.ai.graph.KeyStrategyFactory) () -> {
            java.util.Map<String, com.alibaba.cloud.ai.graph.KeyStrategy> m = new java.util.HashMap<>();
            m.put("docs", com.alibaba.cloud.ai.graph.Channel.<String>create().build());
            m.put("analysis", com.alibaba.cloud.ai.graph.Channel.<String>create().build());
            m.put("report", com.alibaba.cloud.ai.graph.Channel.<String>create().build());
            return m;
        };
        StateGraph g = new StateGraph("sequential", ksf);
        g.addNode("retrieve", com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async(new RetrieveNode()));
        g.addNode("analyze", com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async(new AnalyzeNode(chatClient)));
        g.addNode("report", com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async(new ReportNode(chatClient)));
        g.addEdge(StateGraph.START, "retrieve");
        g.addEdge("retrieve", "analyze");
        g.addEdge("analyze", "report");
        g.addEdge("report", StateGraph.END);
        return g.compile();
    }
}
```

这就是最经典的 RAG 流水线：检索 → 分析 → 报告。注意状态 key（`docs`/`analysis`/`report`）在节点间像接力棒一样传递。Sequential 的本质就是"状态接力"。

<callout emoji="✅"><p>工程经验：Sequential 适合"步骤固定、无分支"的流程。它的最大优点是**可预测、易回滚**——哪一步挂了，从那一步重跑即可（配合 threadId 状态隔离）。别一上来就上 Supervisor，能用顺序解决的绝不引入调度复杂度。</p></callout>

---

## 二、LoopAgent：循环执行直到满足退出条件

Loop = 一个节点反复执行，直到某条件满足才退出。典型场景："生成文案 → 自审 → 不达标就重写"，或者"反复试探 API 直到成功"。

Graph 里用**条件边指回自身**实现循环，关键是"退出条件 + 上限保护"。

```java
package com.example.ch04.loop;

import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.alibaba.cloud.ai.graph.GraphStateException;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class LoopGraph {

    static final int MAX_TRIES = 3;

    // 生成节点：写 draft，并把尝试次数 +1
    static class GenerateNode implements NodeAction {
        private final ChatClient chatClient;
        public GenerateNode(ChatClient c) { this.chatClient = c; }
        @Override
        public Map<String, Object> apply(OverAllState s) {
            int tries = (Integer) s.value("tries", 0);
            String draft = chatClient.prompt()
                    .system("写一句产品宣传语，第" + (tries+1) + "次尝试。")
                    .user("产品：智能水杯").call().content();
            return Map.of("draft", draft, "tries", tries + 1);
        }
    }

    // 审核节点：判断是否达标，写入 check
    static class ReviewNode implements NodeAction {
        private final ChatClient chatClient;
        public ReviewNode(ChatClient c) { this.chatClient = c; }
        @Override
        public Map<String, Object> apply(OverAllState s) {
            String draft = s.value("draft", "").toString();
            String verdict = chatClient.prompt()
                    .system("若宣传语含'智能''健康'则输出 PASS，否则 FAIL。只输出一词。")
                    .user(draft).call().content().trim();
            return Map.of("check", "PASS".equals(verdict));
        }
    }

    // 路由边：达标或超上限 → END；否则 → 回到 generate（循环）
    static class LoopRouter implements EdgeAction {
        @Override
        public String apply(OverAllState s) {
            boolean ok = (Boolean) s.value("check", false);
            int tries = (Integer) s.value("tries", 0);
            if (ok || tries >= MAX_TRIES) return "exit";
            return "retry";
        }
    }

    public static CompiledGraph build(ChatClient chatClient) throws GraphStateException {
        var ksf = (com.alibaba.cloud.ai.graph.KeyStrategyFactory) () -> {
            java.util.Map<String, com.alibaba.cloud.ai.graph.KeyStrategy> m = new java.util.HashMap<>();
            m.put("draft", com.alibaba.cloud.ai.graph.Channel.<String>create().build());
            m.put("check", com.alibaba.cloud.ai.graph.Channel.<Boolean>create().build());
            m.put("tries", com.alibaba.cloud.ai.graph.Channel.<Integer>create().build());
            return m;
        };
        StateGraph g = new StateGraph("loop", ksf);
        g.addNode("generate", com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async(new GenerateNode(chatClient)));
        g.addNode("review", com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async(new ReviewNode(chatClient)));
        g.addEdge(StateGraph.START, "generate");
        g.addEdge("generate", "review");
        g.addConditionalEdges("review",
                com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async(new LoopRouter()),
                Map.of("retry", "generate", "exit", StateGraph.END));
        return g.compile();
    }
}
```

执行流：`generate → review →(不达标)→ generate → review → ... →(达标或第3次)→ END`。`LoopRouter` 同时检查 `check` 和 `tries`，任一满足就退出。**这就是 ReAct 循环（lesson06）和 Plan-and-Execute 自迭代的底层机制**。

<callout emoji="⚠️"><p>Loop 没有上限 = 生产事故。必须同时检查"业务达标"和"尝试次数 >= 上限"，后者是兜底安全阀，防止模型永远不达标导致无限循环烧钱。每次 Loop 都是真金白银的 token 消耗。</p></callout>

---

## 三、Sequential + Loop 组合：真实工作流

生产里两者常组合：外层 Sequential 串大阶段，内层某阶段用 Loop 自迭代。例如"合同审查"：

```
Sequential:
  [抽取条款] → [Loop: 逐条风险审查-不通过就补充分析] → [生成审查报告]
```

用 Graph 就是把 lesson01 的 Loop 子图（subgraph，lesson02 讲过）嵌进 Sequential 的某条边。这种"顺序框大流程、循环处理难点的混合"，是工程里最实用的形态。

---

## 四、为什么不用 Supervisor 而用这两个

| 场景 | 选谁 | 理由 |
|------|------|------|
| 步骤固定、无分支（RAG 流水线） | Sequential | 简单、可预测 |
| 反复试探/自审（生成-校验） | Loop | 天然循环语义 |
| 动态分发、按需调用多个专家 | Supervisor | 需要调度大脑 |
| 既要分流又要迭代 | Supervisor + 内含 Loop | 混合 |

经验法则：**能用 Sequential / Loop 解决的，绝不引入 Supervisor**。复杂度越低，bug 越少、越好运维。这是 Agent 工程化的核心权衡。

---

## 五、企业场景：对账报告的"生成-自审"循环

真实场景——月末对账报告生成：先由模型起草报告，再自审"数字是否和源数据一致、有无遗漏科目"，不一致就基于差异重写，最多重试 3 次。

```java
// 伪代码骨架（接 lesson05 的 ReconcileTools 取真实数据）
StateGraph g = new StateGraph("reconcileReport", ksf);
g.addNode("draft",   node_async(new DraftNode(chatClient, reconcileTools)));
g.addNode("selfCheck", node_async(new SelfCheckNode(chatClient, reconcileTools)));
g.addEdge(START, "draft");
g.addEdge("draft", "selfCheck");
g.addConditionalEdges("selfCheck", edge_async(new ReconcileLoopRouter()),
        Map.of("retry", "draft", "exit", END));  // 数字对不上就重起草
```

这个 Loop 保证了"报告里的金额一定和源系统对得上"——比人工写报告可靠，也比"一次生成不管对错"的朴素 Agent 可靠。这就是 Loop 在企业落地里最实在的价值：**用迭代换质量**。

<callout emoji="💡"><p>求职导向：能讲出"Loop 的核心是退出条件 + 上限保护，本质是 ReAct / 自迭代的底层机制"，说明你理解编排模式的共性，而不是背名词。面试官很喜欢这种"看穿模式本质"的回答。</p></callout>

---

## 七、并行变体：Sequential 的扇出汇总

Sequential 是串行的，但如果步骤间无依赖，可以并行。Graph 里用"一进多出"实现"扇出-汇总"：

```java
StateGraph g = new StateGraph("fanout", ksf);
g.addNode("split", node_async(new SplitNode()));        // 拆分任务
g.addNode("p1", node_async(new PartAgent("环节1")));
g.addNode("p2", node_async(new PartAgent("环节2")));
g.addNode("merge", node_async(new MergeNode()));        // 汇总
g.addEdge(START, "split");
g.addEdge("split", "p1");
g.addEdge("split", "p2");    // p1/p2 并行
g.addEdge("p1", "merge");
g.addEdge("p2", "merge");
g.addEdge("merge", END);
```

这其实是 lesson03 并行 Supervisor 的"无主管版"——没有中心调度，纯靠数据流并行。适合"把一个大文档拆几段同时处理"。

---

## 八、Loop 的退出条件设计模式

Loop 能不能干净退出，全看退出条件设计。三种常见模式：

```
模式A 达标即停：  check==PASS → END
模式B 上限即停：  tries>=MAX → END（兜底）
模式C 人工即停：  人点"停止" → END（HITL 结合）
```

最佳实践是用 **A+B 组合**：业务达标优先，达标则停；不达标但到上限也停（防烧钱）。C 是可选增强。

```java
static class LoopRouter implements com.alibaba.cloud.ai.graph.action.EdgeAction {
    @Override
    public String apply(OverAllState s) {
        boolean ok = (Boolean) s.value("check", false);
        int tries = (Integer) s.value("tries", 0);
        if (ok) return "exit";                 // 模式A
        if (tries >= MAX_TRIES) return "exit"; // 模式B 兜底
        return "retry";
    }
}
```

<callout emoji="⚠️"><p>只写模式A不写下限 = 生产事故。模型可能永远觉得"还差点意思"而无限循环。MAX_TRIES 是安全阀，必须存在。一般 3~5 次足够，再多就是模型能力边界，该转人工了。</p></callout>

---

## 九、Sequential + Loop 组合：合同审查子图

真实"合同审查"= 外层 Sequential（抽取→审查→报告）+ 内层 Loop（逐条风险审查不通过就补充分析）：

```java
// 伪代码骨架
StateGraph review = new StateGraph("contractReview", ksf);
review.addNode("extract", node_async(new ExtractNode()));      // Sequential 第1步
review.addNode("loopStart", node_async(new ReviewItemNode())); // Loop 起点
review.addNode("report", node_async(new ReportNode()));        // Sequential 末步
review.addEdge(START, "extract");
review.addEdge("extract", "loopStart");
review.addConditionalEdges("loopStart",
        edge_async(new ContractLoopRouter()),   // 还有未审条款→继续Loop；审完→report
        Map.of("next", "loopStart", "done", "report"));
review.addEdge("report", END);
```

外层保证"先抽后审再报"的顺序，内层 Loop 保证"每条款审到通过或上限"。组合拳解决复杂业务。

---

## 十、再梳理：Sequential / Loop / Supervisor 怎么选

给个更落地的决策：

| 你的流程 | 选 |
|---------|-----|
| 步骤固定、无分支 | Sequential |
| 反复试探/自审直到达标 | Loop |
| 动态分发、按需调专家 | Supervisor |
| 大步骤固定 + 局部自迭代 | Sequential 外层 + Loop 内层 |
| 多领域并行 + 汇总 | Supervisor 扇出（lesson03） |

记住铁律：**能用 Sequential/Loop 解决的，绝不引入 Supervisor**。复杂度越低越好运维。

---

## 十一、面试自测

1. **Sequential 本质？** —— 状态接力，节点用 addEdge 串成线。
2. **Loop 怎么实现？** —— 条件边指回自身 + 退出条件（达标/上限）+ 上限保护。
3. **能并行吗？** —— 无依赖步骤用扇出边并行，有依赖才串行。
4. **和 Supervisor 取舍？** —— 简单用顺/循环，动态分发才上 Supervisor。

---

## 十三、Loop 的监控指标

Loop Agent 上线后必须监控，否则"悄悄烧钱/死循环"发现不了。关键指标：

```
loop_iterations_avg   : 平均迭代次数（突增=可能陷入重复）
loop_iterations_max   : 最大迭代次数（触顶=常达上限，模型能力不够）
loop_token_cost       : 单次任务 token 消耗（突增=循环失控）
loop_timeout_rate     : 超时率
```

```java
// 概念：每次 Loop 结束记录迭代次数与成本
record LoopMetrics(int iterations, long tokens, String taskId) {}
// 上报到 Prometheus/Grafana，设告警：
//   loop_iterations_max > MAX_TRIES 持续 → 告警（模型能力边界）
//   loop_token_cost 环比 +200% → 告警（可能死循环）
```

<callout emoji="⚠️️<p>监控重点：`loop_iterations_max` 频繁触顶说明"模型在该任务上反复重试仍不达标"——不是调大上限能解决的，要改提示词/工具/换模型。盲目调大上限只会更烧钱。</p></callout>

---

## 十四、Sequential 的失败重试与跳过

Sequential 某节点失败怎么办？两种策略：

1. **重试**：节点内重试（lesson02 讲过），适合瞬时故障（超时/限流）。
2. **跳过/降级**：非关键节点失败可跳过，给默认值继续。如"生成图表"失败，报告仍可出文字版。

```java
// 概念：Sequential 中标记某节点可降级
g.addNode("chart", node_async(new ChartNode().withFallback("图表生成失败，见文字描述")));
g.addEdge("analyze", "chart");   // chart 失败不影响后续 report
```

关键节点（如"检索知识"）不能跳过，必须重试或整体失败转人工；非关键节点（如"美化格式"）可降级。按重要性分级处理。

---

## 十五、面试自测补充

1. **Loop 监控看什么？** —— 平均/最大迭代次数、token 成本、超时率；触顶说明模型能力边界。
2. **Sequential 节点失败？** —— 关键节点重试/转人工，非关键节点降级跳过。
3. **扇出并行前提？** —— 子任务无数据依赖；有依赖必须串行。
4. **和 Supervisor 取舍？** —— 简单用顺/循环，动态分发才上 Supervisor。

---

## 十七、Sequential 与事件驱动的结合

Sequential 不一定同步跑完，可拆成"事件驱动的步骤链"：每步完成发一个事件，下一步监听事件触发。好处是步骤可跨服务、可恢复。

```
[步骤A] 完成 ──事件──▶ [事件总线] ──▶ [步骤B] 触发
                                      │ 完成
                                      ▼
                                   ──事件──▶ [步骤C]
```

```java
// 概念：用 Spring 事件解耦 Sequential 步骤
@Component
class StepA {
    @EventListener
    void onStart(StartEvent e) {
        // 执行步骤A
        applicationEventPublisher.publishEvent(new StepADone(data));
    }
}
```

这把"顺序流"从"一个方法里的线性调用"升级为"跨组件的事件链"，更利于微服务化与故障隔离（对应第九章事件驱动架构）。

<callout emoji="💡"><p>思路：当顺序流跨多个团队/服务时，用事件解耦比硬串调用更稳。但事件链要防"事件丢失/重复"——用可靠消息队列（Kafka/RocketMQ）+ 幂等消费。这是顺序流走向生产的形态。</p></callout>

---

## 十八、Loop 的提前退出：质量达标即停

lesson04 讲了退出条件，补充一个实践：Loop 里"质量达标"的判断最好用"可量化指标"而非模型主观判断，更稳。

```
不推荐：模型自己说"我觉得差不多了" → 易过早停/过晚停
推荐：用规则/校验判断
  - 生成代码能通过编译 → 停
  - 抽取字段 JSON 校验通过 → 停
  - 相似度/置信度 ≥ 阈值 → 停
```

```java
static class QualityRouter implements com.alibaba.cloud.ai.graph.action.EdgeAction {
    public String apply(OverAllState s) {
        boolean valid = JsonValidator.isValid(s.value("draft","").toString());
        int tries = (Integer) s.value("tries", 0);
        if (valid || tries >= MAX) return "exit";
        return "retry";
    }
}
```

客观校验比模型自评可靠，避免 Loop 在"自以为好"时过早停，或在"永远不满意"时烧钱。

---

## 十九、企业案例：简历解析流水线

某 HR 系统用 Sequential 解析简历：

```
START → [解析PDF: 抽取文本] → [NLP: 结构化字段] → [校验: 必填项补全] → [入库] → END
```

每步失败有降级：解析失败→转人工上传；校验缺字段→标记待补。这条流水线每天处理上万份简历，比"一个 Agent 全包"稳得多——因为流程固定、可逐步排查。这正是 Sequential 的用武之地。

---

## 二十、小结

这一节我们把两个最基础也最高频的编排模式收进口袋：

1. **SequentialAgent** = 线性流水线，节点用 `addEdge` 串联，本质是"状态接力"；适合固定步骤、无分支的流程，可预测、易回滚。
2. **LoopAgent** = 循环执行，用条件边指回自身实现，核心是"业务退出条件 + 最大尝试上限"双保险；适合生成-自审、反复试探。
3. 两者常组合：Sequential 框大流程，Loop 处理难点自迭代。
4. 选型法则：能用 Sequential/Loop 解决的，绝不引入 Supervisor——复杂度越低越易运维。
5. 企业落地：对账报告"生成-自审"Loop 用迭代换质量，保证金额一致。

下一节（lesson05）我们讲一个生产里绕不开的模式——**Human In The Loop（人工介入）**：在 Graph 里留一个"人工审核节点"，让关键决策不全自动化。

---

## 延伸阅读 / 引用文档

1. Spring AI Alibaba Graph 循环/条件示例：https://java2ai.com/docs/1.0.0.2/tutorials/graph/examples/
2. Anthropic Building Effective Agents（含 prompt chain / loop）：https://www.anthropic.com/research/building-effective-agents
3. LangGraph 循环与中断（Human-in-the-loop 基础）：https://langchain-ai.github.io/langgraph/concepts/human_in_the_loop/
4. Spring AI Alibaba GitHub（graph-core）：https://github.com/alibaba/spring-ai-alibaba
5. Google ADK 循环 Agent（后续章节）：https://google.github.io/adk-docs/agents/loop-agent/
6. Plan-and-Execute 论文思路参考：https://arxiv.org/abs/23011.0156 （注：实际编号请核实）

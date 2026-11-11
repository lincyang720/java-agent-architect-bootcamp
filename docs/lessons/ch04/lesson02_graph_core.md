# 第四章第2节：Spring AI Alibaba Graph 核心概念：工作流编排、节点与边

大家好，我是秋天。

上一节我们把多 Agent 协作的"通用模型"立起来了：通信、分发、状态同步。这一节我们把模型落到 Spring AI Alibaba Graph 的具体 API 上。Graph 是这一章（乃至整个多 Agent 编排）最核心的工具，Supervisor、Sequential、Loop、HITL 全都是基于它搭的——所以这节课要打牢地基。

这节课讲什么、学完掌握什么：

- 讲什么：`StateGraph` 的构建、`KeyStrategyFactory` 与通道（Channel）/归约器（Reducer）、`NodeAction` / `AsyncNodeAction` 节点、`OverAllState` 读写、普通边/条件边/子图（subgraph）的写法、同步与异步执行。
- 学完掌握：你能独立画一张图、定义状态通道、写节点逻辑、用条件边做分支，并理解 Graph 和 lesson01 通用模型的对应关系。

<callout emoji="💡"><p>招聘映射：JD 高频"熟悉 Spring AI Alibaba Graph 工作流编排"。Graph 是阿里系 Agent 落地的主战力。能把"节点=计算、边=流转、状态=共享"讲清楚，并通过条件边实现分支循环，是硬技能。</p></callout>

---

## 一、Graph = 状态机 + 数据流

先建立一句话心智：**Graph 是一张"带共享状态的有向图"，节点做计算并写状态，边决定流转，状态在节点间流动**。对应 lesson01 的通用模型：

```
 lesson01 通用模型          Spring AI Alibaba Graph
 ─────────────────          ────────────────────────
 Agent (计算)        ◀────▶  NodeAction (节点)
 SharedState         ◀────▶  OverAllState (共享状态)
 Orchestrator(流转)  ◀────▶  Edge (边) + 图结构
 Flow(顺序/循环/条件)◀────▶  addEdge / addConditionalEdges
```

所以你不是在学"一个新框架"，而是在给 lesson01 的通用模型找一个可执行的实现。

---

## 二、状态定义：KeyStrategyFactory 与通道

Graph 的状态 `OverAllState` 不是随便塞 Map，而是用 `KeyStrategyFactory` 声明"有哪些 key、每个 key 用什么通道、怎么合并"。这是 Graph 比裸 Map 强的地方——**通道带归约策略**。

```java
package com.example.ch04.graph;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.Channel;
import com.alibaba.cloud.ai.graph.Reducer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class StateDefs {

    // 定义状态 schema：input 直接覆盖，messages 用追加归约
    public static KeyStrategyFactory buildKeyStrategy() {
        return () -> {
            Map<String, KeyStrategy> keys = new java.util.HashMap<>();
            // input：默认通道，后写覆盖前写
            keys.put("input", Channel.<String>create().build());
            // messages：列表通道，新消息追加而非覆盖（避免丢历史）
            keys.put("messages", Channel.<List<String>>create(
                    new Reducer<List<String>>() {
                        @Override
                        public List<String> apply(List<String> current, List<String> updates) {
                            List<String> merged = new ArrayList<>(current);
                            merged.addAll(updates);
                            return merged;
                        }
                    }).build());
            return keys;
        };
    }
}
```

这里 `Reducer` 决定了"多个节点都写 `messages` 时怎么合并"。用追加，历史不丢；用覆盖，只剩最后一份。Lesson01 讲的"写冲突"坑，Graph 用通道归约在机制层面解决了。

<callout emoji="⚠️"><p>新手最容易犯的错：把"对话历史""工具结果"这类需要累积的状态，用了默认覆盖通道，导致只有最后一次写入生效。凡是"会多次追加"的状态（消息、日志、子结果），务必用追加归约的列表通道。</p></callout>

---

## 三、节点：NodeAction 与 AsyncNodeAction

节点是计算单元。实现 `NodeAction` 接口，`apply(OverAllState)` 返回"要合并进状态的增量 Map"。同步写法是 `NodeAction`，生产推荐包一层 `AsyncNodeAction.node_async(...)` 获得异步执行能力。

```java
package com.example.ch04.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Map;

public class Nodes {

    // 节点1：调用大模型做摘要，写入 state.summary
    public static class SummarizeNode implements NodeAction {
        private final ChatClient chatClient;
        public SummarizeNode(ChatClient chatClient) { this.chatClient = chatClient; }

        @Override
        public Map<String, Object> apply(OverAllState state) throws Exception {
            String doc = state.value("input", "").toString();
            String summary = chatClient.prompt()
                    .system("你是摘要专家，输出不超过80字。")
                    .user("摘要：\n" + doc)
                    .call()
                    .content();
            return Map.of("summary", summary, "messages", List.of("summarize done"));
        }
    }

    // 节点2：基于摘要生成标题
    public static class TitleNode implements NodeAction {
        @Override
        public Map<String, Object> apply(OverAllState state) {
            String summary = state.value("summary", "").toString();
            String title = "【摘要】" + summary.substring(0, Math.min(10, summary.length()));
            return Map.of("title", title);
        }
    }
}
```

注意 `SummarizeNode` 写了 `messages`——因为状态里 `messages` 是追加通道，这里 `List.of("summarize done")` 会被追加进去，而不是覆盖之前的内容。

---

## 四、组装图：节点 + 边

有了状态和节点，用 `StateGraph` 把它们连起来。`addEdge(START, ...)` 是入口，`addEdge(..., END)` 是出口。

```java
package com.example.ch04.graph;

import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.GraphStateException;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Map;
import java.util.Optional;

public class BuildGraph {

    public static CompiledGraph build(ChatClient chatClient) throws GraphStateException {
        KeyStrategyFactory ksf = StateDefs.buildKeyStrategy();

        StateGraph graph = new StateGraph("docPipeline", ksf);
        graph.addNode("summarize",
                AsyncNodeAction.node_async(new Nodes.SummarizeNode(chatClient)));
        graph.addNode("title",
                AsyncNodeAction.node_async(new Nodes.TitleNode()));

        graph.addEdge(StateGraph.START, "summarize");
        graph.addEdge("summarize", "title");
        graph.addEdge("title", StateGraph.END);

        return graph.compile();
    }

    public static void main(ChatClient chatClient) throws GraphStateException {
        CompiledGraph g = build(chatClient);
        Optional<OverAllState> r = g.invoke(
                Map.of("input", "这是一篇关于多智能体协作的长文档……"),
                RunnableConfig.builder().threadId("doc-1").build());
        r.ifPresent(s -> {
            System.out.println("摘要: " + s.value("summary").orElse(""));
            System.out.println("标题: " + s.value("title").orElse(""));
            System.out.println("消息流: " + s.value("messages").orElse(List.of()));
        });
    }
}
```

执行顺序：`START → summarize → title → END`。`summarize` 写 `summary` 和追加 `messages`，`title` 读 `summary` 写 `title`。状态在节点间自然流动——这就是 Graph 的价值。

---

## 五、条件边：分支与循环的开关

普通边是"无脑往下走"，条件边 `addConditionalEdges(from, EdgeAction, Map)` 让流转**依赖运行时状态**。这是实现"if/else 分支"和"loop 循环"的核心机制（lesson03/04 会反复用）。

```java
package com.example.ch04.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;

public class Edges {

    // 根据 state.check 的值路由：pass 去 END，fail 去 revise
    public static class QualityRouter implements EdgeAction {
        @Override
        public String apply(OverAllState state) {
            Boolean ok = (Boolean) state.value("check", false);
            return Boolean.TRUE.equals(ok) ? "pass" : "revise";
        }
    }
}
```

```java
// 在图中使用
graph.addConditionalEdges("summarize",
        AsyncNodeAction_edge.async(new Edges.QualityRouter()),   // EdgeAction 同样可异步
        Map.of("pass", StateGraph.END, "revise", "summarize"));
//                                   ↑ 注意："revise" 指回 "summarize" 自身 = 循环重写
```

看到没？把条件边的目标指回当前节点，就形成了**循环**——这正是 lesson04 的 LoopAgent 的实现原理。Graph 用同一个机制统一表达分支和循环，非常优雅。

<callout emoji="✅"><p>工程经验：循环必须有"退出条件"，否则就是死循环（lesson01 讲的活锁）。在 QualityRouter 里一定要判断"重试次数 >= 上限"就强制走 END。生产 Graph 里每个回边都得配上限保护。</p></callout>

---

## 六、子图（Subgraph）：复用与层级

复杂系统里，一张大图会很难维护。Graph 支持把一张编译好的 `CompiledGraph` 作为节点嵌入另一张图——这就是子图，也是"层次化控制"（lesson09）的实现手段。

```java
package com.example.ch04.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;

import java.util.Map;

public class SubGraphNode implements NodeAction {
    private final CompiledGraph subGraph;
    public SubGraphNode(CompiledGraph subGraph) { this.subGraph = subGraph; }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        // 父图状态取出输入，喂给子图
        String data = state.value("data", "").toString();
        OverAllState subResult = subGraph.invoke(
                Map.of("input", data),
                com.alibaba.cloud.ai.graph.RunnableConfig.builder().build()
        ).orElseThrow();
        // 子图输出写回父图
        return Map.of("subOutput", subResult.value("output").orElse(""));
    }
}
```

子图让"大 Agent 拆小 Agent"在 Graph 层面有了承载：父图编排子图，子图内部再编排节点。这是层次化多 Agent 的骨架。

---

## 七、同步 vs 异步执行

Graph 两种执行方式：

```java
// 同步：invoke，阻塞直到整图跑完
Optional<OverAllState> syncResult = compiled.invoke(input, config);

// 异步：stream/async，适合长任务，可观察中间状态、可中断
// （返回流或 CompletableFuture，取决于 API 形态）
```

长任务（如"生成一份 50 页报告"）务必异步，否则 HTTP 线程被占死。配合 `RunnableConfig.threadId` 做状态隔离——不同 threadId 对应不同会话/任务的状态互不干扰，这也是"断点续跑"的基础（第十七章 Durable Execution 会深入）。

<callout emoji="💡"><p>threadId 是 Graph 的状态隔离键，类比数据库的事务 ID。同一个 threadId 多次 invoke，状态是连续的（适合多轮对话/长任务）；不同 threadId 互不可见。生产里用它做"每用户每会话独立状态"，是并发安全的基石。</p></callout>

---

## 八、企业场景：客诉处理流水线（Graph 版）

把 lesson06 的客诉场景用本节的 API 完整实现骨架：

```
START →[分类节点]→(条件边)→ risk=HIGH →[人工审核节点]→ END
                            → risk=LOW  →[自动回复节点]→ END
```

```java
StateGraph g = new StateGraph("complaint", ksf);
g.addNode("classify",  node_async(new ClassifyNode(chatClient)));
g.addNode("human",     node_async(new HumanReviewNode()));
g.addNode("autoReply", node_async(new AutoReplyNode(chatClient)));
g.addEdge(START, "classify");
g.addConditionalEdges("classify", edge_async(new RiskRouter()),
        Map.of("human", "human", "autoReply", "autoReply"));
g.addEdge("human", END);
g.addEdge("autoReply", END);
```

`ClassifyNode` 用 `ChatClient` + 结构化输出（lesson04）产出 `risk`；`RiskRouter` 读 `risk` 决定路由。全流程确定性、可观测、可回滚——这就是企业买账的 Agent 形态。

---

## 十、节点间数据流可视化与调试

Graph 调试的核心是"看每个节点读写的状态"。生产里建议给状态打快照日志：

```java
// 概念：在每个节点执行后打印状态增量，便于排查"哪步数据错了"
static class LoggingNode implements com.alibaba.cloud.ai.graph.action.NodeAction {
    private final com.alibaba.cloud.ai.graph.action.NodeAction delegate;
    public LoggingNode(com.alibaba.cloud.ai.graph.action.NodeAction d) { this.delegate = d; }
    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        Map<String, Object> out = delegate.apply(state);
        System.out.println("[node] wrote keys=" + out.keySet() + " thread=" + state.value("__threadId"));
        return out;
    }
}
```

把每个业务节点包一层 `LoggingNode`，就能在日志里看到"节点A写了summary、节点B读了summary写了title"的完整数据流——这是 Graph 比手写 if/else 好调试的根本原因：**状态流转显式可观测**。

<callout emoji="💡"><p>进阶：把这种日志接到 OpenTelemetry（lesson07 企业级），每个节点变成一个 span，整个 Graph 执行在 Jaeger 里是一张漂亮的调用图。调试多 Agent 再也不用靠"println 玄学"。</p></callout>

---

## 十一、错误处理与节点重试

节点里调模型可能超时/限流。Graph 本身不自动重试节点，需在节点内或在编排层处理：

```java
static class RetryableNode implements com.alibaba.cloud.ai.graph.action.NodeAction {
    private final ChatClient chatClient;
    public RetryableNode(ChatClient c) { this.chatClient = c; }
    @Override
    public Map<String, Object> apply(OverAllState state) {
        int max = 3;
        for (int i = 1; i <= max; i++) {
            try {
                String r = chatClient.prompt().user(state.value("input","")).call().content();
                return Map.of("result", r);
            } catch (Exception e) {
                if (i == max) throw new RuntimeException("节点执行失败", e);
                // 指数退避后重试
                try { Thread.sleep(500L * i); } catch (InterruptedException ignored) {}
            }
        }
        return Map.of();
    }
}
```

<callout emoji="⚠️"><p>节点重试要幂等：重试时别重复产生副作用（如重复发短信）。读模型/查数据可重试，写外部系统要幂等键保护。这是第十章"幂等与重试"的 Graph 落地版。</p></callout>

---

## 十二、Graph 与 Supervisor 的边界

有同学会混淆：Graph 和 lesson03 的 Supervisor 是什么关系？一句话：**Graph 是"编排引擎"，Supervisor 是"跑在引擎上的一种模式"**。你可以：
- 用 Graph 实现 Supervisor（路由节点 + 条件边）；
- 也可以在 Graph 的某个节点里放一个 ReActAgent（lesson06）；
- 甚至用 Supervisor 模式来"编排多张 Graph"（层次化）。

所以它们是不同抽象层：Graph 管"节点和边怎么执行"，Supervisor 管"任务怎么分给谁"。别把它们对立起来。

---

## 十三、面试自测

1. **通道归约解决什么？** —— 多节点写同一 key 时的合并策略，防覆盖丢数据。
2. **条件边能实现循环吗？** —— 能，目标指回自身节点；必须配退出条件+上限。
3. **子图干嘛用？** —— 复用与层级编排，大 Agent 拆小 Agent。
4. **Graph 和 Supervisor 关系？** —— Graph 是引擎，Supervisor 是跑在上面的模式。

---

## 十五、Graph 可视化：把执行画出来

Graph 的结构就是一张图，生产里建议把它可视化，便于团队（含非研发的运营/架构）理解流程。一个典型的客诉 Graph 可视化：

```
        ┌───────── START ─────────┐
        ▼                        │
   [classify 分类节点]            │
        │                        │
        ├─ risk=HIGH ─▶ [human_review] ─┐
        │                                │
        └─ risk=LOW ──▶ [auto_reply] ───┤
                                        ▼
                                   [END]
```

配合可观测（lesson07 企业级），运行时还能显示"当前走到了哪个节点、每个节点耗时"——这是 Graph 比手写代码最直观的优势：**流程可见、可沟通、可治理**。

---

## 十六、Graph 与 AgentScope 的互操作

本章用 Graph（Spring AI Alibaba）和 AgentScope ReAct 讲了两种范式。它们不是二选一，可互操作：

```
Graph 节点里跑 ReActAgent：
   [Graph节点A] ─▶ [Graph节点B: 内含 ReActAgent 自主循环] ─▶ [Graph节点C]
```

即：用 Graph 管大流程的确定性（顺序/分支），在某个节点里放一个 AgentScope 的 `ReActAgent` 处理"步骤数未知的子任务"。AgentScope 与 Spring AI Alibaba 都跑在 Java 生态，集成成本不高。

<callout emoji="💡"><p>架构思路：Graph 是"骨架"（确定、可审计），ReAct 是"肌肉"（自主、灵活）。骨架用 Graph 写死，肌肉用 ReAct 填充，二者组合得到"既可控又智能"的系统。这是层次化（lesson09）的一种实现。</p></callout>

---

## 十七、面试自测补充

1. **Graph 相比手写编排优势？** —— 结构可见、状态 OverAllState 管理、持久化续跑、节点级可观测。
2. **和 AgentScope 互操作？** —— Graph 节点内嵌 ReActAgent，骨架确定+肌肉自主。
3. **子图干嘛？** —— 复用与层级编排，大 Agent 拆小 Agent。
4. **条件边循环必须有？** —— 退出条件 + 上限保护，防死循环。

---

## 十九、Graph 的状态类型：值类型 vs 通道类型

lesson02 讲了 `KeyStrategyFactory` 定义状态 schema，这里补充"值类型"和"通道类型"的区别，这是很多人混淆的点：

- **值类型（value）**：普通键值，写入即覆盖（如 `input`、`domain`）。
- **通道类型（channel）**：带归约策略，写入按策略合并（如 `messages` 用追加）。

```java
// 值类型：后写覆盖前写
m.put("input", Channel.<String>create().build());
// 通道类型：列表追加，不丢历史
m.put("messages", Channel.<List<String>>create(
        (cur, up) -> { var m = new ArrayList<>(cur); m.addAll(up); return m; }).build());
```

混淆两者是常见 bug：把"该累积的状态"用值类型，结果只有最后一次写入（lesson01 讲过的"写冲突"在 Graph 里的具体表现）。

<callout emoji="⚠️️<p>排查口诀：发现"状态丢了中间值"，先查这个 key 是值类型还是通道类型。该累积（消息/日志/子结果）的必须用通道+追加归约。</p></callout>

---

## 二十、Graph 的编译期校验

`compile()` 不只是"生成可执行图"，它还会做静态校验，提前暴露错误：

- START 没有出边 → 报错（图走不通）。
- 某节点没有出边且不是 END → 警告（可能死路）。
- 条件边的目标节点不存在 → 报错。
- 类型不匹配（节点返回非 Map）→ 报错。

```java
try {
    CompiledGraph g = graph.compile();   // 编译期就校验图结构
} catch (GraphStateException e) {
    // 图结构有误：缺边/悬空节点/类型错
    log.error("图编译失败: {}", e.getMessage());
}
```

工程经验：**在单元测试里 compile() 每张图**，确保图结构正确，而不是等到运行时才炸。这是 Graph 比手写 if/else 多一层安全保障。

---

## 二十一、Graph 与 Spring Bean 的集成

生产里节点常要注入 Spring Bean（ChatClient、Repository、工具）。Graph 在 Spring 环境里这样组织：

```java
@Configuration
public class GraphConfig {
    @Bean
    public CompiledGraph complaintGraph(ChatClient.Builder builder,
                                        ReconcileTools tools) {
        ChatClient client = builder.defaultTools(tools).build();
        // 节点用注入的 client 构建
        StateGraph g = new StateGraph("complaint", ksf);
        g.addNode("classify", node_async(new ClassifyNode(client)));
        // ... 边
        return g.compile();
    }
}
```

节点类持有 `ChatClient`/工具，由 Spring 注入；Graph 作为 `CompiledGraph` Bean 暴露，Controller 直接注入调用。这样 Graph 完全融入 Spring 生命周期，可测试、可注入、可观测。

---

## 二十二、企业案例：Graph 编排的"合同风险审查"

某法务中台用 Graph 编排合同审查：

```
START → [抽取条款] → [逐条风险审查 Loop] → [汇总报告] → [HITL 终审] → END
                              │ 不达标
                              └──── 补充分析（Loop 回边）
```

用 lesson02 的全部机制：通道存累积的审查意见、条件边做 Loop、HITL 节点做终审。一张图把"多步骤 + 循环 + 人工"全部表达，法务人员只面对一个清晰流程图，而非黑盒。

---

## 二十三、小结

这一节我们把 lesson01 的通用模型落到了 Graph 的具体 API：

1. Graph = 带共享状态的有向图：节点做计算、边管流转、状态在节点间流动。
2. 状态用 `KeyStrategyFactory` + 通道（Channel）+ 归约器（Reducer）定义；累积型状态务必用追加归约，防覆盖丢数据。
3. 节点实现 `NodeAction`，`apply` 返回写入状态的增量；生产用 `AsyncNodeAction.node_async` 异步化。
4. 普通边 `addEdge` 顺序流转；条件边 `addConditionalEdges` 按状态分支/循环（目标指回自身=循环，必须配上限）。
5. 子图（CompiledGraph 作节点）实现层级编排，是层次化控制的基础。
6. 同步 `invoke` / 异步执行 + `threadId` 状态隔离，长任务必异步。
7. 企业落地：客诉处理流水线用"分类节点 + 条件边路由"表达。

下一节（lesson03）我们用 Graph 实现第一个多 Agent 模式——**SupervisorAgent：一个主控 Agent 协调多个子 Agent**。

---

## 延伸阅读 / 引用文档

1. Spring AI Alibaba Graph 核心文档：https://java2ai.com/docs/1.0.0.2/tutorials/graph/
2. Spring AI Alibaba Graph 示例（subgraph/conditional）：https://java2ai.com/docs/1.0.0.2/tutorials/graph/examples/
3. Spring AI Alibaba GitHub（graph-core 模块）：https://github.com/alibaba/spring-ai-alibaba
4. LangGraph 概念（Graph 思想源头）：https://langchain-ai.github.io/langgraph/concepts/
5. 工作流编排官方教程：https://java2ai.com/docs/1.0.0.2/frameworks/graph-core/
6. Spring AI Alibaba 1.0 GA 公告：https://developer.aliyun.com/article/1666891

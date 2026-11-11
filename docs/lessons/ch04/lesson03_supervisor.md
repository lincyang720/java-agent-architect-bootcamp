# 第四章第3节：SupervisorAgent 模式：主控 Agent 协调多个子 Agent

大家好，我是秋天。

上一节 Graph 的地基打完了。这一节我们讲第一个、也是生产里最常用到的多 Agent 模式——**Supervisor（主控）模式**。一句话：一个"主管"Agent 看全局任务，决定分给哪个"下属"Agent 干，再汇总结果。就像公司里项目经理把活分下去、收上来、做整合。

这节课讲什么、学完掌握什么：

- 讲什么：Supervisor 模式的拓扑结构、用 Graph 怎么实现（主控节点 + 条件边路由 + 子 Agent 节点）、Supervisor 怎么"决策"分发、结果怎么"汇总"、以及它和 lesson01 讲的"主控分发"策略的对应。
- 学完掌握：你能用 Graph 搭一个"Supervisor 节点根据任务类型，把活分发给客服/财务/技术三个子 Agent 并汇总"的可运行骨架；你理解 Supervisor 为什么会成为"中心化控制"的代表（也是 lesson09 对比的对象）。

<callout emoji="💡"><p>招聘映射：JD 里"熟悉 Supervisor / 多 Agent 编排 / 中心化控制"。Supervisor 是面试高频考点，常被问"和广播/流水线有什么区别""Supervisor 挂了怎么办"。这一节给你标准答案。</p></callout>

---

## 一、Supervisor 模式的拓扑

```
            ┌───────────── Supervisor ─────────────┐
            │  ① 理解任务                            │
            │  ② 决定分发给谁                         │
            │  ③ 汇总子 Agent 结果                    │
            │  ④ 生成最终回复 / 决定是否需要转人工     │
            └───┬──────────┬──────────┬───────────┘
                │          │          │
                ▼          ▼          ▼
          [客服Agent] [财务Agent] [技术Agent]
                │          │          │
                └──── 结果回传 Supervisor ──┘
```

关键特征：
- **子 Agent 之间不直接通信**，全部通过 Supervisor 中转。这叫"星型拓扑 / 中心化"。
- Supervisor 持有全局视野，负责"理解—分发—汇总—决策"。
- 子 Agent 是专职的（客服只懂客服，财务只懂财务），上下文干净、提示词精简。

对应 lesson01 的"任务分发策略 1：主控分发"——Supervisor 就是那个 Orchestrator。

---

## 二、用 Graph 实现 Supervisor：主控节点 + 条件边

在 Graph 里，Supervisor 模式 = 一个"路由节点" + 多个"子 Agent 节点" + 条件边。路由节点读任务，决定下一个去哪个子 Agent；子 Agent 干完活，把结果写回状态，再回到路由节点（或汇总节点）。

```java
package com.example.ch04.supervisor;

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

public class SupervisorGraph {

    // ① 路由节点：判断任务类型，返回目标子 Agent 名
    static class RouterNode implements NodeAction {
        private final ChatClient chatClient;
        public RouterNode(ChatClient c) { this.chatClient = c; }

        @Override
        public Map<String, Object> apply(OverAllState state) {
            String task = state.value("input", "").toString();
            // 用模型判断任务领域（也可用规则/分类器，更稳）
            String domain = chatClient.prompt()
                    .system("判断任务属于：客服 / 财务 / 技术。只输出一个词。")
                    .user(task)
                    .call()
                    .content().trim();
            return Map.of("domain", domain, "messages", List.of("route:" + domain));
        }
    }

    // ② 客服子 Agent
    static class CustomerAgent implements NodeAction {
        private final ChatClient chatClient;
        public CustomerAgent(ChatClient c) { this.chatClient = c; }
        @Override
        public Map<String, Object> apply(OverAllState state) {
            String ans = chatClient.prompt()
                    .system("你是客服专家。").user(state.value("input","").toString())
                    .call().content();
            return Map.of("result_customer", ans);
        }
    }

    // ③ 财务子 Agent（同理）
    static class FinanceAgent implements NodeAction {
        private final ChatClient chatClient;
        public FinanceAgent(ChatClient c) { this.chatClient = c; }
        @Override
        public Map<String, Object> apply(OverAllState state) {
            String ans = chatClient.prompt()
                    .system("你是财务专家。").user(state.value("input","").toString())
                    .call().content();
            return Map.of("result_finance", ans);
        }
    }

    // ④ 路由边：根据 state.domain 决定去哪个子 Agent
    static class RouteEdge implements EdgeAction {
        @Override
        public String apply(OverAllState state) {
            String d = state.value("domain", "客服").toString();
            return switch (d) {
                case "财务" -> "finance";
                case "技术" -> "tech";
                default     -> "customer";
            };
        }
    }

    // ⑤ 汇总节点：把各子 Agent 结果拼成最终回复
    static class AggregateNode implements NodeAction {
        @Override
        public Map<String, Object> apply(OverAllState state) {
            StringBuilder sb = new StringBuilder("【汇总】\n");
            state.value("result_customer","").ifPresent(v -> sb.append("客服: ").append(v).append("\n"));
            state.value("result_finance","").ifPresent(v -> sb.append("财务: ").append(v).append("\n"));
            return Map.of("final", sb.toString());
        }
    }

    public static CompiledGraph build(ChatClient chatClient) throws GraphStateException {
        var ksf = (com.alibaba.cloud.ai.graph.KeyStrategyFactory) () -> {
            java.util.Map<String, com.alibaba.cloud.ai.graph.KeyStrategy> m = new java.util.HashMap<>();
            m.put("input", com.alibaba.cloud.ai.graph.Channel.<String>create().build());
            m.put("domain", com.alibaba.cloud.ai.graph.Channel.<String>create().build());
            m.put("result_customer", com.alibaba.cloud.ai.graph.Channel.<String>create().build());
            m.put("result_finance", com.alibaba.cloud.ai.graph.Channel.<String>create().build());
            m.put("final", com.alibaba.cloud.ai.graph.Channel.<String>create().build());
            m.put("messages", com.alibaba.cloud.ai.graph.Channel.<List<String>>create(
                    new com.alibaba.cloud.ai.graph.Reducer<List<String>>() {
                        public List<String> apply(List<String> cur, List<String> up) {
                            java.util.List<String> merged = new java.util.ArrayList<>(cur);
                            merged.addAll(up); return merged;
                        }
                    }).build());
            return m;
        };

        StateGraph g = new StateGraph("supervisor", ksf);
        g.addNode("router", com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async(new RouterNode(chatClient)));
        g.addNode("customer", com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async(new CustomerAgent(chatClient)));
        g.addNode("finance", com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async(new FinanceAgent(chatClient)));
        g.addNode("aggregate", com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async(new AggregateNode()));

        g.addEdge(StateGraph.START, "router");
        // 条件边：router 之后按 domain 路由到子 Agent
        g.addConditionalEdges("router",
                com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async(new RouteEdge()),
                Map.of("customer", "customer", "finance", "finance", "tech", "tech"));
        // 子 Agent 干完都去汇总
        g.addEdge("customer", "aggregate");
        g.addEdge("finance", "aggregate");
        g.addEdge("aggregate", StateGraph.END);
        return g.compile();
    }
}
```

这个骨架完整表达了 Supervisor 模式：主控 `router` 决定分发，子 Agent 并行/串行执行，最终 `aggregate` 汇总。注意我这里为了清晰，子 Agent 干完都回流到 `aggregate`——真实场景你也可以让 router 在子 Agent 完成后再次介入（多轮分发）。

<callout emoji="✅"><p>工程经验：路由节点的"任务分类"不一定非要让 LLM 做。生产里常用"规则优先 + 模型兜底"——先用关键词/意图分类器（快、稳、便宜）判断 domain，模型只在模糊时介入。把 LLM 用在真正需要理解力的地方，省成本也降延迟。</p></callout>

---

## 三、Supervisor 的"多轮分发"：更像真实项目经理

上面的骨架是一次分发。真实 Supervisor 往往是"多轮"：分发 → 看结果 → 再分发 → 汇总。用 Graph 的回边实现：

```
router ──▶ 子Agent ──▶ 判断节点 ──(未完成)──▶ router
                                └──(完成)──▶ aggregate ──▶ END
```

判断节点读"是否所有子任务完成"的状态，未完成就指回 router 继续分下一轮。这就是"编排-执行（Orchestrator-Workers）"模式的 Graph 表达（第九章会归类）。

---

## 四、Supervisor 的优劣（为 lesson09 铺垫）

先给结论，细节 lesson09 展开：

| 维度 | Supervisor（中心化）优势 | 风险 |
|------|------------------------|------|
| 可控性 | 全局视野、流程清晰、易审计 | —— |
| 调试 | 单点可观测，出问题好定位 | —— |
| 瓶颈 | —— | Supervisor 是单点，挂了全停 |
| 扩展性 | —— | 子 Agent 多了，路由逻辑复杂 |

<callout emoji="⚠️"><p>Supervisor 单点故障是真实风险。生产里要给 Supervisor 节点加：① 超时与降级（router 判断失败就转人工/默认 Agent）；② 幂等（重复分发不产生副作用）；③ 监控（Supervisor 节点耗时/错误率告警）。这些在第十章工程化细讲。</p></callout>

---

## 五、企业场景：智能客服总机

真实落地——某银行的"智能客服总机"，一个 Supervisor 把用户问题分发给专线 Agent：

```
用户: "我的信用卡被多扣了200，怎么退款？"
        │
        ▼
   [Supervisor 路由]
   判断: 含"扣款+退款" → 财务/客诉领域
        │
        ▼
   [账务Agent] 查交易 → [客诉Agent] 判责任 → [退款Agent] 发起流程
        │
        ▼
   [Supervisor 汇总] → 给用户: "已核实多扣200，退款将在3日内到账"
```

用户只跟一个入口对话，背后是多个专职 Agent 协作，但用户无感——这就是 Supervisor 模式在 C 端的最佳体验形态。后端跑在 Spring AI Alibaba Graph 上，`router`=Supervisor 节点，各专线=`customer/finance/...` 子节点。

<callout emoji="💡"><p>求职导向：能说出"Supervisor 模式 = 中心化多 Agent 编排，适合需要统一入口、统一审计的场景（客服总机、贷款初审）"，并主动点出"单点故障要用降级/监控兜底"，面试官会认为你不是只背名词，是真落地过。</p></callout>

---

## 七、多 Agent 的并行执行：Supervisor 下的扇出

前面例子是串行分发（一个子 Agent 干完再下一个）。真实场景里，多个子 Agent 可以**并行**：客服、财务、技术同时分析同一问题，Supervisor 最后汇总。Graph 用"从 router 连出多条无依赖边"实现扇出：

```java
// 概念：router 同时连 customer/finance/tech，三者并行执行
StateGraph g = new StateGraph("parallelSupervisor", ksf);
g.addNode("router", node_async(new RouterNode(chatClient)));
g.addNode("customer", node_async(new CustomerAgent(chatClient)));
g.addNode("finance",  node_async(new FinanceAgent(chatClient)));
g.addNode("tech",     node_async(new TechAgent(chatClient)));
g.addNode("aggregate", node_async(new AggregateNode()));
g.addEdge(START, "router");
g.addEdge("router", "customer");   // 扇出：三条边同时触发
g.addEdge("router", "finance");
g.addEdge("router", "tech");
g.addEdge("customer", "aggregate");
g.addEdge("finance",  "aggregate");
g.addEdge("tech",     "aggregate");
g.addEdge("aggregate", END);
```

`customer/finance/tech` 各自独立执行（Graph 运行时并行调度），`aggregate` 等三者都写道状态后汇总。这把"主控分发"和"并行化"结合，对应第九章"并行化模式"。

<callout emoji="✅"><p>并行前提是子 Agent 之间**无数据依赖**。若 finance 需要 customer 的结果，就不能并行，要先 customer 再 finance。依赖关系决定能否并行——这是编排的基本功。</p></callout>

---

## 八、Supervisor 的失败隔离与降级

Supervisor 单点风险（lesson03 提过），具体怎么兜底：

```java
static class SafeRouter implements com.alibaba.cloud.ai.graph.action.NodeAction {
    private final ChatClient chatClient;
    public SafeRouter(ChatClient c) { this.chatClient = c; }
    @Override
    public Map<String, Object> apply(OverAllState s) {
        try {
            String d = chatClient.prompt()
                    .system("判断领域：客服/财务/技术，只输出一词。").user(s.value("input","").toString())
                    .call().content().trim();
            return Map.of("domain", d);
        } catch (Exception e) {
            // 路由失败 → 降级到默认客服 Agent，而非整图崩溃
            return Map.of("domain", "customer", "routing_failed", true);
        }
    }
}
```

配合 Sentinel（lesson07 企业级）给 Supervisor 节点加限流熔断，单点不至于拖垮全系统。

---

## 九、Supervisor 与顺序/Loop 的组合

真实系统里 Supervisor 常包着 Sequential/Loop：外层 Supervisor 分发任务，内层子 Agent 用 Loop 自迭代。例如"财务分析"子 Agent 内部是"生成→自审→不达标重写"的 Loop（lesson04），外层由 Supervisor 调度。

```
Supervisor
  ├─ 客服Agent (单次)
  ├─ 财务Agent (内含 Loop: 生成-自审-重写)
  └─ 技术Agent (内含 Sequential: 检索-分析-报告)
```

这就是层次化控制的雏形（lesson09 会升华）。

---

## 十、企业场景：智能运维告警处置

某运维中台：告警进来，Supervisor 判断类型（网络/数据库/应用），分发给对应专家 Agent 并行分析，汇总成处置建议。

```
告警 ─▶ [Supervisor 路由] ─┬─▶ [网络Agent] ─┐
                          ├─▶ [数据库Agent] ─┼─▶ [汇总Agent] ─▶ 处置建议
                          └─▶ [应用Agent]  ─┘
```

并行分析比"串行一个个看"快数倍，且每个专家 Agent 提示词专精，准确率高。这正是 Supervisor 在 SRE 场景的落地。

<callout emoji="💡"><p>求职导向：能举出"告警处置 Supervisor + 并行专家 Agent"这种非客服类的落地案例，说明你理解了 Supervisor 的通用性，而不只是背"客服总机"一个例子。面试官喜欢有横向迁移能力的候选人。</p></callout>

---

## 十一、面试自测

1. **Supervisor 拓扑特征？** —— 星型，子 Agent 不直接互联，全听主管。
2. **怎么并行子 Agent？** —— 从路由节点连多条无依赖边，扇出并行。
3. **Supervisor 单点怎么兜底？** —— 路由失败降级默认 Agent + 限流熔断 + 幂等 + 监控。
4. **和顺序/Loop 关系？** —— 外层分发，内层可含顺序/循环（层次化）。

---

## 十三、Supervisor 的提示词设计：怎么写 router

Supervisor 的路由质量，取决于 router 节点的系统提示词写得好不好。要点：

```java
// router 节点的系统提示词（决定分发质量）
String ROUTER_SYS = """
你是一个任务路由器。根据用户问题，判断它属于以下哪个领域，只输出一个词：
- 客服：关于订单、物流、退换货的咨询
- 财务：关于账单、发票、退款、报销
- 技术：关于系统故障、接口报错、性能问题
若问题跨多个领域，选最相关的主领域。
""";
```

要点：
- **分类维度写清**：每个领域的边界举例说明，避免模型混淆。
- **冲突时给规则**：跨领域时选"最相关主领域"，避免来回横跳。
- **输出受限**：要求"只输出一个词"，降低下游解析成本（lesson03 路由边直接 switch）。

<callout emoji="⚠️"><p>router 提示词别写"请仔细思考后分类"这种废话。要写"边界 + 示例 + 输出格式约束"。分类准了，整个 Supervisor 就稳了。分类错了，后面全白干。</p></callout>

---

## 十四、Supervisor 的性能与成本

Supervisor 有额外开销：

- **路由延迟**：每次都要先过 router 模型调用一次（除非用规则分类，lesson03 提过）。
- **上下文胖**：Supervisor 节点本身要带全领域提示词，token 多于单 Agent。
- **汇总开销**：aggregate 节点要把各子 Agent 结果再喂给模型汇总一次。

优化：
- router 用规则/轻量分类器（不调 LLM），省一次调用。
- 子 Agent 结果先压缩再汇总（第十五章上下文压缩）。
- 并行子 Agent（lesson03 扇出），用延迟换吞吐。

---

## 十五、面试自测补充

1. **Supervisor 路由质量靠什么？** —— router 节点的系统提示词（边界+示例+输出约束）。
2. **额外开销？** —— 路由调用延迟、上下文更胖、汇总多一次模型调用；可用规则路由/并行/压缩优化。
3. **和顺序/Loop 怎么组合？** —— 外层分发，内层可含顺序/循环（层次化）。
4. **单点怎么兜底？** —— 路由失败降级默认 Agent + 限流熔断。

---

## 十七、Supervisor 的失败重试与隔离

子 Agent 执行可能失败（模型超时、工具异常）。Supervisor 要做隔离，不让一个子 Agent 的失败拖垮整图：

```java
static class SafeSubAgent implements com.alibaba.cloud.ai.graph.action.NodeAction {
    private final ChatClient chatClient;
    public SafeSubAgent(ChatClient c) { this.chatClient = c; }
    @Override
    public Map<String, Object> apply(OverAllState s) {
        try {
            String r = chatClient.prompt().user(s.value("input","")).call().content();
            return Map.of("result", r, "status", "OK");
        } catch (Exception e) {
            // 子 Agent 失败不抛，标记 FAIL 让 Supervisor 决定降级/重试
            return Map.of("status", "FAIL", "error", e.getMessage());
        }
    }
}
```

Supervisor 的聚合节点读 `status`，若 FAIL 则走降级分支（如转人工、用缓存答案）。**节点失败要"软失败"**——返回状态而非抛异常，让编排层统一决策。

<callout emoji="⚠️️<p>节点抛异常会导致整图中断、状态丢失。生产里所有子 Agent 节点都要 try-catch 软失败，把错误变成状态字段，由 Supervisor 统一处理。这是 Graph 编排的健壮性原则。</p></callout>

---

## 十八、Supervisor 与顺序/Loop 的代价对比

给一张完整决策表，帮你在该用哪个模式时不纠结：

| 维度 | Supervisor | Sequential | Loop |
|------|-----------|-----------|------|
| 适用 | 动态分发多专家 | 固定步骤流 | 反复自迭代 |
| 复杂度 | 中（要写路由） | 低 | 中（要写退出条件） |
| 并行 | 支持扇出 | 可扇出 | 串行 |
| 单点风险 | 有（主管） | 无 | 无 |
| 调试 | 中 | 易 | 中 |

口诀重申：**能用顺序/循环解决的，绝不引入 Supervisor**。Supervisor 只在"任务类型多变、需动态选专家"时才上。

---

## 十九、企业案例：智能 IT 运维分诊

某 SRE 中台用 Supervisor 做"告警分诊"：告警进来 → router 判断类型（网络/DB/应用）→ 分发给对应专家 Agent 并行分析 → 汇总成处置建议推给值班人。

```
告警 ─▶ Supervisor(路由) ─┬─▶ 网络Agent ─┐
                         ├─▶ DBAgent   ─┼─▶ 汇总Agent ─▶ 处置建议
                         └─▶ 应用Agent ─┘
```

价值：以前一个告警要人翻多个系统排查 20 分钟；现在并行分析 2 分钟出建议，值班人只做确认。这就是 Supervisor 在 SRE 的硬价值。

---

## 二十一、Supervisor 的提示词与工具协同设计

lesson03 讲了 router 提示词，这里补"子 Agent 提示词 + 工具"的协同。每个子 Agent 应是"角色 + 工具 + 输出约束"的闭环：

```java
// 财务子 Agent：角色清晰 + 专属工具 + 输出格式约束
ReActAgent finance = ReActAgent.builder()
        .name("FinanceAgent")
        .sysPrompt("你是财务专家，只基于工具返回的数据回答，输出 JSON。")
        .model(model)
        .toolkit(financeTools)          // 只给它财务工具（最小权限）
        .maxIters(6)
        .build();
```

要点：① 子 Agent 提示词只讲"自己领域"，别让它操心别的；② 工具按领域最小化授权（lesson07 安全）；③ 输出格式约束（lesson04 结构化）让结果可被 Supervisor 汇总。三者协同，子 Agent 才"称职"。

<callout emoji="✅"><p>设计原则：子 Agent 的"上下文越小越专注，效果越好"。把不属于它的知识/工具/历史都剥离，它就不会跑偏。这呼应第十五章"上下文工程"的 Select/Isolate 策略。</p></callout>

---

## 二十二、Supervisor vs 直接多模型路由（无 Agent）

有人问："我用规则直接调不同模型/服务，不也行吗？"行，但区别在"是否让模型参与决策"：

| 方案 | 决策主体 | 适合 |
|------|---------|------|
| 规则路由（if/else 调服务） | 代码 | 分支固定、无需理解语义 |
| Supervisor（模型路由） | 模型 | 需理解用户意图再分流 |

若分流靠"关键词"就能定（如 URL 路径），用规则；若分流需"理解这句话到底想干啥"，用 Supervisor 让模型判。别为用 Agent 而用 Agent。

---

## 二十三、面试自测终版

1. **Supervisor 完整链路？** —— router 分类 → 条件边分发 → 子 Agent 执行（并行/串行）→ 汇总 → 输出；高风险接 HITL。
2. **子 Agent 怎么设计？** —— 角色聚焦 + 工具最小授权 + 输出格式约束，上下文越小越稳。
3. **和规则路由区别？** —— 规则靠代码定分支，Supervisor 靠模型理解意图分流。
4. **单点兜底？** —— 路由失败降级默认 Agent + 限流熔断 + 软失败隔离。

---

## 二十四、小结

这一节我们用 Graph 实现了第一个多 Agent 模式：

1. Supervisor 模式 = 星型拓扑：主管 Agent 统一"理解—分发—汇总"，子 Agent 不直接互联。
2. Graph 实现 = 路由节点 + 条件边（按 domain 分发）+ 子 Agent 节点 + 汇总节点。
3. 路由分类可用"规则优先 + 模型兜底"，省成本降延迟。
4. 多轮分发用回边实现"编排-执行"，更接近真实项目经理。
5. 优势是可控、可审计；风险是 Supervisor 单点，需降级/幂等/监控兜底。
6. 企业落地：智能客服总机 = 统一入口 + 多专线 Agent 协作。

下一节（lesson04）我们讲另外两个基础模式——**SequentialAgent（顺序）与 LoopAgent（循环）**，它们比 Supervisor 更简单，但覆盖了绝大多数线性业务流程。

---

## 延伸阅读 / 引用文档

1. Spring AI Alibaba Graph 编排文档：https://java2ai.com/docs/1.0.0.2/tutorials/graph/
2. Anthropic Building Effective Agents（含 orchestrator-workers）：https://www.anthropic.com/research/building-effective-agents
3. LangGraph 多 Agent（Supervisor）示例：https://langchain-ai.github.io/langgraph/tutorials/multi_agent/
4. AutoGen 多 Agent 对话框架（对比参考）：https://microsoft.github.io/autogen/
5. Spring AI Alibaba GitHub：https://github.com/alibaba/spring-ai-alibaba
6. Google ADK Multi-Agent（后续章节参考）：https://google.github.io/adk-docs/

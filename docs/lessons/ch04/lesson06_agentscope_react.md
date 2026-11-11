# 第四章第6节：AgentScope Java 框架的 ReAct 范式实现：思考-行动-观察循环

大家好，我是秋天。

前面五节我们都在 Spring AI Alibaba Graph 里手动拼节点和边。这一节我们换个框架——**AgentScope Java**，看它怎么把"ReAct（Reasoning + Acting，思考-行动-观察）"这种经典 Agent 范式**封装成一个开箱即用的 Agent**。你会发现，lesson04 讲的 Loop 在 AgentScope 里被框架自动化成了"思考→调工具→看结果→再思考"的循环，你不用自己写条件边了。

这节课讲什么、学完掌握什么：

- 讲什么：ReAct 范式原理（思考 Thought → 行动 Action → 观察 Observation 的循环）、AgentScope Java 的 `ReActAgent` 怎么用、工具（`@Tool`）怎么注册、`toolkit` 怎么组织、循环怎么自动终止。
- 学完掌握：你能用 AgentScope Java 搭一个带工具调用的 `ReActAgent`，让它自主决定"调哪个工具、调几次"，直到任务完成；你理解 ReAct 和 lesson04 Loop 的对应关系。

<callout emoji="💡"><p>招聘映射：JD 里"熟悉 ReAct / Plan-and-Execute / Agent 自主规划"。ReAct 是 Agent 最经典的推理范式，几乎所有 Agent 框架的底层。能讲清"思考-行动-观察"循环和终止条件，是 Agent 方向候选人的基本功。</p></callout>

---

## 一、ReAct 范式：Agent 自主干活的底层逻辑

ReAct 是 2022 年提出、至今最主流的 Agent 推理范式。它把 LLM 的"思考"和"行动"交织起来，形成循环：

```
┌──────────────── ReAct 循环 ────────────────┐
│                                              │
│  Thought (思考): 我要查天气，得调 getWeather │
│       │                                      │
│       ▼                                      │
│  Action  (行动): 调用 getWeather("北京")      │
│       │                                      │
│       ▼                                      │
│  Observation (观察): 返回 "晴 25℃"           │
│       │                                      │
│       ▼                                      │
│  Thought: 已知天气，可以回答用户了            │
│       │                                      │
│       ▼                                      │
│  Action: 输出最终答案 → (结束)               │
└──────────────────────────────────────────────┘
```

对比 lesson04 的 Loop：Loop 是"你写死循环条件"，ReAct 是"模型自己决定要不要再行动"，循环次数由模型判断。ReAct = 智能版 Loop。这也是它强大的地方——能应对未知步骤数的任务（比如"查完 A 再决定要不要查 B"）。

---

## 二、AgentScope Java 的 ReActAgent：最小可运行

AgentScope 是阿里开源的 Agent 框架，Java 版（`io.agentscope`）把 `ReActAgent` 做成核心抽象。先看一个能跑的骨架（基于官方 API）：

```java
package com.example.ch04.agentscope;

import io.agentscope.agent.ReActAgent;
import io.agentscope.model.Model;
import io.agentscope.model.DashScopeChatModel;
import io.agentscope.toolkit.Toolkit;

public class ReActDemo {

    public static void main(String[] args) {
        // 1. 准备模型（通义千问 DashScope）
        Model model = DashScopeChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen-plus")
                .stream(true)
                .build();

        // 2. 准备工具箱（把 @Tool 方法注册进去）
        Toolkit toolkit = new Toolkit();
        toolkit.register(new WeatherTool());

        // 3. 构建 ReActAgent
        ReActAgent agent = ReActAgent.builder()
                .name("Assistant")
                .sysPrompt("你是一名有帮助的助手，需要时用工具查证。")
                .model(model)
                .toolkit(toolkit)
                .maxIters(10)         // 防止无限循环的安全阀
                .build();

        // 4. 发起对话（返回 Msg，可 .block() 同步取）
        io.agentscope.message.Msg reply = agent.call(
                io.agentscope.message.Msg.builder()
                        .role(io.agentscope.message.MsgRole.USER)
                        .content(java.util.List.of(
                                io.agentscope.message.TextBlock.builder()
                                        .text("北京现在天气怎么样？适合穿短袖吗？")
                                        .build()))
                        .build()
        ).block();

        System.out.println(reply.getTextContent());
    }
}
```

关键点：
- `ReActAgent.builder()`：声明式构建，跟 Spring 的 Builder 风格一致，好上手。
- `.toolkit(toolkit)`：把工具注册进去，模型在 ReAct 循环里会自动决定调用。
- `.maxIters(10)`：最大迭代次数——这是 ReAct 的"上限保护"，对应 lesson04 讲的 Loop 安全阀。没有它，模型可能陷入"永远觉得自己还差点信息"的死循环。
- `agent.call(msg).block()`：返回 `Msg`，`.block()` 是 Reactor 风格的同步取值（AgentScope Java 基于响应式）。

<callout emoji="✅"><p>工程经验：`.maxIters` 不是越大越好。太大 = 烧钱风险高；太小 = 复杂任务做不完。生产里按任务复杂度分级：简单问答 3~5，多步任务 10~15，并配"超时"双保险。AgentScope 用迭代上限 + 你的超时控制共同兜底。</p></callout>

---

## 三、工具定义：@Tool 与自动注入上下文

AgentScope Java 的工具也用 `@Tool` 注解，并且支持**上下文自动注入**——这点比 Spring AI 更顺手。看天气工具：

```java
package com.example.ch04.agentscope;

import io.agentscope.tool.annotation.Tool;
import io.agentscope.tool.annotation.ToolParam;

public class WeatherTool {

    @Tool(name = "get_weather", description = "查询城市当前天气")
    public String getWeather(
            @ToolParam(name = "city", description = "城市名") String city) {
        // 真实场景调天气 API
        return city + "：晴，25℃";
    }
}
```

更妙的是 AgentScope 支持在工具方法里**自动注入上下文对象**（无需 `@ToolParam`），比如用户身份：

```java
import io.agentscope.tool.ToolContext;
import io.agentscope.tool.annotation.Tool;
import io.agentscope.tool.annotation.ToolParam;

public class OrderTool {

    @Tool(name = "query_order", description = "按订单号查状态")
    public String queryOrder(
            @ToolParam(name = "order_id") String orderId,
            UserContext ctx) {   // ← 自动注入，无需标注
        return "用户 " + ctx.getUserId() + " 的订单 " + orderId + "：已发货";
    }
}
```

`UserContext` 由框架从 `toolExecutionContext` 注入，是可信上下文（lesson05 的 ToolContext 同理）。这样工具既能拿模型给的参数，又能拿你注入的可信身份，做权限校验。

<callout emoji="⚠️"><p>和 Spring AI 的 `@Tool` 不是同一个类！AgentScope 用的是 `io.agentscope.tool.annotation.Tool`，Spring AI 用 `org.springframework.ai.tool.annotation.Tool`。两套注解不互通，混用会编译失败。换框架时别直接 copy 注解导入。</p></callout>

---

## 四、多 Agent 协作：msghub 与 Agent 互联

AgentScope 的强项是多 Agent。多个 `ReActAgent` 可以通过 `msghub`（消息中枢）互相发消息协作，对应 lesson01 的"消息传递"通信模型：

```java
package com.example.ch04.agentscope;

import io.agentscope.agent.ReActAgent;
import io.agentscope.agent.UserAgent;
import io.agentscope.msghub.MsgHub;

public class MultiAgentDemo {

    public static void main(String[] args) {
        ReActAgent researcher = ReActAgent.builder()
                .name("Researcher").model(model).toolkit(toolkit).build();
        ReActAgent writer = ReActAgent.builder()
                .name("Writer").model(model).build();
        UserAgent user = UserAgent.builder().name("用户").build();

        // msghub：把多个 agent 连到同一个消息总线
        try (MsgHub hub = new MsgHub("task-hub",
                List.of(researcher, writer, user))) {
            // 研究者先工作，再交给写作者，用户可介入
            researcher.call(user.call(null).block());
            writer.call(researcher.call(null).block());
        }
    }
}
```

`msghub` 让 Agent 之间共享消息流，谁该回应由消息的接收者决定——这就是 lesson01 说的"消息传递通信"。AgentScope 把这种模式封装得很自然，比手写 Graph 边更适合"松散协作"的场景。

---

## 五、ReAct vs Graph：什么时候用谁

这是面试常问。给一个清晰对照：

| 维度 | AgentScope ReActAgent | Spring AI Alibaba Graph |
|------|----------------------|------------------------|
| 控制方式 | 模型自主决定循环/工具 | 你用图显式定义流程 |
| 适合 | 开放式、步骤数未知的任务 | 流程固定、需强约束的业务 |
| 可控性 | 较低（模型驱动） | 高（图驱动，可审计） |
| 调试 | 难（黑盒循环） | 易（节点级可追踪） |
| 典型场景 | 研究/探索/创意生成 | 客服/对账/审批流水线 |

<callout emoji="💡"><p>求职导向：标准答案是"按可控性需求选"——要确定性、可审计、合规，用 Graph（中心化编排）；要自主性、探索性、步骤不固定，用 ReAct（如 AgentScope）。真实系统常二者结合：Graph 管大流程，某个节点里跑一个 ReActAgent 做子任务。这就是层次化（lesson09）。</p></callout>

---

## 六、企业场景：自动化工单处理

真实落地——客服工单Agent，接到"用户说订单没收到"，ReAct 循环自动：思考→查物流工具→观察"在途"→思考"需告知预计时间"→调通知工具→输出方案。

```java
ReActAgent ticketAgent = ReActAgent.builder()
        .name("TicketAgent")
        .sysPrompt("你是工单处理助手，优先用工具查证，再给用户方案。")
        .model(model)
        .toolkit(toolkit)   // 含 queryLogistics / sendNotify / createRefund
        .maxIters(8)
        .build();

Msg reply = ticketAgent.call(Msg.builder()
        .role(MsgRole.USER)
        .content(List.of(TextBlock.builder()
                .text("用户投诉订单 ORD-999 三天了还没收到，帮忙处理")
                .build()))
        .build()).block();
```

模型自己决定：先 `queryLogistics(ORD-999)` → 看结果在途 → 调 `sendNotify` 告知用户预计时间 → 输出安抚话术。全程你没写 if/else，是 ReAct 循环驱动的。这就是"Agent 自主干活"的体感。

<callout emoji="⚠️"><p>ReAct 自主性是把双刃剑：模型可能调不该调的工具（如误触发 refund）。生产里必须：① 工具按权限分级，高风险工具（退款/删数据）额外加确认或走 HITL（lesson05）；② 用 `maxIters` + 超时兜底；③ 全量记录每次 Thought/Action 用于审计回溯（第十二章安全治理）。</p></callout>

---

## 八、ReAct 的可观测：记录 Thought/Action

ReAct 是黑盒循环，不记录就完全不可调试。AgentScope 内部会产出每一步的 Thought/Action/Observation，生产里要把它落日志/接可观测：

```java
// 概念：给 ReActAgent 注册观察钩子，记录每步推理
ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .toolkit(toolkit)
        .maxIters(10)
        // 注入钩子：每产生一次 Thought/Action 就回调
        .registerInstanceHook("pre_reply", "log_step", (agentRef, msg) -> {
            System.out.println("[ReAct step] " + msg.getTextContent());
            return msg;
        })
        .build();
```

有了每步日志，badcase 复盘时你能看到"模型第 2 步调了错误工具""第 5 步陷入重复查询"——这是 ReAct 上生产的刚需（对应第十二章审计）。

<callout emoji="✅"><p>经验：ReAct 的日志要包含"调用了哪个工具、传了什么参、返回什么、模型怎么解读"。缺任何一环，复盘都只能靠猜。把 ReAct 的推理链当成"黑匣子数据"完整留存。</p></callout>

---

## 九、maxIters 与超时：双保险

lesson06 提过 `maxIters` 是安全阀。但 `maxIters` 只限制"迭代次数"，不限制"单次迭代时长"。若某次工具调用卡住 5 分钟，maxIters 拦不住。所以必须**双保险**：

```java
ReActAgent agent = ReActAgent.builder()
        .name("SafeAgent")
        .model(model)
        .toolkit(toolkit)
        .maxIters(8)                       // 限制迭代次数
        .timeout(java.time.Duration.ofSeconds(30))  // 限制整体/单步时长
        .build();
```

配合工具自身的超时（lesson05 讲过），形成"模型层 maxIters + 调用层 timeout + 工具层 timeout"的三层防护。

---

## 十、ReAct 与 Graph Loop 的代码对比

同样"生成-自审-重写"，ReAct 和 Graph Loop 写法不同，看清差异：

```
ReAct 写法：你只给 Agent + 工具，循环由模型自主决定
  agent.call(task)  → 模型自己想"先写再查再改"

Graph Loop 写法：你用条件边显式定义循环
  generate → review →(不达标)→ generate
```

| 维度 | ReAct | Graph Loop |
|------|-------|-----------|
| 谁决定循环 | 模型 | 你（边定义） |
| 可控性 | 低 | 高 |
| 适合 | 开放式探索 | 流程明确的自迭代 |

所以 lesson04 的 Loop 和 lesson06 的 ReAct 是"同一目标（循环执行）的两种控制哲学"。理解这点，选型不再纠结。

---

## 十一、工具调用失败恢复

ReAct 调工具可能失败（API 超时、参数错）。生产里 Agent 要能"失败后换个方式"而不是崩：

```java
public class RobustTool {
    @Tool(name = "query_order", description = "查订单，失败自动重试")
    public String queryOrder(@ToolParam(name = "order_id") String orderId) {
        for (int i = 1; i <= 3; i++) {
            try {
                return orderService.get(orderId);
            } catch (Exception e) {
                if (i == 3) return "查询失败，请稍后重试";
            }
        }
        return "";
    }
}
```

同时，若模型传参错误（如 orderId 格式不对），工具应返回清晰错误，让模型在下一步自我纠正——这是 ReAct "观察-再思考"循环的天然优势。

---

## 十二、面试自测

1. **ReAct 循环怎么终止？** —— 模型自己判断完成 + maxIters 上限兜底 + timeout。
2. **ReAct 黑盒怎么调试？** —— 记录每步 Thought/Action/Observation 落日志/可观测。
3. **和 Graph Loop 区别？** —— 模型自主 vs 显式边定义；可控性低 vs 高。
4. **工具失败怎么办？** —— 工具内重试 + 清晰错误返回让模型自我纠正。

---

## 十四、ReAct 与 Plan-and-Execute 的取舍

lesson06 讲了 ReAct，lesson08 讲了 PlanNotebook。两者常被对比，给个清晰结论：

| 范式 | 决策方式 | 可控性 | 适合 |
|------|---------|--------|------|
| ReAct | 模型自主循环，边走边想 | 低 | 开放式、步骤数未知 |
| Plan-and-Execute | 先完整规划再执行 | 高 | 复杂但可预判的任务 |

经验法则：
- 任务"步骤数未知、需要探索"（如"查完 A 看情况再决定查不查 B"）→ ReAct。
- 任务"复杂但步骤可预判"（如"生成报告=拉数→分析→写→审"）→ Plan-and-Execute（PlanNotebook）。
- 二者可结合：用 PlanNotebook 定大计划，某个 subtask 内部用 ReAct 自主探索。

<callout emoji="✅"><p>面试加分：能说出"ReAct 是 Plan-and-Execute 的特例——计划只有一步，边执行边修正"，说明你理解两种范式的内在统一，而非死记区别。</p></callout>

---

## 十五、ReAct 的流式输出

AgentScope 的 `ReActAgent.call(...).block()` 是同步取最终结果。生产里想让用户实时看到推理过程（打字机 + 逐步工具调用），用流式：

```java
// 概念：订阅 Agent 的消息流，逐步渲染
agent.stream(taskMsg)               // 返回响应式流
     .doOnNext(msg -> {
         if (msg.isThought()) System.out.print("[思考] " + msg.getTextContent());
         if (msg.isAction())  System.out.print("[调用] " + msg.getToolName());
         if (msg.isObservation()) System.out.print("[结果] " + msg.getTextContent());
     })
     .blockLast();                  // 等流结束
```

前端可把"思考/调用/结果"分别渲染成不同样式（如工具调用显示 loading 动画），体验远好于"干等 10 秒出结果"。这对 C 端 Agent 产品很重要。

---

## 十六、面试自测补充

1. **ReAct vs Plan-and-Execute？** —— 自主循环 vs 先规划再执行；按可控性需求选，可结合。
2. **ReAct 流化怎么给用户看？** —— 订阅消息流，分步渲染思考/调用/结果。
3. **maxIters 本质？** —— 防无限循环的安全阀，必须配 timeout 双保险。
4. **工具失败恢复？** —— 工具内重试 + 清晰错误让模型自我纠正。

---

## 十八、ReAct 的工具选择机制

ReAct 循环里，模型每步要从"可用工具集"里选一个调用。这个选择的依据是什么？是工具的 `name` + `description`（lesson06 提过描述的重要性）。给 AgentScope 工具写描述时，要站在"模型怎么选"的角度：

```java
// 好的描述：说清何时用、参数含义
@Tool(name = "query_order_status",
      description = "查询用户订单的物流/支付状态。当用户问'我的订单到哪了''为什么还没发货'时使用。参数 order_id 为订单号。")
public String queryOrder(@ToolParam(name = "order_id", description = "订单号，如 ORD-123") String id) { ... }

// 差的描述：模型看不懂何时用
@Tool(name = "tool1", description = "处理订单")
public String t1(String x) { ... }   // ❌ 太模糊
```

描述写得好，模型选对工具的概率高；写得模糊，模型要么不选、要么选错（lesson05 同理）。这是"提示词工程"在工具层的延伸。

<callout emoji="✅"><p>经验：工具描述要包含"触发场景 + 参数说明 + 产出"。写完用几个真实用户问句测一下"模型会不会选这个工具"，不会就改描述。工具描述是可迭代的工程资产，不是写完就扔。</p></callout>

---

## 十九、ReAct 与函数调用的关系

ReAct 的"Action"阶段本质是一次函数调用（Tool Calling，lesson05）。所以 ReAct 依赖底层模型支持工具调用。若模型不支持（或你想控成本），可退化成"纯文本 ReAct"——让模型在文本里输出 `Action: xxx` 格式，你解析执行。

```
模型不支持 tool calling 时的退化方案：
  Thought: 我要查天气
  Action: getWeather(北京)        ← 模型输出文本，你正则解析
  Observation: 晴 25℃            ← 你拼接回 prompt
  ...循环
```

但这种方式脆弱（模型格式易错），生产优先用原生 tool calling。这是 ReAct 能稳跑的前提。

---

## 二十、企业案例：IT 助手的自助排障

某公司的"IT 自助助手"用 ReActAgent：员工问"我连不上 VPN"，Agent 自主循环——思考→调 `check_vpn_status` 工具→观察"证书过期"→思考→调 `renew_cert` 工具→观察"已续期"→输出"已帮你续期证书，请重连"。

全程员工只问了一句，Agent 自主完成多步排障。这就是 ReAct 的价值：**把"多步操作"封装成"一句话委托"**。配合沙箱（lesson07）限制工具权限，既自主又安全。

---

## 二十二、ReAct 的终止信号与"幻觉式完成"

ReAct 有个隐患：模型可能"假装完成"——没有真正调工具拿到结果，就输出"已完成"。防范：

1. **强制证据**：系统提示词要求"未调用工具拿到数据前不得输出最终答案"。
2. **校验输出**：对最终答案做合理性检查（如退款金额必须来自工具返回）。
3. **maxIters 兜底**：到上限仍未"真完成"则转人工。

```java
// 概念：在 Agent 外层校验"答案是否基于工具结果"
String ans = agent.call(task).block().getTextContent();
if (!ans.contains(lastToolResultSnippet)) {
    // 模型没基于真实工具结果回答 → 触发重试或转人工
    return fallbackToHuman(task);
}
```

<callout emoji="⚠️️<p>"幻觉式完成"是 ReAct 生产常见坑：模型为了"结束循环"编一个看似合理的答案。防御靠"答案必须引用工具证据"的强约束 + 输出校验。这是 ReAct 比 Graph Loop 更需要小心的地方（Graph Loop 的产出可被结构化校验）。</p></callout>

---

## 二十三、ReAct 与结构化输出结合

lesson06 的 ReAct 输出是自由文本。若要确定性输出（如"分类结果+置信度"），可让 ReAct 最终调用一个"结构化收口工具"：

```java
// ReAct 在最后一步调用该工具，把结论以结构化形式落定
@Tool(name = "finalize", description = "输出最终结构化结论")
public ComplaintResult finalize(
        @ToolParam(description = "分类") String category,
        @ToolParam(description = "置信度0-1") Double confidence) {
    return new ComplaintResult(category, confidence);
}
```

模型在 ReAct 循环末尾调 `finalize`，把自由推理"收口"成 POJO（lesson04）。这把"ReAct 的灵活推理"和"结构化输出的确定性"结合，最适合"需要先探索再给确定结论"的任务。

---

## 二十四、企业案例：智能运维排障 Agent

某 SRE 用 ReActAgent 做"告警自助排障"：告警进来，Agent 自主循环——思考→调 `check_service_health`→观察"CPU 高"→思考→调 `get_top_process`→观察"进程X占满"→调 `restart_process`→观察"恢复"→输出结论。

全程无需人介入，平均 90 秒定位并缓解故障。配合沙箱（lesson07）限制其只能重启指定白名单进程，安全可控。这是 ReAct 在运维场景的硬价值。

---

## 二十五、小结

这一节我们看了 AgentScope Java 的 ReAct 范式：

1. ReAct = 思考(Thought)→行动(Action)→观察(Observation) 的循环，模型自主决定循环次数，是"智能版 Loop"。
2. AgentScope `ReActAgent.builder()` 声明式构建，`toolkit` 注册工具，`maxIters` 是安全阀。
3. 工具用 `io.agentscope.tool.annotation.@Tool`，支持可信上下文（如 `UserContext`）自动注入，做权限校验。
4. 多 Agent 用 `msghub` 消息总线互联，对应"消息传递"通信模型。
5. ReAct vs Graph：模型驱动 vs 图驱动；按可控性需求选，常结合使用。
6. 企业落地：工单 Agent 用 ReAct 自主"查物流→通知→安抚"，但高风险工具要加 HITL/权限。

下一节（lesson07）我们深入 AgentScope 的**安全沙箱与实时介入控制**——Agent 执行环境怎么隔离、人怎么在运行中紧急叫停。

---

## 延伸阅读 / 引用文档

1. AgentScope Java 官方文档（ReActAgent）：https://java.agentscope.io/v1/en/docs/agent/react.html
2. AgentScope Java 快速开始（Agent/Toolkit）：https://java.agentscope.io/zh/quickstart/agent.html
3. AgentScope 官网：https://agentscope.io
4. ReAct 论文（Yao et al., 2022）：https://arxiv.org/abs/2210.03629
5. AgentScope-Runtime（安全沙箱）：https://agentscope.io（Deploy & Tool SandBox 章节）
6. Spring AI Alibaba 对比参考：https://java2ai.com/docs/1.0.0.2/

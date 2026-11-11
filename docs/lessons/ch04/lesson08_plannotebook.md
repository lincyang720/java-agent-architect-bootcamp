# 第四章第8节：PlanNotebook：任务规划与状态跟踪的工具

大家好，我是秋天。

上一节我们给 Agent 套上了安全沙箱和介入控制。这一节讲一个让 Agent "更像个靠谱员工"的工具——**PlanNotebook（计划笔记本）**。核心问题：ReAct 虽然能自主循环，但它是"走一步看一步"，复杂任务容易做到一半忘了初衷、或者重复劳动。PlanNotebook 让 Agent 先**列计划、再执行、边做边更新状态**，把"野循环"变成"有纪律的执行"。

这节课讲什么、学完掌握什么：

- 讲什么：PlanNotebook 的定位（Agent 的任务规划与状态跟踪组件）、在 AgentScope Java 里的 `enablePlan()` 与 `PlanNotebook.builder()` 配置、自动注入的规划工具（建计划/改状态/完成子任务）、以及它和 lesson07 "介入控制"的衔接（needUserConfirm）。
- 学完掌握：你能给 `ReActAgent` 开启 PlanNotebook，让它面对复杂任务时自动建计划并跟踪；你理解"规划-执行-跟踪"为什么比纯 ReAct 更适合企业生产。

<callout emoji="💡"><p>招聘映射：JD 里"熟悉 Plan-and-Execute / Agent 任务规划 / 自主规划智能体"。PlanNotebook 是"让 Agent 可控地自主工作"的代表组件，也是 JManus 这类通用智能体的底层能力之一。能讲清"规划"对生产稳定性的价值，是高级 Agent 工程师的认知标志。</p></callout>

---

## 一、纯 ReAct 的痛点：走一步看一步

回顾 lesson06 的 ReAct：模型每轮"思考→行动→观察"，但它**不显式记录"我整体要干嘛"**。任务一复杂就有问题：

```
ReAct 无计划时：
  Thought: 先查A → Action: queryA → Observation: ...
  Thought: 再查B → Action: queryB → Observation: ...
  Thought: 哎刚才A的结果是啥来着？→ 重复 queryA（忘了）
  Thought: 我觉得差不多了 → 输出（其实漏了C）
```

症状：重复劳动、遗漏步骤、偏离目标。这在生产里叫"Agent 漂移"——跑着跑着忘了最初任务。

---

## 二、PlanNotebook 的解法：显式计划 + 状态跟踪

PlanNotebook 给 Agent 一个"笔记本"，让它把任务拆成子任务（subtask），每做完一个就更新状态。模型每轮推理前，系统自动把"当前计划进度"注入提示词，引导它聚焦未完成项。

```
┌──────────── PlanNotebook 工作流 ────────────┐
│  收到复杂任务                                 │
│       │                                      │
│       ▼                                      │
│  [create_plan] 拆成 subtask1/2/3             │
│       │                                      │
│       ▼                                      │
│  执行 subtask1 → [finish_subtask] 标记完成    │
│  执行 subtask2 → [finish_subtask] 标记完成    │
│  发现需调整   → [revise_current_plan] 改计划  │
│       │                                      │
│       ▼                                      │
│  [finish_plan] 全部完成 → 输出结果            │
└──────────────────────────────────────────────┘
```

每个 subtask 有状态：`todo`（待办）/ `in_progress`（进行中）/ `abandoned`（放弃）/ `done`（完成）。这把"隐式记忆"变成了"可观测的状态机"。

---

## 三、AgentScope Java 里开启 PlanNotebook

两种配置方式——快速默认、或自定义。

### 方式一：快速开启

```java
package com.example.ch04.plan;

import io.agentscope.agent.ReActAgent;
import io.agentscope.model.Model;
import io.agentscope.toolkit.Toolkit;

public class PlanQuickStart {

    public static ReActAgent build(Model model, Toolkit toolkit) {
        return ReActAgent.builder()
                .name("PlanAgent")
                .sysPrompt("你是系统性助手，处理复杂任务前先制定计划。")
                .model(model)
                .toolkit(toolkit)
                .enablePlan()          // ← 一行开启规划能力
                .build();
    }
}
```

`enablePlan()` 会自动给 Agent 注入一组规划工具：`create_plan`（建计划）、`revise_current_plan`（改计划）、`update_subtask_state`（改状态）、`finish_subtask`（完成子任务）、`view_subtasks`（看进度）、`finish_plan`（结束）。Agent 会按需自动调用，你不用手写。

### 方式二：自定义配置（推荐生产）

```java
package com.example.ch04.plan;

import io.agentscope.agent.ReActAgent;
import io.agentscope.task.PlanNotebook;
import io.agentscope.task.storage.InMemoryPlanStorage;

public class PlanCustom {

    public static ReActAgent build(Model model, Toolkit toolkit) {
        // 自定义：最多 10 子任务，需用户确认后再执行，内存存储
        PlanNotebook planNotebook = PlanNotebook.builder()
                .maxSubtasks(10)              // 限制子任务数量，防爆炸
                .needUserConfirm(true)        // 建完计划等人确认再执行
                .storage(new InMemoryPlanStorage())  // 计划持久化（生产换DB）
                .planToHint(new DefaultPlanToHint()) // 计划→提示词注入策略
                .build();

        return ReActAgent.builder()
                .name("PlanAgent")
                .model(model)
                .toolkit(toolkit)
                .planNotebook(planNotebook)
                .build();
    }
}
```

两个关键配置：
- `maxSubtasks(10)`：限制计划规模，防止模型把任务拆成 50 个子任务拖垮执行。
- `needUserConfirm(true)`：建完计划先给人看，人确认后才执行——这就是 lesson07 讲的"人工确认"介入，直接内置在 PlanNotebook 里。

<callout emoji="✅"><p>工程经验：`needUserConfirm` 是 PlanNotebook 最实用的安全开关。复杂任务（如"重构这段代码""给全量用户发通知"）让人在执行前看一眼计划，能挡掉绝大多数"Agent 理解错意图"的事故。这是"规划 + 介入"的天然结合点。</p></callout>

---

## 四、完整示例：复杂任务自动规划

给 Agent 一个多步任务，看它怎么用 PlanNotebook：

```java
package com.example.ch04.plan;

import io.agentscope.agent.ReActAgent;
import io.agentscope.message.Msg;
import io.agentscope.message.MsgRole;
import io.agentscope.message.TextBlock;

import java.util.List;

public class PlanExample {

    public static void main(String[] args) {
        ReActAgent agent = PlanCustom.build(model, toolkit);

        Msg task = Msg.builder()
                .role(MsgRole.USER)
                .content(List.of(TextBlock.builder()
                        .text("构建一个简单的计算器网页，包含 HTML、CSS 和 JavaScript。")
                        .build()))
                .build();

        // Agent 会自动：建计划(设计UI/写HTML/写CSS/写JS/联调)
        // → 逐个 finish_subtask → finish_plan
        Msg response = agent.call(task).block();
        System.out.println(response.getTextContent());
    }
}
```

执行中，Agent 的"笔记本"大致长这样（可视化）：

```
计划：构建计算器网页
  [done]      设计 UI 布局
  [done]      编写 HTML 结构
  [in_progress] 编写 CSS 样式
  [todo]      编写 JS 交互逻辑
  [todo]      本地联调测试
```

每次推理前，系统把这段进度注入提示词，引导 Agent 聚焦 `[in_progress]` 和 `[todo]`，不再"走一步看一步"。这就是 PlanNotebook 消除"Agent 漂移"的机制。

---

## 五、PlanNotebook 与状态跟踪的工程价值

为什么生产里 Plan-and-Execute 比纯 ReAct 更受欢迎？三点：

1. **可观测**：计划进度是显式的，运维/用户能看到 Agent "做到哪了"，而不是黑盒等待。
2. **可恢复**：长任务中断后，从 `PlanNotebook` 的存储里读回计划状态，从断点续跑（对应第十七章 Durable Execution）。
3. **可干预**：`needUserConfirm` 让人在关键节点介入；`revise_plan` 允许中途调整——比 ReAct 的"全自主"可控得多。

<callout emoji="⚠️"><p>存储别用 `InMemoryPlanStorage` 上生产。进程一重启，计划丢了，长任务无法续跑。生产换持久化存储（数据库/Redis），配合 `threadId` 隔离不同会话的计划。这是可恢复性的前提。</p></callout>

---

## 六、与企业场景：数字员工的"工作日志"

真实落地——某企业的"数字员工"每天处理"生成季度经营分析报告"这种长任务。没有 PlanNotebook 时，它常做到一半跑偏或漏环节；有了之后：

```
计划：生成Q3经营分析报告
  [done]   拉取各业务线销售数据
  [done]   计算同比增长/环比
  [in_progress] 撰写风险章节（调用风控Agent）
  [todo]   生成图表
  [todo]   汇总成PPT
```

管理层能在后台实时看到进度条，哪一步卡住一目了然；执行中如果老板说"先不生成PPT了"，Agent 调 `revise_plan` 删掉该 subtask 即可。这体验，才是"数字员工"该有的样子，而不是"丢个任务然后祈祷它能搞定"。

<callout emoji="💡"><p>求职导向：能对比"纯 ReAct（灵活但易漂移）vs Plan-and-Execute（有纪律、可观测、可恢复）"，并点出 PlanNotebook 的 `needUserConfirm` 与 lesson07 介入控制、`storage` 与第十七章持久化的关系，说明你理解 Agent 工程的"稳定性设计"，而不只是会调 API。</p></callout>

---

## 八、PlanNotebook 与 Graph 子图的对应

lesson02 讲了 Graph 子图（subgraph），PlanNotebook 在理念上类似：一个 Agent 接收"任务"，内部把它拆成多个 subtask 按顺序/并行执行——这本身就是一张"微缩 Graph"。区别：子图是你显式用 `StateGraph` 画的，PlanNotebook 是模型在运行时动态生成的"软图"。

```
显式 Graph 子图：你画好 A→B→C，固定
PlanNotebook ：模型动态建 plan：sub1→sub2→sub3，可中途 revise
```

所以：**流程确定用 Graph 子图（可控）；流程不确定但需规划用 PlanNotebook（灵活）。** 两者都解决"复杂任务拆解"，只是控制权属不同。

---

## 九、revise_plan：中途调整计划

PlanNotebook 不只是"建完就执行"，还支持中途修改。比如执行中发现"原计划的 C 步骤前提不满足"，Agent 调 `revise_current_plan` 删掉或替换该 subtask：

```java
// 概念：PlanNotebook 暴露的规划工具（自动注入）
// create_plan      : 建计划
// revise_current_plan : 改计划（增/删/改 subtask）
// update_subtask_state: 改某 subtask 状态
// finish_subtask  : 标记完成
// view_subtasks   : 查看进度
// finish_plan     : 结束
```

生产里这很有用：老板中途说"先别做 PPT 了"，Agent 调 revise 把对应 subtask 标 abandoned，继续执行其余——无需重启任务。

<callout emoji="✅"><p>经验：`maxSubtasks` 限制计划规模，但 `revise` 不受此限——要防止 Agent 在 revise 时无限加 subtask。生产里对 revise 也做次数/规模上限，或在 revise 时强制 needUserConfirm。</p></callout>

---

## 十、持久化存储选型

lesson08 提过别用 `InMemoryPlanStorage` 上生产。持久化选型：

| 存储 | 适用 | 特点 |
|------|------|------|
| Redis | 短任务、需快速读写 | 快、可设 TTL 自动过期 |
| PostgreSQL | 长任务、需强一致 | 可靠、易查询、易审计 |
| 对象存储/S3 | 超大计划（长文档） | 便宜、海量 |

```java
// 概念：换成 Redis 持久化（生产推荐）
PlanNotebook notebook = PlanNotebook.builder()
        .maxSubtasks(10)
        .needUserConfirm(true)
        .storage(new RedisPlanStorage(redisTemplate))  // 持久化
        .build();
```

配合 `threadId`，长任务中断后从 Redis 读回计划状态续跑（对应第十七章 Durable Execution）。

---

## 十一、与 HITL 的协同：needUserConfirm

PlanNotebook 的 `needUserConfirm(true)` 本质是内置了 lesson05 的 HITL——建完计划先给人确认。这是"规划 + 介入"的天然结合：

```
Agent 建计划(10个subtask)
   │
   ▼
[needUserConfirm] → 暂停，展示计划给人
   人点"确认" → 执行
   人点"修改" → Agent revise_plan → 再确认
```

对"生成报告/重构代码/批量操作"这类高风险长任务，这个闸门能挡掉绝大多数"理解错意图"的事故。

---

## 十二、面试自测

1. **纯 ReAct 的痛点？** —— 走一步看一步，易重复/遗漏/漂移。
2. **PlanNotebook 解决什么？** —— 显式计划 + subtask 状态机，消除漂移、可观测可恢复。
3. **和 Graph 子图区别？** —— 子图固定你画；PlanNotebook 模型动态生成。
4. **存储为什么别用内存？** —— 进程重启丢计划，长任务无法续跑。

---

## 十四、PlanNotebook 的执行时序图

把 PlanNotebook 的一次完整运行画成时序，看清"模型、笔记本、工具"三者交互：

```
用户 ──▶ Agent: 复杂任务
Agent ──▶ Notebook: create_plan
Notebook ──▶ Agent: 计划(5个subtask) + [needUserConfirm? 等人确认]
Agent ──▶ 工具: 执行 subtask1
工具 ──▶ Agent: 结果
Agent ──▶ Notebook: finish_subtask(1) + view_subtasks(看进度)
Agent ──▶ 工具: 执行 subtask2
... (中途可 revise_plan 调整)
Agent ──▶ Notebook: finish_plan
Agent ──▶ 用户: 最终输出
```

关键点：每次执行后 Agent 都 `finish_subtask` 更新状态，下次推理前 Notebook 把进度注入 prompt——这就是"不漂移"的机制保证。

---

## 十五、PlanNotebook 的失败恢复实战

长任务执行到 subtask3 时进程重启，怎么办？靠持久化存储 + threadId：

```java
// 概念：重启后从存储读回计划状态，从断点续跑
PlanNotebook notebook = PlanNotebook.builder()
        .maxSubtasks(10)
        .storage(new RedisPlanStorage(redisTemplate))
        .build();

ReActAgent agent = ReActAgent.builder()
        .name("RecoveryAgent")
        .model(model)
        .toolkit(toolkit)
        .planNotebook(notebook)
        .build();

// 用同一 threadId 重新 invoke，Agent 读到"已完成 subtask1/2"
// 自动从 subtask3 继续，不重复执行已完成部分
Msg r = agent.call(taskMsg).block();  // taskMsg 带相同会话标识
```

这把 lesson08 的"可恢复"落到代码层面——持久化让 Agent 具备"断点续跑"能力，是生产长任务的刚需。

<callout emoji="💡"><p>求职导向：能讲出"PlanNotebook 的持久化 + threadId 实现断点续跑"，说明你理解 Agent 工程的"可靠性设计"，而不只是会调 API。这直接对应第十七章 Durable Execution，是高级岗考察点。</p></callout>

---

## 十六、面试自测

1. **PlanNotebook 消除漂移的机制？** —— 显式计划 + 每步 finish_subtask 更新状态 + 进度注入 prompt 引导聚焦。
2. **needUserConfirm 干嘛？** —— 建完计划等人确认再执行，内置 HITL。
3. **怎么断点续跑？** —— 持久化存储 + threadId，重启后从已完成 subtask 继续。
4. **和 Graph 子图区别？** —— 子图固定你画；PlanNotebook 模型动态生成软图。

---

## 十八、PlanNotebook 配置全参数详解

lesson08 用了 `PlanNotebook.builder()` 的几个参数，这里把常用配置讲全：

| 参数 | 作用 | 生产建议 |
|------|------|---------|
| `maxSubtasks` | 限制子任务数 | 5~15，防计划爆炸 |
| `needUserConfirm` | 执行前等人确认 | 高风险任务=true |
| `storage` | 持久化存储 | Redis/PG，禁用内存 |
| `planToHint` | 进度→提示词注入策略 | 默认即可，复杂可定制 |
| `autoParse` | 自动从模型输出解析计划 | 默认开启 |

```java
PlanNotebook notebook = PlanNotebook.builder()
        .maxSubtasks(12)
        .needUserConfirm(true)
        .storage(new RedisPlanStorage(redisTemplate))
        .planToHint(new DefaultPlanToHint())   // 把计划进度注入每轮提示词
        .build();
```

<callout emoji="💡"><p>关键点：`planToHint` 决定了"计划进度怎么被模型看到"。默认策略是把未完成 subtask 列在提示词里引导聚焦。如果你的任务 subtask 很多，可定制"只显示当前 in_progress + 下 2 个 todo"，进一步省 token。</p></callout>

---

## 十九、PlanNotebook 与 Supervisor 协同（层次化）

PlanNotebook 通常不是孤立用的，而是嵌在更大结构里。例如 Supervisor 模式下，总控把"写季度报告"任务交给一个带 PlanNotebook 的 ReportAgent：

```
Supervisor
  ├─ 路由：判断"写报告" → 交给 ReportAgent
  └─ ReportAgent 内部用 PlanNotebook：
        plan: [拉数据][分析][写章][画图][汇总]
        逐 subtask 执行，人可在 needUserConfirm 介入
```

这就是层次化（lesson09）：Supervisor 管"分给谁"，PlanNotebook 管"被分到的 Agent 内部怎么有条理地干"。两者职责清晰、互补。

---

## 二十、企业案例：周报 Agent 的 Plan 实战

某团队"自动周报 Agent"用 PlanNotebook 的真实计划：

```
计划：生成张三的本周工作周报
  [done]       拉取 git 提交记录
  [done]       拉取 Jira 任务状态
  [in_progress] 归类到"需求/开发/测试/运维"
  [todo]       撰写各模块小结
  [todo]       生成下周计划建议
  [abandoned]  生成 PPT 版（老板说先不要）
```

注意 `abandoned` 那条——执行中老板说"先不要 PPT"，Agent 调 `revise_plan` 把它标记放弃，其余正常推进。这种"运行时灵活调整"正是 PlanNotebook 比固定流程强的地方。

---

## 二十一、面试自测补充

1. **PlanNotebook 常用配置？** —— maxSubtasks 限规模、needUserConfirm 加闸门、storage 持久化、planToHint 注入策略。
2. **和 Supervisor 协同？** —— Supervisor 管分发，PlanNotebook 管被分 Agent 内部规划，层次化互补。
3. **revise 实战？** —— 中途可 abandon/增删 subtask，无需重启任务。
4. **为什么不用内存存储？** —— 重启丢计划，长任务无法续跑。

---

## 二十三、PlanNotebook 的 subtask 状态机详解

lesson08 提到 subtask 状态，这里把状态机讲清，便于调试：

```
        create_plan
            │
            ▼
   ┌─── todo ───┐
   │            │ start
   ▼            ▼
 in_progress ──(done)──▶ done
   │
   │ abandon
   ▼
 abandoned
```

- `todo`：待办，未开始。
- `in_progress`：进行中（同时应有且仅有 1 个，避免并发混乱）。
- `done`：完成。
- `abandoned`：放弃（如老板说不要了，lesson08 案例）。

系统每轮只把 `in_progress` 和前几个 `todo` 注入提示词，引导 Agent 聚焦。状态机清晰，进度可视化才准。

<callout emoji="✅"><p>工程经验：监控"长期 in_progress 不推进"的 subtask——说明 Agent 卡在某步（可能工具失败或模型不会）。设超时自动 abandon + 告警，别让它永远 in_progress 占着进度条。</p></callout>

---

## 二十四、PlanNotebook 与上下文压缩（衔接第十五章）

PlanNotebook 的进度注入会占用上下文窗口。subtask 多了（如 50 个），每轮都注入全部状态会撑爆窗口（上下文腐烂，第十五章）。解法：

- **只注入活跃部分**：`in_progress` + 接下来 2~3 个 `todo`，已完成的不重复注入。
- **已完成 subtask 做摘要**：不保留完整中间结果，只留一句话结论。
- **超长任务分 Checkpoint**：每 N 个 subtask 做一次整体摘要压缩。

这正是"上下文工程"里 Write/Select/Compress 策略在 PlanNotebook 的体现。

---

## 二十五、面试自测再补充

1. **subtask 状态机？** —— todo / in_progress / done / abandoned，每轮聚焦活跃项。
2. **PlanNotebook 与上下文腐烂？** —— 只注入活跃项 + 已完成项摘要 + Checkpoint 压缩。
3. **怎么断点续跑？** —— 持久化存储 + threadId。
4. **和 Graph 子图区别？** —— 子图固定你画；PlanNotebook 模型动态生成软图。

---

## 二十六、小结

这一节我们给 Agent 配上了"工作计划本"：

1. 纯 ReAct 痛点：走一步看一步，易重复劳动、遗漏步骤、目标漂移。
2. PlanNotebook 解法：显式计划 + subtask 状态机（todo/in_progress/done/abandoned），计划进度注入提示词引导聚焦。
3. 使用：`enablePlan()` 快速开；`PlanNotebook.builder().maxSubtasks().needUserConfirm().storage()` 自定义。
4. 自动注入规划工具：create_plan / revise_current_plan / finish_subtask / finish_plan 等。
5. 工程价值：可观测（看进度）、可恢复（持久化续跑）、可干预（needUserConfirm + revise）。
6. 生产注意：存储换持久化、配合 threadId；企业落地即"数字员工工作日志"。

下一节（lesson09）我们收官这一章——把前面所有模式（Supervisor/Sequential/Loop/HITL/ReAct/Plan）做一次**多 Agent 设计模式总结**，对比中心化、去中心化、层次化控制的优劣，给你一张"选型地图"。

---

## 延伸阅读 / 引用文档

1. AgentScope Java PlanNotebook 文档：https://java.agentscope.io/v1/en/docs/task/plan.html
2. AgentScope Java 智能体（ReActAgent + Plan）：https://java.agentscope.io/zh/quickstart/agent.html
3. AgentScope PlanNotebook Web 示例（含 SSE 流式）：https://github.com/agentscope-ai/agentscope-java
4. Plan-and-Execute 论文思路：https://arxiv.org/abs/23011.0156 （注：编号请核实原文）
5. Spring AI Alibaba JManus（自主规划智能体，基于 Graph）：https://github.com/alibaba/spring-ai-alibaba
6. Anthropic Building Effective Agents（规划型 Agent）：https://www.anthropic.com/research/building-effective-agents

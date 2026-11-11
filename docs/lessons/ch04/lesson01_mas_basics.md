# 第四章第1节：多 Agent 协作基础：通信协议、任务分发、状态同步的通用模型

大家好，我是秋天。

单个 Agent 再强，也有天花板：它一次只能专注一件事，上下文窗口有限，专业领域知识有边界。真实企业问题往往是复合的——"帮我做一次季度财务分析"背后要拆成"拉数据、做清洗、画图表、写结论、审合规"，没有任何一个 Agent 能从头到尾搞定。

所以这一章我们进入**多 Agent 协作（Multi-Agent System, MAS）**。这一节先打地基：不讲任何具体框架，先把"多个 Agent 怎么协作"这件事的通用模型讲透——通信协议、任务分发、状态同步。框架（Graph / AgentScope）只是这些通用模型的具体实现。

这节课讲什么、学完掌握什么：

- 讲什么：多 Agent 系统的三种拓扑（集中式/去中心化/层次化，lesson09 细对比）、Agent 间通信协议（消息传递 / 共享黑板 / A2A）、任务分发策略、状态同步的难点。
- 学完掌握：你能用"通用模型"描述任何一个多 Agent 框架在干什么；你能指出"通信协议选错"会带来的具体问题；你理解为什么"状态同步"是多 Agent 最大的坑。

<callout emoji="💡"><p>招聘映射：JD 里"熟悉多智能体编排""ReAct / Supervisor / 顺序循环编排""中心化/去中心化/层次化控制"——这一节是这些术语的"元认知"。面试官问"多 Agent 怎么协作"，你能从通用模型讲到具体模式，就是高级选手。</p></callout>

---

## 一、为什么要多 Agent：单 Agent 的三个天花板

```
单 Agent 天花板：
┌─────────────────────────────────────┐
│ 1. 上下文窗口有限：塞不下全公司知识   │
│ 2. 能力冲突：既当客服又当会计易混乱   │
│ 3. 无法并行：一件事一件事串行做       │
└─────────────────────────────────────┘
        │  解法：拆成多个专职 Agent
        ▼
多 Agent：
[数据Agent] [分析Agent] [审核Agent] [报告Agent]
    │           │            │           │
    └──── 协作 ──┴────────────┴───────────┘
```

拆分的收益：每个 Agent 职责单一、提示词精简、上下文干净、可独立迭代、可并行。代价是——你得解决"它们怎么协作"。这就是本章的主题。

---

## 二、Agent 间通信协议：三种主流模型

多 Agent 协作的第一问题是"它们怎么说话"。业界有三种主流通信模型：

### 模型 1：消息传递（Message Passing）

Agent 之间直接发消息，类似 Actor 模型。A 把任务结果发给 B，B 处理后发给 C。AgentScope 的 `msghub`、ReActAgent 的 `Msg` 就是这种。

```
A ──Msg("数据已就绪")──▶ B ──Msg("分析完成")──▶ C
```

优点：解耦、异步、天然支持并行。缺点：消息格式需约定，容易"对不上暗号"。

### 模型 2：共享黑板（Blackboard / Shared State）

所有 Agent 读写同一份共享状态（黑板），不直接互相发消息，而是"看黑板"。Spring AI Alibaba Graph 的 `OverAllState` 就是这种——节点们共享一份状态字典。

```
        ┌──── 共享状态 OverAllState ────┐
   A ──▶│ input / summary / decision   │◀── B
   C ──▶│                              │◀── D
        └──────────────────────────────┘
```

优点：状态集中、易调试、易持久化。缺点：需要并发控制（谁先写谁后写），状态设计不好会变成"全局变量地狱"。

### 模型 3：A2A 协议（Agent-to-Agent，标准化）

Google 推的开放协议，让不同框架、不同厂商的 Agent 能互相发现、调用。类比微服务里的 HTTP/gRPC——A2A 是 Agent 的"标准通信协议"。

```
Agent A (Spring AI) ──A2A──▶ Agent B (LangChain/Python) ──▶ ...
```

<callout emoji="✅"><p>选型直觉：同一进程内、强一致流程 → 共享状态（Graph 的 OverAllState）；跨进程、松耦合、要并行 → 消息传递（msghub）；跨厂商/跨框架 → A2A 标准协议。第十一章会专门讲 MCP 与 A2A。</p></callout>

---

## 三、任务分发：谁来决定"谁干什么"

多个 Agent 摆在那，怎么把任务分下去？三种分发策略：

### 策略 1：主控分发（Centralized / Supervisor）

一个 Supervisor Agent 看任务，决定分给哪个子 Agent。这是 lesson03 要讲的 Supervisor 模式。

```
        ┌── Supervisor ──┐
        │  任务路由器      │
        └──┬───┬───┬───┘
           ▼   ▼   ▼
         [A] [B] [C]   子 Agent 不直接互联，全听 Supervisor
```

### 策略 2：广播/流水线（Sequential / Pipeline）

任务按固定顺序流过每个 Agent，上一个的输出是下一个的输入。lesson04 的 SequentialAgent 就是它。

```
输入 ─▶ A ─▶ B ─▶ C ─▶ 输出   （线性流水线）
```

### 策略 3：协商/去中心化（Decentralized）

Agent 之间自主协商谁接活，没有中心节点。适合开放式、动态任务，但最难控制和调试。

```
A ◀──▶ B ◀──▶ C   互相直接通信，自组织
```

<callout emoji="⚠️"><p>生产里 90% 的场景用策略 1 或 2。策略 3 看着酷，但出了 bug 你根本不知道是哪个 Agent 疯了。可控性优先，别为了"去中心化"的浪漫牺牲可观测性。</p></callout>

---

## 四、状态同步：多 Agent 最大的坑

多 Agent 协作最难的不是"让它们干活"，而是"让它们对同一件事有一致的认知"。这就是状态同步问题。

典型坑：
1. **写冲突**：Agent A 和 B 同时改共享状态，后写的覆盖先写的，数据丢了。
2. **脏读**：Agent B 读了 A 还没写完的中间状态，基于错误前提行动。
3. **状态膨胀**：每个 Agent 都往共享状态塞东西，上下文爆炸（对应第十五章"上下文腐烂"）。
4. **死锁/活锁**：A 等 B、B 等 A，谁都不动。

Spring AI Alibaba Graph 的解法是用"通道（Channel）+ 归约器（Reducer）"管理状态：每个状态键定义自己的合并策略，比如消息列表用"追加"而非"覆盖"。

```
状态 key="messages"
  写入1: [m1]   写入2: [m2]
  Reducer=APPEND → 合并后: [m1, m2]   ✅ 不丢
  （若用覆盖策略 → 只剩 [m2] ❌）
```

AgentScope Java 用 `Memory`（如 `InMemoryMemory`）做每 Agent 的私有记忆，用 `msghub` 做 Agent 间消息总线，从机制上隔离"私有状态"和"共享消息"。

<callout emoji="💡"><p>工程经验：状态同步的设计原则——① 私有状态放 Agent 自己（Memory），共享状态才进黑板；② 共享状态用带归约策略的通道，避免覆盖；③ 状态要能序列化、能持久化（长任务断点续跑）。这三条决定了你的多 Agent 系统能不能上生产。</p></callout>

---

## 五、一个通用多 Agent 系统的骨架（伪代码）

不管用哪个框架，多 Agent 系统的骨架都长这样。我用伪代码把"通用模型"固化下来，后面每节课的具体框架都是它的实例化：

```java
// 通用多 Agent 协作骨架（非特定框架，表达通用模型）
interface Agent {
    // 每个 Agent 接收输入，产出输出；可访问共享状态
    AgentResult run(AgentInput input, SharedState state);
}

// 1. 通信：共享状态（也可换成消息队列）
class SharedState {
    Map<String, Object> store = new ConcurrentHashMap<>();
    // 带归约策略的写，避免覆盖冲突
    void write(String key, Object value, Reducer reducer) { ... }
    Object read(String key) { ... }
}

// 2. 任务分发：编排器决定谁先谁后
interface Orchestrator {
    List<Agent> plan(Task task);   // 拆任务、排顺序
    void dispatch(List<Agent> agents, SharedState state);
}

// 3. 控制流：顺序 / 循环 / 条件
enum Flow { SEQUENTIAL, LOOP, CONDITIONAL }

// 具体实现：Supervisor / Sequential / Graph 都是对上面的填充
```

`Spring AI Alibaba Graph` 把 `SharedState` 实现为 `OverAllState`、`Orchestrator` 实现为"图的结构 + 条件边"、`Agent` 实现为"节点 NodeAction"。`AgentScope` 把 `Agent` 实现为 `ReActAgent`、`SharedState` 实现为 `msghub + Memory`。**框架在变，通用模型不变**——这就是本节要你带走的认知。

---

## 六、企业场景：数字员工团队的协作

落到真实场景——某银行的"数字员工中台"，一组 Agent 协作完成一笔对公贷款初审：

```
┌──────────── 贷款初审多 Agent 系统 ────────────┐
│                                                │
│  [接收Agent] 解析客户材料                       │
│       │                                        │
│       ▼                                        │
│  [征信Agent] 查央行征信 + 内部信用分            │
│       │                                        │
│       ▼                                        │
│  [风控Agent] 规则引擎 + 模型打分                │
│       │                                        │
│       ▼                                        │
│  [合规Agent] 检查监管红线（反洗钱等）           │
│       │                                        │
│       ▼                                        │
│  [终审Agent/Supervisor] 汇总 → 通过/拒/转人工   │
└────────────────────────────────────────────────┘
```

这就是"流水线 + 主控终审"的混合。每个 Agent 是专职数字员工，状态（客户材料、征信结果、风控分）在共享状态里流转，终审 Agent 基于全量状态做决策。现实中跑在 Spring AI Alibaba Graph 上，节点=Agent，边=流程，条件边=风控不达标转人工。

<callout emoji="💡"><p>求职导向：能举出"数字员工团队"这种落地案例，并说清"为什么拆成多个 Agent 而不是一个"，你在面试里就展现了"架构思维"。企业招 Agent 工程师，要的是能设计协作系统的人，不是会调一个 API 的人。</p></callout>

---

## 八、消息传递模型：代码示例

前面讲了三种通信模型，这里给"消息传递"一个最小代码骨架（框架无关的通用表达），帮你建立肌肉记忆：

```java
// 通用消息传递骨架（表达 lesson01 的"消息模型"）
interface Agent {
    void onMessage(Message msg, MessageBus bus);
}

class MessageBus {
    // 简单的发布-订阅：Agent 注册后收消息
    Map<String, Agent> subscribers = new java.util.concurrent.ConcurrentHashMap<>();

    void publish(Message msg) {
        Agent target = subscribers.get(msg.to());
        if (target != null) target.onMessage(msg, this);
    }
}

record Message(String from, String to, Object payload) {}

// A 处理完发给 B
class AgentA implements Agent {
    public void onMessage(Message msg, MessageBus bus) {
        Object result = "处理:" + msg.payload();
        bus.publish(new Message("A", "B", result));  // 直接发给 B
    }
}
```

这个骨架对应 AgentScope 的 `msghub`：Agent 往总线发 `Msg`，目标 Agent 接收处理。解耦、异步、天然支持并行。

---

## 九、共享黑板模型：代码示例

"共享黑板"对应 Graph 的 `OverAllState`，这里给最小骨架：

```java
// 通用共享状态骨架（对应 Graph OverAllState + Reducer）
class SharedState {
    Map<String, Object> store = new java.util.concurrent.ConcurrentHashMap<>();

    // 带归约策略的写：避免多 Agent 并发覆盖
    void write(String key, Object value, Reducer reducer) {
        store.merge(key, value, (oldV, newV) -> reducer.apply((List<?>) oldV, (List<?>) newV));
    }
    Object read(String key) { return store.get(key); }
}

interface Reducer {
    Object apply(List<?> current, List<?> updates);
}
```

注意 `store.merge` + `Reducer` 就是 Graph 通道归约的朴素实现——多个节点写同一 key 时，不是"后者覆盖前者"，而是"按归约策略合并"（如列表追加）。这正是 lesson02 讲的机制。

<callout emoji="✅"><p>把两个骨架放在一起看，你会发现自己已经"手写"了一遍 Graph 和 msghub 的核心。框架只是把这些通用模型工程化、加上持久化/可观测。理解了 lesson01 的通用模型，学任何框架都快。</p></callout>

---

## 十、状态同步的并发控制深入

多 Agent 并行写共享状态时，光有归约还不够。几个进阶要点：

1. **写顺序无关性**：归约函数必须满足交换律/结合律（如列表追加），否则并发结果不确定。
2. **读已提交**：Agent 读状态应在"上一个节点完全写完"之后，Graph 用节点依赖保证（A→B，B 读到的一定是 A 写完的）。
3. **大状态拆分**：别把所有东西塞一个 Map，按领域拆 key（如 `finance_*`、`cust_*`），减少冲突面。
4. **不可变快照**：长任务每隔几步对状态做快照，便于回滚/审计（对应第十七章）。

<callout emoji="⚠️"><p>归约函数写错是隐蔽 bug：比如用了"求和"归约但某节点重复执行，结果被加两次。生产里给归约函数写单元测试，验证"重复/乱序写入结果一致"。</p></callout>

---

## 十一、A2A 与 MCP：跨框架通信预览

lesson01 提了 A2A 是"跨框架标准协议"。这里补一句定位：A2A 解决"Agent 之间怎么对话"，MCP 解决"Agent 怎么连工具/数据源"（第十一章专讲）。关系：

```
Agent A (Spring AI) ──A2A──▶ Agent B (LangChain)
   │                          │
   └──── MCP ────▶ 工具/数据库 ◀── MCP ────┘
```

企业内部若有"Java 栈 Agent"和"Python 栈 Agent"混编，A2A 让它们互操作，不必强求同一框架。这是"多 Agent"从单框架走向企业级生态的关键。

---

## 十二、面试自测

1. **三种通信模型？** —— 消息传递（解耦并行）、共享黑板（集中易调）、A2A（跨框架标准）。
2. **状态同步最大坑？** —— 写冲突/脏读/膨胀/死锁；解法=私有状态隔离+带归约共享通道+持久化。
3. **任务分发策略？** —— 主控(Supervisor)/流水线(Sequential)/去中心化协商。
4. **通用骨架？** —— Agent + SharedState + Orchestrator + Flow，框架是其实例化。

---

## 十四、多 Agent 的测试与评估

多 Agent 系统比单 Agent 难测，因为行为有不确定性（模型决策）。测试策略分层：

```
① 单元层：每个 Agent/节点是纯函数（输入状态→输出状态），可单测
② 集成层：用 Mock Model 固定输出，验证编排流（router 是否分到对的 Agent）
③ 评估层：用评测集跑端到端，看最终效果（第十六章 EvalScope/RAGAS）
```

```java
// 概念：用 Mock ChatModel 固定 router 输出，验证分发逻辑
@Test
void should_route_to_finance() {
    ChatModel mock = new FixedOutputModel("财务");  // 恒返回"财务"
    SupervisorGraph g = build(mock);
    OverAllState r = g.invoke(Map.of("input", "退款多少"), config).get();
    assertEquals("finance", r.value("domain").get());
}
```

把"模型决策"用 Mock 固定后，编排逻辑就变成确定性可测的——这是多 Agent 工程化的基本功。

<callout emoji="✅"><p>经验：别试图端到端测试"模型会不会随机选错 Agent"，那是评估层的事。单元/集成层只验证"我的编排代码对不对"，用 Mock 把模型不确定性隔离掉。</p></callout>

---

## 十五、常见反模式

多 Agent 设计常见坑：

1. **过度拆分**：把本可一个 Agent 干的拆成 5 个，通信开销 > 收益。→ 先单 Agent，不够再拆。
2. **无状态设计混乱**：把本该私有的状态塞进共享黑板，造成耦合。→ 私有状态放 Agent 自己。
3. **去中心化滥用**：为酷而用去中心化，结果不可控。→ 默认中心化。
4. **无上限循环**：Loop/ReAct 没 maxIters，烧钱。→ 永远配上限。
5. **忽视审计**：多 Agent 动作不记录，出事查不到。→ 全链路审计。

---

## 十六、求职导向小结

面试被问"你设计过多 Agent 吗"，最佳结构：
- **先判断拓扑**：按业务特征选中心化/层次化/去中心化。
- **再讲安全**：HITL + 沙箱 + 审计兜底。
- **最后举证**：一个落地案例（客服总机/贷款初审/数字员工）+ 一个踩坑反思。

这种"判断→安全→举证"的回答，比罗列框架名有说服力得多。

---

## 十八、通信协议选型决策树

lesson01 讲了三种通信模型，给一张选型决策树，面对需求直接套：

```
开始
 │
 ├─ 同进程、强一致、要调试 → 共享黑板（Graph OverAllState）
 ├─ 同进程、松耦合、要并行 → 消息传递（msghub）
 └─ 跨框架/跨厂商 → A2A 标准协议
       │
       └─ 是否要统一治理/注册发现？ → 是 → 配 Nacos MCP Registry（lesson07）
```

经验：先问"是不是跨进程"。不跨进程优先共享状态（调试友好）；跨进程优先消息总线；跨厂商必上 A2A。别一上来就分布式。

<callout emoji="✅"><p>工程直觉：能用同进程共享状态解决的，绝不引入跨进程消息；能引入消息的，绝不引入跨框架 A2A。复杂度递增，可维护性递减。每升一级都要有"不得不"的理由。</p></callout>

---

## 十九、状态同步的分布式实现

lesson01 的"共享黑板"若在单进程用 `ConcurrentHashMap` 即可。但多 Agent 部署在多实例时，状态要跨进程共享，用分布式存储：

```
Agent 实例A ─┐
Agent 实例B ─┼──▶ 共享状态存储(Redis/PG) ──▶ 所有实例读写同一份状态
Agent 实例C ─┘       （带 Reducer 语义的原子 merge）
```

```java
// 概念：用 Redis 做跨实例共享状态，merge 保证原子归约
redisTemplate.opsForHash().putAll("state:" + threadId, updates);
// 读取时用 Lua 脚本保证 "读-合并-写" 原子，避免并发覆盖
```

这把 lesson01 的通用模型落到了分布式生产形态——但前提是"状态 schema 设计得当"（lesson02 的 KeyStrategyFactory）。

---

## 二十、与事件驱动架构的结合

多 Agent 系统常嵌在事件驱动架构（EDA）里：外部事件（如"新工单创建"）触发 Agent 编排，Agent 产出再发事件驱动下游。

```
[工单系统] ──事件──▶ [事件总线 Kafka] ──▶ [Supervisor Agent 消费]
                                                   │
                                                   ▼
                                      [子 Agent 处理] ──事件──▶ [CRM/工单系统]
```

好处：Agent 与业务系统解耦，靠事件而非直接调用串联，弹性好、易扩展。这也是第九章"事件驱动架构与微服务中的 Agent"的铺垫。

---

## 二十一、面试自测补充

1. **通信协议怎么选？** —— 同进程共享状态/消息；跨进程消息；跨厂商 A2A。
2. **分布式状态同步？** —— 共享存储(Redis/PG) + 原子 merge 保证归约语义。
3. **和 EDA 关系？** —— 外部事件触发 Agent 编排，Agent 产出再发事件，解耦弹性好。
4. **测试策略？** —— Mock 模型固定决策，验证编排逻辑确定性可测。

---

## 二十三、多 Agent 的"上下文窗口"陷阱

lesson01 提过"上下文窗口有限"是单 Agent 天花板，多 Agent 反而能缓解：把大上下文拆给专职 Agent，每个只持自己那块。

```
单 Agent：整个任务上下文塞一个窗口 → 易溢出、易漂移
多 Agent：每个子 Agent 只持自己 subtask 的上下文 → 各自清爽
```

但要注意"上下文传染"：Supervisor 汇总时若把子 Agent 的完整输出全塞进自己的窗口，Supervisor 也会溢出。解法：子 Agent 输出先压缩（第十五章）再汇总。

<callout emoji="💡"><p>洞察：多 Agent 不是"更多上下文"，而是"更聚焦的上下文"。设计时要让每个 Agent 的上下文尽可能小、尽可能相关。这是把"上下文工程"落到多 Agent 架构的关键。</p></callout>

---

## 二十四、任务分发策略的代码骨架

lesson01 讲了三种分发策略，这里给一个"编排器"通用骨架（框架无关），把概念固化：

```java
interface Orchestrator {
    // 把任务拆成有序/并行的子任务
    Plan plan(Task task);
    // 按状态调度下一个要执行的 Agent
    Agent next(SharedState state);
}

class SupervisorOrchestrator implements Orchestrator {
    public Agent next(SharedState s) {
        String domain = s.read("domain");
        return switch (domain) {
            case "finance" -> financeAgent;
            case "tech"    -> techAgent;
            default        -> customerAgent;
        };
    }
}
```

`Graph` 的"条件边 + 路由节点"、`Supervisor` 模式，本质都是这个 `next()` 的具体实现。通用模型通了，看框架就不晕。

---

## 二十五、本章与后续章节的衔接

lesson01 是第四章的"地基"，后续每节都在它之上盖楼：

```
lesson01 通用模型 → lesson02 Graph 引擎 → lesson03~04 模式
                → lesson05 HITL → lesson06~08 AgentScope/沙箱/Plan
                → lesson09 选型总结
```

学完本章，你具备"设计多 Agent 系统"能力；第五章（ADK）给你第二个框架视角；后续章节补"工程化/评测/部署"，把"能设计"变成"能交付"。

---

## 二十六、小结

这一节我们把多 Agent 协作的"通用模型"立起来了：

1. 多 Agent 动机：突破单 Agent 的上下文/能力/并行天花板。
2. 通信协议三模型：消息传递（Actor/msghub）、共享黑板（Graph OverAllState）、A2A 标准协议（跨框架）。
3. 任务分发三策略：主控分发（Supervisor）、流水线（Sequential）、去中心化协商（慎用）。
4. 状态同步是最大坑：写冲突、脏读、状态膨胀、死锁；解法——私有状态隔离 + 带归约的共享通道 + 可持久化。
5. 通用骨架：Agent + SharedState + Orchestrator + Flow，具体框架（Graph / AgentScope）都是它的实例化。
6. 企业落地：银行数字员工中台 = 流水线 + 主控终审。

下一节（lesson02）我们进入 Spring AI Alibaba Graph 的核心概念，把上面的"共享状态 + 编排器"落到具体 API——工作流、节点、边怎么写。

---

## 延伸阅读 / 引用文档

1. AgentScope Java 官方文档（多 Agent 协作）：https://java.agentscope.io/v1/en/docs/
2. Spring AI Alibaba Graph 文档：https://java2ai.com/docs/1.0.0.2/tutorials/graph/
3. Google A2A 协议规范：https://github.com/google/A2A
4. Anthropic 多 Agent 协作研究（Building Effective Agents）：https://www.anthropic.com/research/building-effective-agents
5. LangGraph 多 Agent 编排思想（来源）：https://langchain-ai.github.io/langgraph/
6. AutoGen（微软多 Agent 框架，对比参考）：https://microsoft.github.io/autogen/

# 第十三章第3节：通用智能体 vs 专用智能体：应用场景与架构差异

大家好，我是秋天。

前面两节我们讲了 Agentic AI 的定义、L1-L5，以及 LLM 到 Agent 的必然。这一节聊一个非常"落地导向"的话题：**做 Agent，是做一个啥都会的"通用智能体"，还是做一堆"专精某领域"的专用智能体？**

这个问题没有标准答案，但有清晰的决策框架。很多团队一开始冲着"通用 Agent"去，最后发现不如"专用 Agent 组合"好用、好控、好上线。这一节我把这俩的架构差异、适用场景、成本账给你算清楚。

学完这一节，你将掌握：

1. 通用 vs 专用智能体的本质区别（能力广度 vs 深度）；
2. 两者在架构上的关键差异（上下文、工具集、编排、治理）；
3. 什么场景选哪种（含企业真实例子）；
4. "专用 Agent 组合"如何逼近"通用"效果；
5. 招聘里"Agent 架构选型"对应的岗位要求。

---

## 一、两种智能体的定义

先对齐概念：

- **通用智能体（General-purpose Agent）**：一个 Agent 试图处理多领域任务，工具集大而全，靠模型自身路由与规划。常被宣传为"一个 Agent 搞定一切"。
- **专用智能体（Specialized Agent）**：每个 Agent 只负责一个明确领域（如"合同审查 Agent""订单查询 Agent"），工具集窄而精，由编排层（Supervisor / A2A）组合。

```text
   通用 Agent                 专用 Agent 组合
 ┌──────────────┐          ┌────────┐ ┌────────┐ ┌────────┐
 │  全能 Agent   │          │合同Agent│ │订单Agent│ │客服Agent│
 │ 工具:100+     │          │工具:5   │ │工具:3   │ │工具:8   │
 │ 领域:全       │          └───┬────┘ └───┬────┘ └───┬────┘
 │ 靠模型自选    │              └─────┬─────┴──────────┘
 └──────────────┘                    ▼
                               编排层(Supervisor/A2A)
   特征：灵活但难控              特征：可控、可审计、可独立迭代
```

<callout emoji="💡"><p>秋天给一句判断：通用 Agent 像"万能工"，专用 Agent 像"工种明确的班组"。家里换个灯泡，万能工方便；但盖栋楼，你得要电工、泥瓦工、木工各司其职——靠一个万能工既慢又容易出事。</p></callout>

---

## 二、架构差异：五维对比

| 维度 | 通用 Agent | 专用 Agent |
|------|-----------|------------|
| 工具集 | 大而全（100+），模型自选 | 窄而精（<10），领域强相关 |
| 上下文 | 混杂多领域，易"上下文腐烂" | 聚焦单领域，上下文干净 |
| 规划 | 模型承担重规划，易跑偏 | 编排层承担路由，路径可控 |
| 可观测/审计 | 难定位哪步出错 | 每 Agent 边界清晰，易审计 |
| 迭代成本 | 改一处影响全局 | 单 Agent 独立升级 |

<callout emoji="⚠️"><p>最关键的是"上下文腐烂"：通用 Agent 把 100 个工具的描述全塞进 context，模型反而选不准、容易幻觉（呼应第十五章 Context Engineering）。专用 Agent 只给相关工具，模型决策质量更高。这是"专用胜出"的技术根因之一。</p></callout>

---

## 三、代码对比：两种路由方式

### 3.1 通用 Agent：一个 ChatClient 带全部工具

```java
// 通用：所有工具一股脑注入，靠模型自己选
chatClient.prompt()
    .user(question)
    .tools(allTools)  // 100+ 工具
    .call()
    .content();
// 优点：简单；缺点：工具多→选不准、context 膨胀、难审计
```

### 3.2 专用 Agent + 编排（Supervisor 路由）

```java
package com.qiutian.agentic;

import java.util.*;

/** 极简 Supervisor：根据意图路由到专用 Agent */
public class Supervisor {

    // 每个专用 Agent 只注册自己领域的工具
    private final Map<String, SpecializedAgent> agents = Map.of(
        "contract", new SpecializedAgent("合同审查", List.of("risk_scan", "clause_extract")),
        "order",    new SpecializedAgent("订单查询", List.of("query_order", "refund")),
        "support",  new SpecializedAgent("客服",     List.of("faq", "complaint"))
    );

    /** 第一步：判断意图，路由到对应专用 Agent */
    public String route(String intent, String question) {
        SpecializedAgent agent = switch (intent) {
            case "合同" -> agents.get("contract");
            case "订单" -> agents.get("order");
            default     -> agents.get("support");
        };
        return agent.handle(question); // 只带自己领域的工具
    }

    static class SpecializedAgent {
        final String name;
        final List<String> tools;
        SpecializedAgent(String n, List<String> t) { name=n; tools=t; }
        String handle(String q) {
            // 实际：chatClient.prompt().user(q).tools(mapTools(tools))
            return "[" + name + "] 处理:" + q;
        }
    }
}
```

<callout emoji="✅"><p>这个骨架体现"专用胜出"的核心：路由层做意图分类（轻量、可控），专用 Agent 只带相关工具（决策准、context 小、易审计）。生产里 Supervisor 可以是 L4 编排（第四章），也可以是基于 A2A 的动态协作（第十一章）。</p></callout>

---

## 四、应用场景：怎么选

给你一张决策表，写方案时直接套：

| 场景特征 | 推荐 | 理由 |
|----------|------|------|
| 单领域、流程固定（合同审查） | 专用 | 质量稳、可审计、合规强 |
| 内部员工助手（多领域偶发） | 专用组合+路由 | 兼顾广度与可控 |
| 探索性研究 / 个人效率 | 通用 | 灵活优先，容错高 |
| 强监管（金融/政务） | 专用（必选） | 审计与合规硬要求 |
| 跨企业协作网络 | 专用 + A2A | 各厂专精 Agent 互操作 |

```text
   选型决策树（简化）
 ┌──────────────────────────────────────────┐
 │ 是否强监管 / 高审计要求？                   │
 │   是 → 专用 Agent（必选）                  │
 │   否 ↓                                    │
 │ 任务是否跨多领域且需高质量？               │
 │   是 → 专用组合 + 编排                     │
 │   否 → 通用 Agent（够用即可）              │
 └──────────────────────────────────────────┘
```

<callout emoji="💡"><p>企业里 80% 的落地是"专用组合"。原因很现实：老板要的是"合同审查准确率 99%、可解释、可审计"，不是"什么都能聊但偶尔胡说"。专用 Agent 把不确定性关进了领域笼子里。</p></callout>

---

## 五、"专用组合"如何逼近"通用"效果

很多人担心"专用 Agent 不够通用"。其实通过编排层（Supervisor / A2A），专用组合既能控又能扩：

```text
           专用组合 → 逼近通用
 ┌──────────────────────────────────────────┐
 │ 用户: "帮我查订单并审查合同风险"           │
 │   Supervisor 拆成两个子任务                 │
 │     → 订单 Agent（查订单）                  │
 │     → 合同 Agent（审风险）                  │
 │   汇总 → 给用户完整答案                     │
 │ 效果：像通用 Agent 一样"啥都能办"          │
 │ 但底层：每个环节专精、可控、可审计          │
 └──────────────────────────────────────────┘
```

这就是 L4（多智能体协作）的价值——**用"专精 + 编排"既获得通用能力，又不丢失可控性**。第十一章 A2A、第四章 Supervisor 都是这个思路。

---

## 六、成本账：通用 vs 专用

除了质量，还要算账：

```text
   成本对比（粗略）
 ┌──────────────────────────────────────────┐
 │ 通用 Agent：                              │
 │   - 每次调用 context 大（全工具描述）→ token 贵 │
 │   - 易跑偏重试 → 调用次数多 → 更贵          │
 │   - 一个出错全链路受影响 → 隐性成本高        │
 │ 专用 Agent：                              │
 │   - context 小 → token 省                 │
 │   - 决策准 → 重试少 → 调用省               │
 │   - 故障隔离 → 隐性成本低                  │
 └──────────────────────────────────────────┘
```

<callout emoji="⚠️"><p>别小看 token 成本。通用 Agent 每次把 100 个工具描述塞进 prompt，一个中等会话可能多花 30%-50% token。第十章"成本治理"讲的模型路由、上下文压缩，在通用 Agent 上压力更大。专用 Agent 天然更省。</p></callout>

---

## 七、招聘映射

```text
【岗位】AI Agent 架构师
【要求】
- 能根据业务特征合理选型通用/专用 Agent 架构；
- 有 Supervisor / 多 Agent 编排、A2A 协作落地经验；
- 理解上下文工程对 Agent 质量的影响（防上下文腐烂）。
```

<callout emoji="✅"><p>"通用 vs 专用"是架构选型的经典题。能说出"专用胜在可控/可审计/省 token、通用胜在灵活、强监管必选专用"，并给出决策树，面试官立刻把你归到"有架构判断力"那一档。</p></callout>

---

## 八、常见误区

1. **"通用 = 更高级"**：错，通用是取舍，不是等级。L5 通用 Agent 目前生产慎之又慎。
2. **"专用就是写死 if-else"**：错，专用 Agent 内部仍是完整 Agent（规划+工具+记忆），只是领域收敛。
3. **"上了专用就不用通用模型"**：错，专用 Agent 内部仍用强模型做推理，只是工具集收敛。
4. **"专用组合一定更贵"**：错，通常更省（见成本账）。

---

## 九、用 A2A 做"动态组队"的专用 Agent

前面专用 Agent 靠 Supervisor 静态路由。更灵活的是用 A2A 动态发现并组队：

```java
// 基于 AgentCard 动态发现专精 Agent 并委派（骨架）
class DynamicCrew {
    A2AClient a2a = new A2AClient();

    String solve(String goal) {
        // 1. 从注册中心拉所有可用 Agent 的 AgentCard
        List<AgentCard> cards = registry.discover();
        // 2. 按目标语义匹配需要的专精 Agent
        List<AgentCard> team = matcher.match(goal, cards);
        // 3. 依次 A2A 委派，汇总结果
        StringBuilder sb = new StringBuilder();
        for (AgentCard c : team) {
            sb.append(a2a.sendTask(c.url(), goal).artifact());
        }
        return sb.toString();
    }
}
```

```text
   静态路由 vs 动态组队
 ┌──────────────────────────────────────────────────┐
 │ 静态(Supervisor)：路由规则写死，简单可控          │
 │ 动态(A2A)：按目标临时组队，灵活但需治理           │
 │ 选型：稳定业务用静态，跨组织协作/未知任务用动态   │
 └──────────────────────────────────────────────────┘
```

<callout emoji="💡"><p>动态组队是 L4 的进阶形态，也更接近 L5。但它对"Agent 能力描述准确性"要求极高——AgentCard 写错，匹配就错。所以 AgentCard 的质量（见第十一章）直接决定动态组队成败。</p></callout>

---

## 十、成本量化：通用 vs 专用 的 token 账单

前面讲了成本账，这里给个可计算的模型：

```java
// 估算每次会话的 token 成本（示意）
class CostEstimator {
    double cost(boolean general, int toolsNum, int turns) {
        // 通用：所有工具描述常驻 context
        double perTurnTools = general ? toolsNum * 40 : 5 * 40;
        double base = 200;                 // 系统提示
        return (base + perTurnTools) * turns * 0.00001; // 单价示意
    }
}
// general=true, tools=100, turns=10 → 成本显著高于专用
```

<callout emoji="⚠️"><p>别小看这部分。一个每天 10 万次调用的客服 Agent，通用方案比专用方案每月可能多花几万到几十万 token 费用。成本治理（第十章）在规模下是硬指标。</p></callout>

---

## 十一、评估：专用 vs 通用的准确率对比实验

怎么证明"专用更准"？做个对照实验：

```text
   对照实验设计
 ┌──────────────────────────────────────────────────┐
 │ 任务：合同风险审查（强领域）                      │
 │ A 组：通用 Agent（全工具）   → 准确率 81%         │
 │ B 组：专用合同 Agent（仅5工具）→ 准确率 96%        │
 │ 结论：领域任务，专用显著更优（且更省 token）       │
 │ 注意：探索性任务（如"帮我研究某话题"）可能相反   │
 └──────────────────────────────────────────────────┘
```

<callout emoji="✅"><p>这种对照实验是你做架构选型汇报的"弹药"。老板问"为啥不用通用"，你拿准确率和成本两张表说话，比纯理念有说服力。</p></callout>

---

## 十二、演进路径：从专用走向"受控通用"

企业不必二选一。常见演进：先用专用 Agent 把核心业务做稳，再在上层加编排/路由，逐步逼近通用体验：

```text
   推荐演进路径
 ┌──────────────────────────────────────────────────┐
 │ 阶段1：N 个独立专用 Agent（质量稳、可审计）        │
 │   ↓ 业务需要"一句话办多件事"                      │
 │ 阶段2：加 Supervisor 路由（专用组合）              │
 │   ↓ 跨组织协作                                    │
 │ 阶段3：A2A 动态组队（受控通用）                    │
 │ 始终：每个环节可审计、可干预、不失控               │
 └──────────────────────────────────────────────────┘
```

---

## 十三、面试追问：你们为什么不上通用 Agent

```text
问：为什么不用一个通用 Agent 搞定所有事？
答：我们的业务是强监管的合同/金融场景，准确率、可审计是硬要求。
   专用 Agent 把不确定性关在领域内，准确率和可审计性显著更优，
   且 token 更省。我们用"专用组合 + 编排"逼近通用体验，
   但不牺牲可控性。通用 Agent 在探索性场景更合适，不是我们的主战场。
```

---

## 小结

这一节我们厘清了通用与专用智能体的取舍：

- **定义**：通用（广而全、靠模型自选）vs 专用（窄而精、靠编排组合）；
- **差异**：工具集、上下文、规划、审计、迭代成本五维，专用在可控性/质量上占优；
- **代码**：通用（全工具注入）vs 专用+Supervisor 路由（意图分类+领域工具）；
- **选型**：强监管/高质量必选专用；探索/个人效率可用通用；企业多走"专用组合"；
- **逼近通用**：专用 + 编排（L4/A2A）既控又扩；
- **成本**：专用通常更省 token、更省隐性成本。

到这里，第十三章"Agentic AI 理论与未来趋势"三节全部讲完。下一章我们回到最落地的视角——Java Agent 生态与框架演进，给你一张"该关注什么框架、怎么选型、企业自研怎么搞"的全局图。

---

## 延伸阅读 / 引用文档

1. Anthropic《Effective Agents》（何时用简单方案）：https://www.anthropic.com/engineering/building-effective-agents
2. LangChain4j 多 Agent / 路由文档：https://docs.langchain4j.dev/
3. Spring AI Alibaba Graph（Supervisor 编排）：https://spring-ai-alibaba.github.io/spring-ai-alibaba/
4. Google ADK for Java（多 Agent）：https://google.github.io/adk-docs/
5. AgentScope（多智能体框架）：https://github.com/modelscope/agentscope
6. Context Engineering 指南（上下文腐烂与工具选择）：https://www.anthropic.com/engineering/effective-context-engineering

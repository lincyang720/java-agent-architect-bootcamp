# 第十五章第4节：企业级上下文层——语义/血缘/运维/策略上下文与 MCP/A2A 协同

大家好，我是秋天。

前面三节我们把上下文工程的"战术"讲透了：定义、四大策略、窗口真相。这节课上"战略"——**在一家企业里，上下文不该是每个 Agent 各写各的散兵游勇，而要成为一层可治理、可审计、可复用的企业资产**。

这节课讲什么、学完掌握什么：

- 讲什么：企业级上下文层的四大组成（语义上下文 / 血缘上下文 / 运维上下文 / 策略上下文），以及它怎么通过 **MCP（模型上下文协议）** 和 **A2A（Agent-to-Agent）** 和整个 Agent 生态协同，把"上下文"当资产管起来。
- 学完掌握：你能画出一个企业上下文层的架构图；你能说清"为什么上下文需要血缘和策略"；你知道 MCP 怎么给上下文层提供标准化供给、A2A 怎么在 Agent 间传递上下文。

<callout emoji="💡"><p>招聘映射：JD 原话"有 Agent 平台工程经验，能设计上下文治理层""理解 MCP/A2A 协议并在企业级 Agent 架构中落地"。这节课直接对应"企业级工程化"和"协议"两条硬要求（第十一章 MCP/A2A 的上一层）。能讲清"上下文即资产"的人，在架构师面试里是碾压级的。</p></callout>

---

## 一、为什么需要"企业级上下文层"

先说痛点。小团队 1 个 Agent，上下文自己管自己。但企业里是 **几十个 Agent、跨多个业务域、多个租户**：

```
没有上下文层时（混乱）：
  Agent-A 自己拼 prompt、自己查库、自己记记忆
  Agent-B 重复造轮子，且和 A 的记忆不互通
  合规来查"这条回答的上下文哪来的" → 查不到
  运营改一条策略 → 要改 N 个 Agent 的代码

有了上下文层后（治理）：
  所有 Agent 从统一"上下文服务"取上下文
  策略/知识/记忆集中管理、版本化、可审计
  一条上下文有完整血缘（来自哪个知识库、哪版策略）
  合规可溯源、运营可灰度、架构可复用
```

核心转变：**上下文从"每个 Agent 的私有实现"变成"企业共享的可治理资产"。**

<callout emoji="✅"><p>一句话记忆：上下文层的价值 = 复用（不用每个 Agent 重写）+ 治理（可审计可追溯）+ 一致性（策略统一生效）。这是"提示词平台/上下文平台"这个岗位存在的根本原因。</p></callout>

---

## 二、上下文层的四大组成部分

企业级上下文层至少包含四类上下文，各司其职：

```
┌──────────────────────────────────────────────────────────────┐
│                  企业级上下文层 (Context Layer)                 │
├──────────────┬───────────────────────────────────────────────┤
│ 语义上下文    │ 知识/记忆/检索结果：Agent "知道什么"            │
│ Semantic      │ 来源：RAG 知识库、长期记忆、用户画像           │
├──────────────┼───────────────────────────────────────────────┤
│ 血缘上下文    │ 这条上下文"从哪来、哪个版本、谁改的"            │
│ Lineage       │ 用于审计、溯源、badcase 定位                   │
├──────────────┼───────────────────────────────────────────────┤
│ 运维上下文    │ 运行时状态/健康/降级/限流：Agent "现在什么状态" │
│ Ops           │ 来源：监控、配置中心、熔断状态                 │
├──────────────┼───────────────────────────────────────────────┤
│ 策略上下文    │ 合规/路由/权限/租户策略：Agent "被允许做什么"   │
│ Policy        │ 来源：策略引擎、权限中心、租户配置             │
└──────────────┴───────────────────────────────────────────────┘
```

### 2.1 语义上下文（Semantic Context）

就是前面 lesson02 讲的"知识 + 记忆 + 检索结果"。企业级要求：集中存储、统一检索接口、带元数据。

```java
// ch15-context-engineering/src/main/java/demo/layer/SemanticContextService.java
package demo.layer;

import java.util.List;

/** 语义上下文服务：统一供给"知识 + 记忆"，对外只暴露 Select 接口 */
public interface SemanticContextService {
    /** 按任务语义检索相关知识（接第六章/第七章底座） */
    List<ContextItem> retrieve(String tenantId, String task, int topK);
    /** 写入长期记忆（Write 策略） */
    void writeMemory(String tenantId, String userId, MemoryPayload payload);
}
```

### 2.2 血缘上下文（Lineage Context）

这是企业最容易缺、但合规最看重的。**每一条进入窗口的上下文，都要记录"它是谁、从哪来、哪个版本"。**

```java
// ch15-context-engineering/src/main/java/demo/layer/LineageContext.java
package demo.layer;

import java.time.Instant;

/**
 * 血缘上下文：记录每条上下文的来源与版本，支撑审计与 badcase 溯源。
 */
public record LineageContext(
        String itemId,
        String sourceSystem,     // 知识库名 / 记忆库 / 工具名
        String sourceVersion,    // 知识库版本 / 策略版本
        String fetchedBy,        // 哪个 Agent / 用户
        Instant fetchedAt,
        String pipelineRunId     // 哪次检索/装配流程产生的（接第十六章评测）
) {
    /** 生成可审计的血缘标签，拼进上下文条目 */
    public String toTag() {
        return String.format("[src=%s@%s,by=%s,at=%s,run=%s]",
                sourceSystem, sourceVersion, fetchedBy, fetchedAt, pipelineRunId);
    }
}
```

<callout emoji="⚠️"><p>没有血缘，出了合规事故你连"这条错误答案是哪份旧制度喂的"都查不出。金融/政企的上下文层，血缘不是锦上添花，是监管硬要求。每条进窗口的上下文都带 sourceVersion，运营改了知识库你能立刻定位受影响的历史回答。</p></callout>

### 2.3 运维上下文（Ops Context）

Agent 运行时需要"知道自己的状态"：当前是否降级、限流阈值多少、依赖服务健不健康。这些就是运维上下文。

```java
// ch15-context-engineering/src/main/java/demo/layer/OpsContext.java
package demo.layer;

/** 运维上下文：Agent 运行时注入自身状态，决定行为 */
public record OpsContext(
        boolean degraded,            // 是否降级模式
        int rateLimitRemaining,      // 剩余配额
        String dependencyHealth,     // 依赖服务健康：HEALTHY/DEGRADED/DOWN
        String modelRoute            // 当前路由到的模型（成本治理）
) {
    /** 降级时，注入"简化指令"到系统提示，让模型走保守路径 */
    public String toSystemHint() {
        if (degraded) return "【运维提示】当前系统降级，请只做必要回答，避免调用重载工具。";
        if ("DOWN".equals(dependencyHealth))
            return "【运维提示】知识库依赖不可用，请明确告知用户无法查证，不要编造。";
        return "";
    }
}
```

### 2.4 策略上下文（Policy Context）

决定"这个租户、这个角色、这次请求，被允许做什么"。权限、合规、租户隔离策略都在这。

```java
// ch15-context-engineering/src/main/java/demo/layer/PolicyContext.java
package demo.layer;

/** 策略上下文：注入权限/合规/租户约束，约束 Agent 行为边界 */
public record PolicyContext(
        String tenantId,
        List<String> allowedTools,     // 该租户允许调用的工具白名单
        boolean piiRedaction,          // 是否强制 PII 脱敏
        String complianceLevel         // 合规等级：STANDARD / STRICT
) {
    public String toSystemConstraint() {
        StringBuilder sb = new StringBuilder();
        sb.append("【策略】你仅可调用工具：").append(allowedTools).append("。");
        if (piiRedaction) sb.append("输出前必须脱敏所有个人身份信息。");
        if ("STRICT".equals(complianceLevel))
            sb.append("涉及金额/医疗/法务的结论必须标注来源且不得臆测。");
        return sb.toString();
    }
}
```

<callout emoji="💡"><p>四大上下文怎么分工？语义回答"知道什么"，血缘回答"哪来的"，运维回答"现在啥状态"，策略回答"被允许做啥"。四者拼接成一次完整的、可治理的上下文窗口。这也是为什么 lesson04 叫"企业级"——它有治理维度，不只是技术维度。</p></callout>

---

## 三、上下文层与 MCP 的协同

### 3.1 MCP 是什么（回顾）

MCP（Model Context Protocol）是 Anthropic 提出的开放协议，让 Agent 用**统一方式**连接"数据源"和"工具"。对上下文层来说，MCP 是**标准化的"上下文供给管道"**。

```
传统（每接一个数据源写一套代码）：
  Agent ──硬编码──► MySQL
  Agent ──硬编码──► 向量库
  Agent ──硬编码──► 内部 API

MCP（标准化）：
  Agent ──MCP 协议──► MCP Server A (知识库)
                    ──MCP 协议──► MCP Server B (CRM)
                    ──MCP 协议──► MCP Server C (工单系统)
  上下文层只需对接 MCP，不必关心后端是啥
```

### 3.2 用 MCP 给上下文层供给语义上下文

```java
// ch15-context-engineering/src/main/java/demo/layer/mcp/McpBackedSemantic.java
package demo.layer.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpClientTransport;

import java.util.List;

/**
 * 通过 MCP 协议从多个 MCP Server 拉取语义上下文。
 * 上下文层不关心后端是向量库还是 CRM，只认 MCP 资源接口。
 */
public class McpBackedSemantic {

    private final List<McpSyncClient> servers;

    public McpBackedSemantic(List<McpSyncClient> servers) {
        this.servers = servers;
    }

    /** 从所有已连接的 MCP Server 检索资源，统一汇入语义上下文 */
    public List<ContextItem> retrieveFromMcp(String query) {
        return servers.stream()
                .flatMap(s -> {
                    // 调用 MCP 的 resources/read 或 tools/call 取数据
                    var resources = s.listResources();
                    // 简化：把每个 server 的检索结果转成 ContextItem（带血缘）
                    return resources.stream().map(r ->
                        new ContextItem(r.uri(), r.contents().toString(),
                                ContextType.RETRIEVED, 0.8,
                                "mcp://" + s.getServerInfo().name()));
                }).toList();
    }
}
```

### 3.3 MCP 让上下文"可插拔"

最大好处：换知识库、加数据源，**上下文层代码不动，只加一个 MCP Server**。这对企业"数据源天天变"的现实是救命的。

<callout emoji="✅"><p>MCP 对上下文工程的意义 = 供给标准化。你的 Select 策略（lesson02）从此面对的是统一的 MCP 资源接口，而不是一堆硬编码的 DAO。这也是"上下文即资产"能成立的技术前提——资产得能被标准协议访问。</p></callout>

---

## 四、上下文层与 A2A 的协同

### 4.1 A2A 是什么

A2A（Agent-to-Agent，Google 提出的开放协议）让**多个 Agent 互相发现、委派任务、传递上下文**。对上下文层来说，A2A 是**上下文在 Agent 之间的"传递总线"**。

### 4.2 用 A2A 在 Agent 间传递隔离的上下文

回忆 lesson02 的 Isolate：每个子 Agent 只拿自己该看的。A2A 负责"安全地把上下文切片传给对应 Agent"。

```java
// ch15-context-engineering/src/main/java/demo/layer/a2a/A2AContextHandoff.java
package demo.layer.a2a;

import demo.context.ContextItem;
import demo.context.ContextType;
import demo.context.isolate.SubAgentIsolation;

import java.util.List;

/**
 * 通过 A2A 把"隔离后的上下文切片"交给目标 Agent。
 * 配合 lesson02 的 Isolate，确保子 Agent 只看授权类型。
 */
public class A2AContextHandoff {

    /** 把全局上下文按目标 Agent 的授权切片，经 A2A 消息发出 */
    public A2AMessage handoff(List<ContextItem> global,
                               String targetAgent,
                               List<ContextType> allowed) {
        List<ContextItem> slice = SubAgentIsolation.sliceForSubAgent(global, allowed);
        // A2A 消息体（示意）：携带隔离后的上下文 + 血缘
        return new A2AMessage(targetAgent, slice);
    }

    public record A2AMessage(String targetAgent, List<ContextItem> context) {}
}
```

```
Supervisor Agent
     │  经 A2A 分发
     ├────► 检索 Agent   （只给 RETRIEVED 授权）
     ├────► 执行 Agent   （只给 TOOL + MEMORY 授权）
     └────► 审核 Agent   （给全部 + POLICY 策略）
     每个 Agent 上下文隔离（Isolate），且带血缘可溯源
```

<callout emoji="⚠️"><p>A2A 传递上下文时，必须带着血缘和策略标签。否则下游 Agent 拿到一段上下文却不知道"它来自哪个租户、哪个版本"，既无法审计，也可能跨租户污染。上下文层和 A2A 协同的要点：传递的不只是内容，是"带治理元数据的上下文对象"。</p></callout>

---

## 五、把上下文当"可治理资产"：版本、灰度、审计

### 5.1 上下文版本化与灰度

知识库、策略、系统提示词都会变。企业要求"改了能灰度、出错能回滚"：

```yaml
# ch15-context-engineering/src/main/resources/context-governance.yml
context-governance:
  semantic:
    knowledge-versioning: true        # 知识库按版本管理
    retrieval-pin-version: true       # 可指定用某版知识（回滚用）
  policy:
    strategy-versioning: true         # 策略按版本
    canary:                           # 灰度：1% 流量用新策略
      enabled: true
      new-version: v2
      traffic-percent: 1
  lineage:
    mandatory-tag: true               # 每条上下文强制带血缘标签
    audit-log: true                   # 审计日志
  ops:
    degrade-on-dependency-down: true  # 依赖挂了自动降级（接 Ops 上下文）
```

### 5.2 审计与溯源：一条回答的完整血缘链

```java
// ch15-context-engineering/src/main/java/demo/layer/AuditTrail.java
package demo.layer;

import demo.layer.LineageContext;

import java.util.List;

/** 一条回答的上下文血缘链，用于合规审计与 badcase 定位 */
public record AuditTrail(
        String answerId,
        String tenantId,
        List<LineageContext> contextChain,   // 这条回答用了哪些上下文、哪版
        String modelUsed,
        String policyVersion
) {
    /** 出 badcase 时，能立刻定位"是哪版知识/策略导致" */
    public String rootCauseHint() {
        return contextChain.stream()
                .map(LineageContext::toTag)
                .reduce("回答[" + answerId + "] 血缘：\n", (a, b) -> a + b + "\n");
    }
}
```

<callout emoji="💡"><p>治理闭环：版本化 → 灰度 → 审计 → 回滚。这四步让"上下文"真正成为企业资产，而不是一团不可控的 prompt 字符串。当运营说"昨天改了知识库，今天有客户投诉答错了"，你能 5 分钟内用血缘链定位到"是 v3 知识库某条导致"，并一键回滚到 v2。这就是平台工程的价值。</p></callout>

---

## 六、企业级上下文层完整架构图

把全章串起来，给你一张能直接画进架构评审的图：

```
┌──────────────────────────────────────────────────────────────────┐
│                        企业级上下文层                                │
│                                                                    │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────────────┐   │
│  │ 语义上下文│  │ 血缘上下文│  │ 运维上下文│  │   策略上下文      │   │
│  │ Semantic │  │ Lineage  │  │   Ops    │  │     Policy        │   │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────────┬─────────┘   │
│       │             │             │                │             │
│  ┌────┴─────────────┴─────────────┴────────────────┴─────────┐   │
│  │            Context Orchestrator（装配 + 治理）              │   │
│  │   Write / Select / Compress / Isolate  （lesson02 策略）   │   │
│  │   位置工程 / 缓存 / 腐烂监控            （lesson03 手段）   │   │
│  └────┬───────────────────────────────────────────┬─────────┘   │
│       │ 供给（MCP）                                │ 分发（A2A） │
│  ┌────┴──────────┐                        ┌────────┴────────┐   │
│  │ MCP Servers   │                        │  Agent 网络     │   │
│  │ 知识库/CRM/API│                        │ Supervisor+子Ag │   │
│  └───────────────┘                        └─────────────────┘   │
│                                                                    │
│  横切：版本化 / 灰度 / 审计 / 回滚（可治理资产）                    │
└──────────────────────────────────────────────────────────────────┘
```

---

## 七、企业落地场景：政企知识库问答的上下文治理

某政府"政策问答 Agent"的上下文层落地：

```
语义上下文：对接 12 个委办局知识库（经 MCP 统一接入）
血缘上下文：每条政策答案标注"来源委办局 + 政策版本 + 生效日期"
运维上下文：高峰期限流，依赖检索服务降级时模型明确拒答不编造
策略上下文：公众租户只开放查询工具，内部租户开放工单工具
治理：政策更新走版本化 + 1% 灰度，出问题 5 分钟回滚

效果：
  • 知识库从 12 套硬编码 → 12 个 MCP Server，加数据源零改 Agent
  • 一次政策误答，5 分钟定位到"某委办局 v3 政策条款"，秒级回滚
  • 合规审计一键导出任意回答的完整血缘链
```

**为什么这样设计？** 政企场景"答错即问责"。没有血缘和策略上下文，错误无法定位、责任无法划分。上下文层把"可治理"做成架构内置能力，而不是事后补丁。

<callout emoji="✅"><p>这节是全课程"工程化"思想的集大成：上下文从战术（lesson01-03）升到战略（lesson04）。当你能把上下文讲成"带血缘、策略、版本、审计的资产层"，且用 MCP/A2A 标准化供给与分发，你就不是"写 Agent 的"，你是"设计 Agent 平台的"——这是架构师和高级工程师的分水岭。</p></callout>

---

## 八、与课程其他章的咬合

```
第十一章 MCP/A2A      ──► 本节协议底座（供给 + 分发）
第十章 工程化/成本    ──► 运维上下文 + 缓存降本
第十二章 安全治理      ──► 策略上下文（权限/合规/脱敏）
第六章 记忆 / 第七章 RAG ──► 语义上下文底座
第十六章 评测          ──► 血缘上下文接入 badcase 归因闭环
```

<callout emoji="💡"><p>学习路径：这一节依赖第十一章（MCP/A2A）和第十二章（治理）的概念。如果你还没看，建议学完本章后回看这两章——上下文层是它们的"上层消费者"。面试时把这层关系讲出来，架构视野立刻拉开。</p></callout>

---

## 九、面试自测

1. **企业级上下文层包含哪四类？** —— 语义/血缘/运维/策略，各管"知道什么/哪来的/啥状态/能做啥"。
2. **为什么需要血缘上下文？** —— 审计、badcase 溯源、知识库版本定位与回滚。
3. **MCP 对上下文层的作用？** —— 标准化供给管道，数据源可插拔，Select 不关心后端。
4. **A2A 传递上下文要注意什么？** —— 带血缘+策略标签，配合 Isolate 做授权切片。

---

## 十、小结

这节课把上下文工程从"战术"升到"战略"：

1. **企业级上下文层**把上下文从"各 Agent 私有实现"变成"可治理、可审计、可复用的企业资产"。
2. **四大组成**：语义（知道什么）、血缘（哪来的）、运维（啥状态）、策略（能做啥），四者拼成完整可治理窗口。
3. **MCP 协同**：标准化上下文供给管道，数据源可插拔，Select 不关心后端。
4. **A2A 协同**：上下文在 Agent 间的安全传递总线，必须带血缘+策略标签，配合 Isolate 切片。
5. **治理闭环**：版本化 → 灰度 → 审计 → 回滚，让上下文成为真正的企业资产。

至此第十五章（上下文工程）四节全部讲完。它是 2025–26 招聘头号技能，务必结合第十六章"评测闭环"一起落地。

---

## 延伸阅读 / 引用文档

1. Anthropic 上下文工程博客（含治理思想）：https://www.anthropic.com/engineering/effective-context-engineering
2. MCP 官方文档（Model Context Protocol）：https://modelcontextprotocol.io/
3. Google A2A 协议（Agent-to-Agent）：https://github.com/google/A2A
4. Spring AI Alibaba 上下文/治理实践：https://java2ai.com/docs/1.0.0.2/
5. LangSmith 上下文追踪与血缘（可观测性）：https://docs.smith.langchain.com/
6. Anthropic Prompt Caching（上下文层降本）：https://docs.anthropic.com/en/docs/build-with-claude/prompt-caching

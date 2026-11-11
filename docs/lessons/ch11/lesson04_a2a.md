# 第十一章第4节：A2A 协议（Agent-to-Agent）简介及标准化趋势对 Agent 架构的影响

大家好，我是秋天。

前面三节我们把 MCP 讲透了——它解决的是"模型怎么拿到工具和数据"。但还有一个更大的问题没人讲：**Agent 和 Agent 之间怎么对话、怎么分工、怎么把一件事接力做完？**

这一节我们聊 **A2A（Agent-to-Agent Protocol）**，Google 在 2025 年开源的 Agent 间通信标准，以及更宏观的"标准化趋势"会怎样重塑你未来三年写的 Agent 架构。

学完这一节，你将掌握：

1. A2A 要解决的痛点和它的核心概念（AgentCard / Task / Message / Artifact）；
2. A2A 与 MCP 的根本区别（一个管"模型连工具"，一个管"Agent 连 Agent"）；
3. 一个最小 A2A Server/Client 的 Java 思路示例；
4. 标准化趋势（MCP + A2A + AGNTCY 等）对架构设计的三重影响；
5. 招聘里"多智能体协作 / 标准化协议"对应的岗位要求。

---

## 一、为什么有了 MCP 还需要 A2A

很多同学会问："MCP 不是能调工具吗？Agent 把另一个 Agent 当工具调不就行了？"

答案是：**能用，但别扭，而且很快会乱。** 看区别：

```text
           把 Agent 当 MCP 工具（凑合方案）
 ┌──────────────┐   MCP call_tool    ┌──────────────┐
 │ Agent A      │ ─────────────────▶ │ Agent B      │
 │ (主)         │ ◀───────────────── │ (被当工具)   │
 └──────────────┘   返回字符串结果    └──────────────┘
 问题：
 1. A 不知道 B 能做什么、擅长什么（无能力发现）
 2. 长任务没有"状态/进度"概念（MCP Tool 是一次性请求-响应）
 3. 多轮协商、人工介入、流式中间结果全得自己造轮子
```

Agent 之间需要的是**协作语义**：能力发现、任务委派、状态追踪、流式产出、人工介入。这些是 MCP（面向"模型-工具"）没有的。A2A 就是为"Agent-Agent"量身定制的。

<callout emoji="💡"><p>一句话记忆：MCP 是"模型的手脚"（连工具/数据），A2A 是"Agent 的嘴和耳朵"（连其他 Agent）。两者互补，不是替代。后面会给你一张"何时用哪个"的判断图。</p></callout>

---

## 二、A2A 核心概念

A2A 规范定义了几个关键对象，先记牢：

| 概念 | 含义 | 类比 |
|------|------|------|
| **AgentCard** | 一个 Agent 的"数字名片"，JSON 描述它能做什么、怎么连 | 微服务的 `/actuator/info` + OpenAPI |
| **Task** | 一次协作单元，有生命周期（submitted→working→input-required→completed） | 一个工单 |
| **Message** | Agent 间传递的内容（文本/文件/结构化数据） | 消息体 |
| **Artifact** | Task 最终产出的结果（可能是多份文件） | 交付物 |
| **Part** | Message/Artifact 里的一个内容块 | 附件/段落 |

AgentCard 长这样（这是 A2A 的精髓——**能力自描述**）：

```json
{
  "protocolVersion": "0.2.0",
  "name": "合同审查 Agent",
  "description": "审查销售合同中的风险条款",
  "url": "https://agents.internal/contract-review",
  "capabilities": {
    "streaming": true,
    "pushNotifications": false,
    "stateTransitionHistory": true
  },
  "skills": [
    {
      "id": "risk-scan",
      "name": "风险扫描",
      "description": "识别合同中的不公平条款",
      "tags": ["legal", "contract"],
      "examples": ["扫描这份 NDA 的风险点"]
    }
  ],
  "authentication": { "schemes": ["bearer"] }
}
```

<callout emoji="✅"><p>AgentCard 就是 A2A 实现"能力发现"的载体。一个编排 Agent 启动时拉取所有下游 Agent 的 AgentCard，就知道"谁会干什么"，无需硬编码依赖。这和微服务里的服务发现是一个思想，只是描述对象是 Agent 能力。</p></callout>

---

## 三、A2A vs MCP：一张判断图

这是面试高频题，我给你画清楚：

```text
                 MCP  vs  A2A 决策树
 ┌─────────────────────────────────────────────┐
 │ 你的需求是？                                  │
 │                                              │
 │  A. 让模型用"工具/数据"（查库、读文件、调API）│
 │     └─▶ 用 MCP（Host→Server，模型视角）        │
 │                                              │
 │  B. 让一个 Agent 把任务"委派"给另一个 Agent   │
 │     └─▶ 用 A2A（Agent→Agent，协作视角）       │
 │                                              │
 │  C. 既要工具又要协作？                         │
 │     └─▶ A2A 编排 + 每个 Agent 内部用 MCP 接工具│
 └─────────────────────────────────────────────┘

 组合示例：
 ┌────────────┐  A2A   ┌────────────┐
 │ 编排 Agent  │──────▶│ 审查 Agent  │
 └─────┬──────┘        └─────┬──────┘
       │                     │ MCP
       │ MCP                 ▼
       ▼              ┌────────────┐
 ┌────────────┐       │ 合同库Server│
 │ 知识库Server│       └────────────┘
 └────────────┘
```

核心区别再提炼一句：**MCP 是"向下"连资源，A2A 是"横向"连同伴**。一个成熟的多 Agent 系统，两者都要。

---

## 四、最小 A2A 交互流程（含代码思路）

A2A 基于 HTTP + JSON-RPC。给你一条标准交互流：

```text
   客户端 Agent ──▶ 服务端 Agent
   1. GET /.well-known/agent.json   → 拉取 AgentCard（能力发现）
   2. POST /tasks (send)            → 创建 Task，委派任务
   3. 服务端返回 task（status=working）或流式事件
   4. 如需补充信息：status=input-required，客户端补传 Message
   5. 最终 status=completed，返回 Artifact
```

下面给你一个**概念级**的 Java A2A Client 示例（基于 Spring 的 RestClient，思路清晰为主，具体 SDK 以官方为准）：

```java
package com.qiutian.a2a;

import org.springframework.web.client.RestClient;
import java.util.Map;

/** 最小 A2A 客户端：拉 AgentCard + 发起任务 */
public class A2AClient {

    private final RestClient http = RestClient.builder().build();

    /** 1. 能力发现：拉取对方 AgentCard */
    public Map<String, Object> discover(String baseUrl) {
        return http.get()
                .uri(baseUrl + "/.well-known/agent.json")
                .retrieve()
                .body(Map.class);
    }

    /** 2. 发起一个 Task（JSON-RPC 风格） */
    public Map<String, Object> sendTask(String baseUrl, String taskId, String text) {
        Map<String, Object> rpc = Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "tasks/send",
                "params", Map.of(
                        "id", taskId,
                        "message", Map.of(
                                "role", "user",
                                "parts", Map.of("text", text)
                        )
                )
        );
        return http.post()
                .uri(baseUrl + "/tasks")
                .body(rpc)
                .retrieve()
                .body(Map.class);
    }

    public static void main(String[] args) {
        A2AClient client = new A2AClient();
        String peer = "https://agents.internal/contract-review";
        Map<String, Object> card = client.discover(peer);
        System.out.println("对方能力: " + card.get("name"));

        Map<String, Object> result =
                client.sendTask(peer, "task-001", "扫描这份 NDA 的风险点");
        System.out.println("任务结果: " + result);
    }
}
```

<callout emoji="⚠️"><p>注意：上面是"思路级"代码，展示 A2A 的 HTTP + JSON-RPC 形态。生产请用官方 A2A SDK（Google 已开源 `java` 实现）。核心是"先发现（AgentCard）后委派（Task）"两步，牢记这个节奏。</p></callout>

---

## 五、A2A Server 端：怎么暴露一个 Agent

Server 端要干三件事：① 暴露 `/.well-known/agent.json`；② 接收 `/tasks` 请求；③ 把业务结果封成 Artifact。给你一个 Spring Boot Controller 骨架：

```java
package com.qiutian.a2a;

import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
public class A2AServerController {

    // 简化：用内存存 task 状态
    private final Map<String, String> tasks = new ConcurrentHashMap<>();

    /** ① 能力发现端点 */
    @GetMapping("/.well-known/agent.json")
    public Map<String, Object> agentCard() {
        return Map.of(
            "protocolVersion", "0.2.0",
            "name", "合同审查 Agent",
            "description", "审查销售合同中的风险条款",
            "url", "https://agents.internal/contract-review",
            "skills", new Object[]{
                Map.of("id", "risk-scan", "name", "风险扫描",
                       "description", "识别合同中的不公平条款")
            }
        );
    }

    /** ② 接收任务委派 */
    @PostMapping("/tasks")
    public Map<String, Object> handleTask(@RequestBody Map<String, Object> rpc) {
        Map<String, Object> params = (Map<String, Object>) rpc.get("params");
        String taskId = (String) params.get("id");
        // 简化：直接同步返回结果（真实场景应支持流式/异步）
        tasks.put(taskId, "working");
        String result = "发现 3 处风险：免责条款过宽、管辖法院不利、违约金畸高";
        tasks.put(taskId, "completed");
        return Map.of(
            "jsonrpc", "2.0", "id", rpc.get("id"),
            "result", Map.of(
                "id", taskId,
                "status", Map.of("state", "completed"),
                "artifacts", new Object[]{
                    Map.of("name", "risk-report",
                           "parts", Map.of("text", result))
                }
            )
        );
    }
}
```

<callout emoji="💡"><p>真实 A2A Server 还要处理 `input-required`（向客户端要更多信息）、`streaming`（SSE 推进度）、`pushNotifications`（任务完成后反向通知）。但骨架逻辑不变：发现 → 收任务 → 产出 Artifact。先把这个节奏刻进脑子。</p></callout>

---

## 六、标准化趋势：MCP + A2A + 更多

2025-2026 是 Agent 协议"标准化元年"。除了 MCP（Anthropic）和 A2A（Google），还有：

- **AGNTCY**（Cisco 主导的开源 Agent 互操作框架）：偏"多厂商 Agent 网络"；
- **LMOS / 各云厂商私有协议**：阿里、字节、微软也各有 Agent 编排协议；
- **OpenAI 的 Agents SDK / 协议倾向**：生态玩家持续博弈。

给你一张"协议全景"图，看清它们各自占位：

```text
            Agent 协议分层全景
 ┌────────────────────────────────────────────────────┐
 │  Layer 3  Agent 间协作      →  A2A / AGNTCY         │
 │  (谁委派谁、怎么握手、任务生命周期)                  │
 ├────────────────────────────────────────────────────┤
 │  Layer 2  模型连工具/数据    →  MCP                 │
 │  (Resources/Tools/Prompts/Sampling)                 │
 ├────────────────────────────────────────────────────┤
 │  Layer 1  模型推理 API        →  OpenAI/Anthropic/   │
 │  (各家 LLM 的 chat/completions)                     │
 └────────────────────────────────────────────────────┘

 趋势：Layer1 在收敛（OpenAI 格式成事实标准），
       Layer2 MCP 已成事实标准，
       Layer3 A2A 正快速被接纳。
```

<callout emoji="✅"><p>招聘映射：JD 里"熟悉 A2A / 多 Agent 协作协议""有 Agent 标准化互操作经验"，正是指 Layer 3。能把 MCP（Layer2）和 A2A（Layer3）的分工讲清楚，是"Agent 架构师"区别于"普通 Agent 开发"的分水岭。</p></callout>

---

## 七、标准化趋势对 Agent 架构的三重影响

这是这一节的"为什么这样设计"核心。标准化不是技术洁癖，它实实在在地改变了架构决策：

**影响 1：从"单体 Agent"走向"Agent 网络"**
以前一个 Agent 把所有逻辑写死；现在可以"编排 Agent + 多个专精 Agent"，通过 A2A 动态组合。架构从"大而全"变成"小而专 + 可编排"。

```text
   旧架构（单体）              新架构（网络）
 ┌──────────────┐          ┌──────────┐
 │ 全能 Agent    │          │ 编排Agent │──A2A──▶ 审查Agent
 │ 含所有逻辑    │          └──────────┘──A2A──▶ 翻译Agent
 └──────────────┘                        ──A2A──▶ 摘要Agent
   难以复用/扩展                 专精 Agent 可独立演进
```

**影响 2：工具与模型彻底解耦，多模型策略变简单**
因为工具在 MCP Server，换模型不影响工具；因为 Agent 通过 A2A 协作，换某个 Agent 的实现不影响整体。企业可以做"成本路由"——简单任务用小模型 Agent，复杂任务用大模型 Agent，全程对用户透明。

**影响 3：治理与审计有了统一抓手**
标准化后，所有工具调用走 MCP、所有 Agent 协作走 A2A，天然留下统一的调用链路。这就把第十二章要讲的"行为审计"从"每个框架各做各的"变成"协议层统一可观测"。

<callout emoji="⚠️"><p>但秋天要泼盆冷水：标准化≠成熟。A2A 还在快速演进，SDK 和最佳实践远不如 MCP 稳定。企业落地时别一上来就全量 A2A 化，先用 MCP 把工具标准化（收益立竿见影），A2A 在"确实需要多 Agent 协作"的边界再引入。</p></callout>

---

## 八、企业落地场景：数字员工流水线

给你一个真实感强的例子。某电商"数字员工"处理客诉：

```text
   客诉处理数字员工（A2A 编排）
 ┌─────────────────────────────────────────────┐
 │ 编排 Agent（接收客诉文本）                     │
 │   │ A2A 委派                                  │
 │   ├─▶ 情绪分析 Agent   （判断用户愤怒等级）    │
 │   ├─▶ 订单查询 Agent    （MCP 连订单 Server）  │
 │   ├─▶ 补偿策略 Agent    （按规则生成方案）     │
 │   └─▶ 话术生成 Agent    （生成回复）          │
 │   ▼                                          │
 │ 汇总成"客诉处理单"返回人工坐席                 │
 └─────────────────────────────────────────────┘

 价值：每个专精 Agent 由不同团队维护（NLP 组、订单组、风控组），
       通过 A2A 标准协作，互不耦合；新增"合规审查 Agent"
       只需接入 A2A，不动其他 Agent。
```

---

## 九、招聘里的对应要求怎么写进简历

给你一段可直接借鉴的项目描述写法：

```text
【项目】基于 MCP + A2A 的企业 Agent 互操作平台
【职责】
- 基于 MCP 协议将 12 个内部系统封装为标准工具服务，消除框架私有集成；
- 基于 A2A 协议设计多 Agent 编排层，实现客诉/合同等 5 条业务流水线的动态协作；
- 统一工具调用与 Agent 协作的链路追踪，支撑审计与成本治理。
【成果】工具复用率提升 60%，新业务接入周期从 2 周降至 3 天。
```

<callout emoji="💡"><p>数字要真实或合理估算，别编。但"MCP + A2A + 互操作 + 审计"这几个关键词凑齐，这份简历在 2026 的 Agent 岗位里绝对是头部水平。</p></callout>

---

## 十、A2A 与 MCP 的协同部署实战

生产里 A2A 和 MCP 不是二选一，而是叠加使用。给你一个完整的"编排 Agent + 专精 Agent + 工具"部署图：

```text
           协同部署（真实生产形态）
 ┌────────────────────────────────────────────────────┐
 │ 编排 Agent (Host)                                  │
 │   │ A2A (HTTP/JSON-RPC)                            │
 │   ├──────────────▶ 合同审查 Agent                  │
 │   │                    │ MCP                       │
 │   │                    ▼                          │
 │   │              ┌──────────────┐                  │
 │   │              │ 合同库 Server │                  │
 │   ├──────────────▶ 翻译 Agent                      │
 │   │                    │ MCP                       │
 │   │                    ▼                          │
 │   │              ┌──────────────┐                  │
 │   │              │ 术语库 Server │                  │
 │   └──────────────▶ 摘要 Agent                      │
 └────────────────────────────────────────────────────┘
 编排层用 A2A 协调"谁做什么"，每个 Agent 内部用 MCP 接工具。
```

对应到 Java 代码，编排 Agent 里既要建 A2A Client（连同伴），也要建 MCP Client（连工具）：

```java
// 编排 Agent 的两种连接
A2AClient a2a = new A2AClient();           // 连其他 Agent
List<McpTransport> tools = List.of(         // 连自己的工具
    new StdioMcpTransport.Builder()
        .command(List.of("java","-jar","kb-server.jar")).build());
// 任务先 A2A 委派给专精 Agent，专精 Agent 内部再用 MCP 调工具
```

<callout emoji="💡"><p>记住口诀："纵向 MCP，横向 A2A"。纵向是 Agent 往下连资源，横向是 Agent 之间协作。这套组合是企业多 Agent 系统的标准骨架。</p></callout>

---

## 十一、A2A 的任务生命周期状态机

A2A 的 Task 有明确的生命周期，理解它能帮你做正确的超时与重试：

```text
   Task 状态机
 ┌─────────┐   submit   ┌──────────┐
 │ (none)  │──────────▶│ submitted│
 └─────────┘           └────┬─────┘
                            │ 开始处理
                            ▼
                       ┌──────────┐  需补充信息  ┌──────────────┐
                       │ working  │────────────▶│ input-required│
                       └────┬─────┘◀────────────└──────┬───────┘
                            │ 完成                    │ 补传后回到 working
                            ▼                         │
                       ┌──────────┐                  │
                       │completed │◀─────────────────┘
                       └──────────┘
                            │ 或失败
                            ▼
                       ┌──────────┐
                       │  failed   │
                       └──────────┘
```

```java
// 处理 input-required：客户端补传后再提交
if ("input-required".equals(state)) {
    a2a.sendTask(taskId, "补充材料：客户已授权书见附件");
}
```

---

## 十二、流式（Streaming）与推送（Push）通知

长任务不能让客户端干等。A2A 支持两种推进度方式：

| 方式 | 机制 | 适用 |
|------|------|------|
| Streaming (SSE) | Server 用 SSE 持续推送中间状态 | 客户端在线、要实时进度 |
| Push Notification | 任务完成后 Server 主动回调 URL | 客户端离线/异步场景 |

```yaml
# A2A Server 开启推送的配置思路
a2a:
  server:
    push-notifications:
      enabled: true
      callback-whitelist:
        - https://agent-host.internal/callbacks
```

---

## 十三、标准化趋势下的"协议治理"组织保障

技术标准化了，组织也得跟上，否则又是各自为政：

```text
   协议治理三件套
 ┌──────────────────────────────────────────────────┐
 │ 1. 协议委员会：统一 protocolVersion、命名规范      │
 │ 2. Agent 注册中心：所有 AgentCard 集中登记可发现   │
 │ 3. 跨团队契约测试：Agent 间/Agent-工具 接口变更告警 │
 └──────────────────────────────────────────────────┘
```

<callout emoji="⚠️"><p>协议标准是技术，但"谁来管版本、谁来审契约"是组织问题。大厂都有专门的"Agent/AI 平台"团队做协议治理，否则半年后又是 N 套私 dialect。</p></callout>

---

## 十四、招聘深挖：A2A 和 MCP 你都会，怎么证明

```text
【简历写法】
"基于 A2A 设计多 Agent 编排层，基于 MCP 标准化工具，
 实现跨框架互操作；任务链路全托管于协议层，
 新增业务 Agent 接入周期从 2 周降至 3 天。"

【面试追问】
Q：A2A 和 MCP 能不能合并成一个协议？
A：理论上能，但职责不同——MCP 是"模型视角连资源"，
  A2A 是"Agent 视角连同伴"，合并会模糊边界、增加耦合，
  所以业界选择分层而非合并。
```

---

## 小结

这一节我们把视野从"模型连工具"抬升到"Agent 连 Agent"：

- **MCP vs A2A**：MCP 管模型连工具/数据（向下），A2A 管 Agent 连 Agent（横向），互补；
- **A2A 核心**：AgentCard（能力发现）、Task（任务生命周期）、Message/Artifact（内容/产出）；
- **代码节奏**：先 `discover` 拉 AgentCard，再 `tasks/send` 委派，最后收 Artifact；
- **标准化趋势**：Layer1 收敛、Layer2 MCP 成事实标准、Layer3 A2A 快速被接纳；
- **架构影响**：单体→网络、模型与工具解耦、治理有了统一抓手；
- **落地策略**：先用 MCP 标准化工具（收益快），A2A 在真有多 Agent 协作时再上。

到这里，第十一章"MCP 与标准化"四节全部讲完。你已经具备协议层的完整认知，下一章我们进入"安全与治理"——这是 Agent 能不能上生产的生死线。

---

## 延伸阅读 / 引用文档

1. Google A2A 协议官方仓库：https://github.com/google/A2A
2. A2A 协议规范文档：https://google.github.io/A2A/
3. MCP 官方规范（对照理解 Layer2）：https://modelcontextprotocol.io/specification
4. AGNTCY 开源 Agent 互操作框架（Cisco）：https://github.com/agntcy
5. Google 博客：Announcing the Agent2Agent Protocol (A2A)：https://developers.googleblog.com/
6. Spring AI 官方文档（MCP 集成参考）：https://docs.spring.io/spring-ai/reference/1.0/

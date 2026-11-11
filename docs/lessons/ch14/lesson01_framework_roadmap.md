# 第十四章第1节：主流框架演进路线图：LangChain4j、Spring AI、Google ADK、AgentScope Java 的近期发展与规划

大家好，我是秋天。

我们这门课前面用了 LangChain4j、Spring AI、Google ADK、AgentScope 四个框架写代码。最后一章，我带你跳出"怎么用"，看"它们往哪去"——框架的演进路线图，本质上是 Agent 技术趋势的晴雨表。你做技术选型、做职业规划时，看懂路线图比背 API 重要十倍。

学完这一节，你将掌握：

1. 四个主流 Java 框架的定位差异与演进方向；
2. 它们各自最该关注的近期能力（MCP、Graph、ADK、多智能体）；
3. 如何根据路线图判断"该押注哪个框架"；
4. 框架演进背后反映的 Agent 技术大势；
5. 招聘里"框架广度 + 趋势判断"对应的岗位要求。

---

## 一、先给四框架定位

别混。四个框架性格完全不同：

```text
   四框架定位图
 ┌────────────────────────────────────────────────────┐
 │ LangChain4j   → "灵活积木"，生态最全，适合深度定制   │
 │ Spring AI     → "企业首选"，和 Spring 全家桶无缝      │
 │ Google ADK    → "Google 血缘"，强在多 Agent + Vertex  │
 │ AgentScope    → "阿里系多智能体"，ReAct/ReMe 见长    │
 └────────────────────────────────────────────────────┘
 一句话：要灵活选 LangChain4j，要企业落地选 Spring AI，
         要 Google 云选 ADK，要多智能体研究选 AgentScope。
```

<callout emoji="💡"><p>秋天提醒：框架会过时，但"框架解决什么问题的思路"不过时。这一节你真正要学的是"看路线图的方法"，不是记版本号。版本号以官网为准。</p></callout>

---

## 二、LangChain4j：从"链"到"Agent + MCP"

LangChain4j 的起点是"Chain"（把模型调用串起来），演进方向：

```text
   LangChain4j 演进路线
 ┌──────────────────────────────────────────────────┐
 │ 早期：Chain / AiServices（声明式 Agent）          │
 │   ↓ 补记忆、补工具、补 RAG                        │
 │ 当下：MCP 集成、多模态、@Memory 注解              │
 │   ↓ 对齐协议层                                    │
 │ 趋势：标准化（MCP Client/Server）、 Agent 编排     │
 └──────────────────────────────────────────────────┘
```

近期重点能力（代码示例，MCP 接入，第三章/十一章讲过）：

```java
// LangChain4j 当前主推：AiServices + MCP 工具
Assistant assistant = AiServices.builder(Assistant.class)
        .chatLanguageModel(model)
        .toolSpecifications(mcpToolSpecs)  // 接 MCP，呼应标准化趋势
        .build();
```

<callout emoji="⚠️"><p>LangChain4j 的优势在"什么都有"，劣势也在"什么都有"——抽象层多、学习曲线陡。选型时如果团队不熟悉，Spring AI 更顺手。</p></callout>

---

## 三、Spring AI：企业落地 + Alibaba 扩展

Spring AI 的演进最贴近"企业工程化"，尤其国内：

```text
   Spring AI 演进路线
 ┌──────────────────────────────────────────────────┐
 │ 1.0：ChatClient 统一抽象（一套 API 接多家模型）   │
 │   ↓ 企业需求驱动                                  │
 │ 1.x：MCP 客户端/服务端、Advisor（拦截链）         │
 │   ↓ 国内生态                                       │
 │ Spring AI Alibaba：Graph 编排、DashScope、Nacos、  │
 │   Higress、Prompt 管理、RAG、可观测               │
 │   ↓ 趋势                                          │
 │ 平台化：把 Agent 当"企业服务"治理（限流/审计/监控）│
 └──────────────────────────────────────────────────┘
```

给你一个体现"演进方向"的 Spring AI Alibaba Graph 编排片段（概念级）：

```java
// Spring AI Alibaba Graph：用代码声明多节点工作流
// 反映"从单 Agent 到可编排流水线"的演进
StateGraph graph = new StateGraph("customer-complaint")
        .addNode("classify", classifyNode)
        .addNode("queryOrder", queryOrderNode)   // 可接 MCP
        .addNode("reply", replyNode)
        .addEdge("classify", "queryOrder")
        .addEdge("queryOrder", "reply")
        .addEdge(START, "classify");
GraphAgent agent = graph.compile();
```

<callout emoji="✅"><p>Spring AI 的路线图强烈指向"企业平台化"——Graph 编排 + Nacos 注册 + Higress 网关 + 可观测，把 Agent 当成和企业微服务同等级别的基础设施治理。这在国内大厂落地需求里是刚需。</p></callout>

---

## 四、Google ADK for Java：原生多 Agent + 云原生

ADK（Agent Development Kit）是 Google 2025 推出的，定位"为生产级 Agent 而生"：

```text
   Google ADK 演进路线
 ┌──────────────────────────────────────────────────┐
 │ 核心：Agent 原语（LLM/Sequential/Loop/Parallel）   │
 │   ↓ 内置工具（Google Search、Code Interpreter）    │
 │   ↓ Vertex AI 部署（企业级伸缩、评估、监控）        │
 │ 趋势：Agent 即服务、与 Google Cloud 深度绑定       │
 └──────────────────────────────────────────────────┘
```

ADK 的"循环 + 并行 Agent"是它特色（第五章讲过）：

```java
// ADK 的 LoopAgent：直到条件满足反复执行
LoopAgent reviewLoop = LoopAgent.builder()
        .name("self-review-loop")
        .maxIterations(5)
        .subAgents(generateAgent, critiqueAgent)
        .build();
```

<callout emoji="💡"><p>ADK 适合"已经在 Google Cloud 栈里"的团队。它的强项是把 Agent 直接对接 Vertex AI 的评估/部署/监控，省去大量平台工程。但绑定云厂商，迁移成本要考虑。</p></callout>

---

## 五、AgentScope Java：多智能体 + 记忆创新

AgentScope（ModelScope 出品）路线偏"多智能体研究与创新"：

```text
   AgentScope 演进路线
 ┌──────────────────────────────────────────────────┐
 │ 基础：ReAct Agent、消息传递、多智能体协作          │
 │   ↓ 记忆创新                                      │
 │ ReMe 项目（智能体记忆机制，第六章提过）            │
 │   ↓ 趋势                                          │
 │ 更灵活的多智能体拓扑 + 记忆/反思增强               │
 └──────────────────────────────────────────────────┘
```

---

## 六、横向对比：怎么看路线图选框架

给你一张总表，写选型报告直接用：

| 框架 | 最强项 | 近期重点 | 适合谁 | 风险 |
|------|--------|----------|--------|------|
| LangChain4j | 生态灵活 | MCP/多模态 | 要深度定制 | 抽象多、偏陡 |
| Spring AI | 企业落地 | Graph/Alibaba/Nacos | 国内企业 | 国内生态依赖 |
| Google ADK | 云原生多 Agent | Vertex/并行循环 | GCP 栈 | 云绑定 |
| AgentScope | 多智能体研究 | ReMe 记忆 | 研究/创新 | 企业成熟度 |

<callout emoji="⚠️"><p>选型铁律：**先看团队和基础设施，再看框架特性**。团队全是 Spring 老兵 → Spring AI；已经在 GCP → ADK；要做前沿多智能体研究 → AgentScope。别为了"框架酷"推翻团队栈。</p></callout>

---

## 七、框架演进揭示的 Agent 技术大势

把四个框架的路线图叠起来，你能看出行业共识：

```text
   框架路线图共同指向的 5 大趋势
 ┌──────────────────────────────────────────────────┐
 │ 1. 协议标准化：全都拥抱 MCP（Layer2）              │
 │ 2. 编排显式化：从隐式链到显式 Graph/StateMachine   │
 │ 3. 多 Agent 化：从单 Agent 到 Supervisor/并行      │
 │ 4. 平台工程化：限流/审计/监控/注册成为一等公民     │
 │ 5. 云原生化：Agent 当服务部署、评估、伸缩          │
 └──────────────────────────────────────────────────┘
```

<callout emoji="💡"><p>这 5 条就是你看任何框架、任何招聘 JD 的"解码器"。JD 里写的"熟悉 Agent 编排/平台工程/MCP"，本质就是这 5 条。你能把框架路线图映射到趋势，面试谈薪都硬气。</p></callout>

---

## 八、招聘映射

```text
【岗位】AI Agent 技术专家 / 架构师
【要求】
- 熟悉主流 Java Agent 框架（LangChain4j/Spring AI/ADK 等）至少 2 种；
- 能根据业务与基础设施合理选型，并预判框架演进影响；
- 有 Agent 平台化（编排/审计/监控）落地经验。
```

<callout emoji="✅"><p>框架广度 + 趋势判断，是"专家"和"会用一种框架的人"的区别。这一节给你的是"看路线图的方法"，比记版本号有用一辈子。</p></callout>

---

## 八、各框架最小依赖对照（Maven）

给你一张"起手依赖"速查，做选型 POC 时直接抄：

```xml
<!-- LangChain4j 起手 -->
<dependency>
  <groupId>dev.langchain4j</groupId>
  <artifactId>langchain4j-open-ai</artifactId>
  <version>1.0.0</version>
</dependency>
<dependency>
  <groupId>dev.langchain4j</groupId>
  <artifactId>langchain4j-mcp</artifactId>
  <version>1.0.0</version>
</dependency>

<!-- Spring AI 起手 -->
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-starter-model-openai</artifactId>
  <version>1.0.0</version>
</dependency>
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-mcp</artifactId>
  <version>1.0.0</version>
</dependency>

<!-- Google ADK for Java 起手 -->
<dependency>
  <groupId>com.google.adk</groupId>
  <artifactId>google-adk</artifactId>
  <version>1.0.0</version>
</dependency>
```

<callout emoji="💡"><p>版本号以 Maven Central / 官方为准。这张表的重点是"起手成本"——LangChain4j 和 Spring AI 都是单依赖即可跑，ADK 偏云原生。起手成本也影响团队学习曲线。</p></callout>

---

## 九、框架迁移成本分析

你迟早会遇到"换框架"。迁移成本取决于"耦合深度"：

```text
   耦合深度 → 迁移成本
 ┌──────────────────────────────────────────────────┐
 │ 浅耦合（只用了 ChatClient/AiServices 接口）       │
 │   → 换框架改适配层即可，业务代码不动（低成本）    │
 │ 深耦合（大量用框架私有注解/内部 API）             │
 │   → 几乎重写（高成本）                            │
 │ 结论：业务只依赖"平台抽象层"，框架是替换件       │
 └──────────────────────────────────────────────────┘
```

呼应 lesson03 的"适配层解耦"——把框架当插件，才是抗演进的正确姿势。

---

## 十、国内 vs 海外选型差异

给你一个地域视角的选型参考（企业落地常受此影响）：

| 维度 | 国内企业倾向 | 海外/出海企业倾向 |
|------|--------------|-------------------|
| 主框架 | Spring AI + Alibaba 生态 | LangChain4j / ADK |
| 模型 | Qwen / DeepSeek / 智谱 | GPT / Claude / Gemini |
| 基础设施 | 阿里云/腾讯云/私有云 | GCP / AWS |
| 合规 | 等保/生成式管理办法 | GDPR / 行业监管 |
| 协议 | MCP 同样拥抱 | MCP + A2A |

<callout emoji="⚠️"><p>选型不能脱离"你在哪、用什么云、受什么监管"。一个在私有云跑政企项目的团队，硬上 ADK（强绑 GCP）是不现实的。务实优先。</p></callout>

---

## 十一、框架演进时间线（认知锚点）

给你一条示意时间线，理解"为什么现在长这样"：

```text
   演进示意时间线
 ──────────────────────────────────────────────►
 2023  Chain 为主（LangChain 系）
 2024  Agent/工具调用成熟，Spring AI 1.0 起势
 2024末 MCP 发布 → 协议层崛起
 2025  ADK 发布、Spring AI Alibaba 生态、A2A 发布
 2026  平台工程化 + 上下文工程 + 评测闭环成主流
   ↑ 你处在"协议+平台"时代，框架之争退居次席
```

---

## 十二、面试追问：你更看好哪个框架

```text
问：LangChain4j、Spring AI、ADK 你更看好哪个？
答：都不"押注"——它们是协议层（MCP/A2A）上的不同壳。
   我更关注底层能力（编排/记忆/治理）是否扎实、是否契合团队与基础设施。
   当前国内企业落地我主推 Spring AI + Alibaba 生态（中台契合），
   但业务若深度在 GCP，ADK 更顺。选型按"团队+基础设施"而非"喜好"。
```

---

## 小结

这一节我们梳理了四大 Java Agent 框架的演进：

- **定位**：LangChain4j（灵活）、Spring AI（企业）、ADK（GCP 多 Agent）、AgentScope（多智能体研究）；
- **各自重点**：LangChain4j→MCP、Spring AI→Graph/Alibaba、ADK→Vertex/循环并行、AgentScope→ReMe 记忆；
- **选型**：先看团队与基础设施，再看特性，不为酷推翻栈；
- **大势**：标准化、编排显式化、多 Agent、平台工程化、云原生化五趋势；
- **方法**：学会看路线图，比记版本号更重要。

下一节我们看"新兴框架与趋势"——OpenClaw Java 客户端、MCP 生态崛起，以及哪些信号值得你提前关注。

---

## 延伸阅读 / 引用文档

1. LangChain4j 官方文档与发布说明：https://docs.langchain4j.dev/
2. Spring AI 官方文档：https://docs.spring.io/spring-ai/reference/1.0/
3. Spring AI Alibaba 官网：https://spring-ai-alibaba.github.io/spring-ai-alibaba/
4. Google ADK for Java 文档：https://google.github.io/adk-docs/
5. AgentScope GitHub：https://github.com/modelscope/agentscope
6. MCP 官方（协议生态总入口）：https://modelcontextprotocol.io/

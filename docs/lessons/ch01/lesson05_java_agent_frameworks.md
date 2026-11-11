# 第1章第5节：Java主流Agent框架全景概览：LangChain4j、Spring AI、AgentScope Java、Google ADK for Java、OpenClaw对接能力

大家好，我是秋天。

这一节课，我们来做一件非常实用的事情：

**把 Java 生态里目前最主流的几个 Agent 框架，放在一起系统梳理一遍。**

很多同学在学习 Agent 时，会陷入一个困境：框架太多，不知道从哪里入手，也不清楚它们之间有什么本质区别，更不知道自己的项目到底该选哪个。

这节课，我们就来解决这个问题。

我们会覆盖五个框架：

- **LangChain4j**：Java 生态里最早成熟、社区最活跃的 Agent 框架
- **Spring AI**：Spring 官方推出的 AI 集成框架，面向 Spring 开发者
- **AgentScope Java**：阿里巴巴开源的多 Agent 协作框架
- **Google ADK for Java**：Google 推出的 Agent 开发套件
- **OpenClaw**：专注企业级 Java Agent 能力的新兴框架

学完这节课，你会对 Java Agent 框架的整体格局有一个清晰认知，也能在面对具体项目时，快速判断哪个框架最合适。

---

## 一、为什么 Java 需要专门的 Agent 框架

在回答"选哪个框架"之前，我们先回答一个更根本的问题：

**为什么不直接用 HTTP 调 LLM，还需要 Agent 框架？**

上一节课我们讲了四种 HTTP 客户端，它们解决的是"怎么把请求发出去"的问题。

但一个完整的 Agent 系统，还需要解决更多问题：

- **工具调用**：怎么定义工具、怎么让模型知道工具存在、怎么解析模型返回的工具调用请求、怎么执行并把结果返回给模型
- **记忆管理**：多轮对话怎么维护上下文、长期记忆怎么存储和检索
- **提示词管理**：系统提示词怎么组织、模板怎么注入变量
- **多步推理**：怎么让模型在一个任务里循环思考和行动
- **多 Agent 协作**：多个 Agent 怎么分工、怎么通信、怎么协调
- **可观测性**：请求链路怎么追踪、Token 消耗怎么统计

这些能力如果全部自己实现，工作量非常大，而且容易出错。

Agent 框架的价值，就是把这些通用能力封装好，让你能专注在业务逻辑上。

<callout emoji="💡">
Agent 框架不是魔法，它是把"模型调用 + 工具管理 + 记忆 + 编排"这些工程能力打包好的基础设施。选对框架，能让你少走很多弯路。
</callout>

---

## 二、五个框架先给结论版

| **框架** | **背后团队** | **核心定位** | **最适合场景** | **推荐度** |
|-|-|-|-|-|
| LangChain4j | 开源社区 | 功能最全、生态最广的 Java Agent 框架 | 新项目首选、RAG、工具调用、多模型 | 很高 |
| Spring AI | Spring 官方 | 面向 Spring 开发者的 AI 抽象层 | 已有 Spring Boot 项目快速接入 AI | 很高 |
| AgentScope Java | 阿里巴巴 | 多 Agent 协作与编排 | 复杂多 Agent 工作流、企业内部系统 | 中高 |
| Google ADK for Java | Google | 标准化 Agent 开发套件 | Google Cloud / Vertex AI 生态、标准化要求高 | 中高 |
| OpenClaw | 开源社区 | 企业级 Java Agent 能力平台 | 企业私有化部署、多模型网关对接 | 中 |

---

## 三、LangChain4j：Java Agent 生态的先行者

### 1. 它是什么

LangChain4j 是 LangChain 在 Java 生态的实现，是目前 Java 里功能最完整、社区最活跃的 Agent 框架。

它覆盖了 Agent 开发的几乎所有核心能力：

- 多模型集成（OpenAI、Anthropic、Azure、Ollama 等）
- 工具调用（Function Calling）
- RAG（检索增强生成）
- 记忆管理
- 提示词模板
- 输出解析

### 2. 核心设计：AiServices

LangChain4j 最有特色的设计是 **AiServices**——用声明式接口定义 Agent，框架自动生成实现。

```Java
// 定义一个 Agent 接口
interface AssistantAgent {
    String chat(String userMessage);
}

```

```Java
// 框架自动生成实现，绑定模型和工具
AssistantAgent agent = AiServices.builder(AssistantAgent.class)
        .chatLanguageModel(model)
        .tools(new SearchTool(), new CalculatorTool())
        .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
        .build();

String response = agent.chat("帮我搜索一下最新的 Java 21 新特性");
System.out.println(response);

```

### 3. 工具定义方式

```Java
public class SearchTool {

    @Tool("搜索互联网上的最新信息")
    public String search(@P("搜索关键词") String query) {
        // 实际调用搜索 API
        return "搜索结果：" + query + " 的相关内容";
    }
}

```

这种方式非常 Java 化——用注解声明工具，框架自动把工具描述注入到模型的系统提示词里，并在模型调用工具时自动路由执行。

### 4. RAG 支持

```Java
// 构建知识库
EmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();
EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
        .embeddingStore(embeddingStore)
        .embeddingModel(embeddingModel)
        .build();

ingestor.ingest(Document.from("Java 21 引入了虚拟线程，大幅提升并发性能..."));

// 构建带 RAG 的 Agent
AssistantAgent agent = AiServices.builder(AssistantAgent.class)
        .chatLanguageModel(model)
        .contentRetriever(EmbeddingStoreContentRetriever.from(embeddingStore))
        .build();

```

### 5. 适合场景和参考文档

**适合**：新项目首选、需要 RAG、需要工具调用、需要多模型支持、追求生态成熟度。

- 官方文档：https://docs.langchain4j.dev
- GitHub：https://github.com/langchain4j/langchain4j
- Maven 依赖：`dev.langchain4j:langchain4j:0.36.0`

---

## 四、Spring AI：Spring 开发者的首选路径

### 1. 它是什么

Spring AI 是 Spring 官方推出的 AI 集成框架，目标是让 Spring 开发者能用熟悉的编程模型接入 AI 能力。

它的核心理念是：**AI 能力应该像数据库、消息队列一样，成为 Spring 应用的标准基础设施。**

### 2. ChatClient：流畅的链式 API

Spring AI 的核心入口是 `ChatClient`，设计风格和 Spring WebClient 非常一致：

```Java
@Service
public class AgentService {

    private final ChatClient chatClient;

    public AgentService(ChatClient.Builder builder) {
        this.chatClient = builder
                .defaultSystem("你是一个专业的 Java 架构师助手，回答要简洁准确")
                .build();
    }

    public String ask(String question) {
        return chatClient.prompt()
                .user(question)
                .call()
                .content();
    }
}

```

### 3. 函数调用（工具）

```Java
@Bean
@Description("获取指定城市的当前天气")
public Function<WeatherRequest, WeatherResponse> weatherFunction() {
    return request -> {
        // 调用天气 API
        return new WeatherResponse(request.city(), "晴天", 25);
    };
}

```

```Java
// 在调用时注入工具
String result = chatClient.prompt()
        .user("北京今天天气怎么样？")
        .functions("weatherFunction")
        .call()
        .content();

```

### 4. 结构化输出

Spring AI 支持把模型输出直接映射到 Java 对象：

```Java
record AgentPlan(String goal, List<String> steps, String estimatedTime) {}

AgentPlan plan = chatClient.prompt()
        .user("帮我制定一个学习 Java Agent 的计划")
        .call()
        .entity(AgentPlan.class);

System.out.println("目标：" + plan.goal());
System.out.println("步骤：" + plan.steps());

```

### 5. 适合场景和参考文档

**适合**：已有 Spring Boot 项目、团队熟悉 Spring 生态、希望快速接入 AI 能力、对框架统一性要求高。

- 官方文档：https://docs.spring.io/spring-ai/reference/
- GitHub：https://github.com/spring-projects/spring-ai
- Maven 依赖：`org.springframework.ai:spring-ai-openai-spring-boot-starter`

<callout emoji="🎁">
如果你的项目已经是 Spring Boot，Spring AI 是阻力最小的接入路径。你不需要学习全新的框架，只需要在已有体系里加几个 Bean 就能跑起来。
</callout>

---

## 五、AgentScope Java：多 Agent 协作的专业选手

### 1. 它是什么

AgentScope 是阿里巴巴开源的 Agent 框架，Java 版本专注于多 Agent 协作场景。

它的核心思想是：把复杂任务分解给多个专门的 Agent，通过消息传递和协调机制完成协作。

### 2. 核心概念

AgentScope 中，每个 Agent 是一个独立的处理单元：

```Java
// 定义一个研究 Agent
public class ResearchAgent extends AgentBase {

    @Override
    public Message reply(Message message) {
        String query = message.getContent().toString();
        // 执行搜索和信息收集
        String result = doResearch(query);
        return Message.builder()
                .name(this.name())
                .content(result)
                .build();
    }
}

```

```Java
// 定义一个写作 Agent
public class WriterAgent extends AgentBase {

    @Override
    public Message reply(Message message) {
        String research = message.getContent().toString();
        // 基于研究结果生成报告
        String report = generateReport(research);
        return Message.builder()
                .name(this.name())
                .content(report)
                .build();
    }
}

```

```Java
// 编排多个 Agent 协作
ResearchAgent researcher = new ResearchAgent("researcher", modelConfig);
WriterAgent writer = new WriterAgent("writer", modelConfig);

// 顺序协作：研究 → 写作
Message task = Message.builder().content("分析 Java Agent 框架的发展趋势").build();
Message researchResult = researcher.reply(task);
Message finalReport = writer.reply(researchResult);

```

### 3. 适合场景和参考文档

**适合**：复杂多 Agent 工作流、需要角色分工的任务（如研究+写作+审核）、阿里云生态用户。

- GitHub：https://github.com/modelscope/agentscope
- 文档：https://agentscope.io

---

## 六、Google ADK for Java：标准化的 Agent 开发套件

### 1. 它是什么

Google Agent Development Kit（ADK）是 Google 推出的 Agent 开发框架，Java 版本与 Vertex AI 和 Google Cloud 深度集成。

它的定位是：**为企业级 Agent 开发提供标准化的构建方式。**

### 2. Agent 定义方式

```Java
// 使用 ADK 定义一个基础 Agent
Agent agent = Agent.newBuilder()
        .name("java-assistant")
        .model("gemini-2.0-flash")
        .description("一个专业的 Java 开发助手")
        .instruction("你是一个资深 Java 架构师，专注于帮助开发者解决实际工程问题")
        .tools(AgentTool.fromAgent(searchAgent))
        .build();

```

```Java
// 运行 Agent
try (Runner runner = new Runner(agent, "user-001", null, sessionService)) {
    Content userMessage = ContentMaker.fromMultiModalData("如何在 Java 中实现流式调用 LLM？");
    runner.runAsync("session-001", userMessage).forEach(event -> {
        if (event.hasContent()) {
            System.out.println(event.getContent());
        }
    });
}

```

### 3. 内置工具集成

ADK 提供了一系列开箱即用的工具：

```Java
// Google Search 工具
Tool googleSearch = new GoogleSearchTool();

// 代码执行工具
Tool codeExecutor = new BuiltInCodeExecutionTool();

Agent agent = Agent.newBuilder()
        .name("research-agent")
        .model("gemini-2.0-flash")
        .tools(googleSearch, codeExecutor)
        .build();

```

### 4. 适合场景和参考文档

**适合**：Google Cloud / Vertex AI 用户、需要与 Google 服务深度集成、对标准化和企业级支持要求高。

- 官方文档：https://google.github.io/adk-docs/
- GitHub：https://github.com/google/adk-java
- Maven 依赖：`com.google.adk:google-adk:0.1.0`

---

## 七、OpenClaw：面向企业的 Java Agent 平台

### 1. 它是什么

OpenClaw 是一个专注企业级 Java Agent 能力的框架，强调：

- 多模型网关统一对接
- 企业私有化部署支持
- 安全与权限管控
- 与现有 Java 企业系统的低成本集成

### 2. 核心设计思路

OpenClaw 把模型接入抽象成统一网关，无论后端是 OpenAI、Anthropic、本地模型还是企业自研模型，对业务层都暴露统一接口：

```Java
// 统一的模型调用接口
OpenClawClient client = OpenClawClient.builder()
        .gateway("https://your-gateway.internal")
        .apiKey(System.getenv("OPENCLW_API_KEY"))
        .build();

ChatResponse response = client.chat()
        .model("gpt-4o")          // 或切换为 claude-sonnet-4-6、本地模型
        .message("分析这段 Java 代码的性能问题")
        .call();

```

### 3. 适合场景

**适合**：企业私有化部署、需要统一多模型网关、对安全合规要求高、希望低成本对接现有 Java 系统。

- GitHub：https://github.com/openclaw/openclaw

---

## 八、如何选择：给你一个实战决策框架

选框架不是选"哪个最强"，而是选"哪个最适合你现在的处境"。

### 情况一：你是 Spring Boot 项目，想快速接入 AI

**首选 Spring AI**

理由：零学习成本迁移、和现有 Spring Bean 体系无缝融合、官方长期维护。

### 情况二：你在做一个全新的 Java AI 项目，需要完整 Agent 能力

**首选 LangChain4j**

理由：功能最全、文档最完善、社区最活跃、RAG 和工具调用能力成熟。

### 情况三：你要做复杂的多 Agent 协作系统

**考虑 AgentScope Java 或 LangChain4j + Spring AI Alibaba Graph**

理由：多 Agent 编排是这两者的强项，角色分工、消息路由、状态管理都有完整支持。

### 情况四：你在 Google Cloud 体系，或者对标准化要求很高

**首选 Google ADK for Java**

理由：与 Vertex AI 深度集成，企业级支持有保障，符合 Google 的技术标准。

### 情况五：你在做企业私有化部署，需要统一多模型网关

**考虑 OpenClaw**

理由：专为企业私有化设计，安全合规能力强，对接多模型网关有成熟方案。

<callout emoji="✅">
对于大多数 Java 开发者来说，**LangChain4j + Spring AI** 的组合是最值得深入学习的路径。LangChain4j 给你完整的 Agent 能力，Spring AI 让你在 Spring 体系里用得顺手。
</callout>

---

## 九、框架能力横向对比

| **能力** | **LangChain4j** | **Spring AI** | **AgentScope Java** | **Google ADK** | **OpenClaw** |
|-|-|-|-|-|-|
| 多模型支持 | ✅ 很强 | ✅ 强 | ✅ 支持 | ⚠️ 偏 Google | ✅ 网关统一 |
| 工具调用 | ✅ 很成熟 | ✅ 成熟 | ✅ 支持 | ✅ 内置工具丰富 | ⚠️ 基础支持 |
| RAG 支持 | ✅ 很强 | ✅ 强 | ⚠️ 一般 | ⚠️ 依赖 Google | ⚠️ 基础支持 |
| 多 Agent 协作 | ⚠️ 一般 | ✅ Spring AI Graph | ✅ 很强 | ✅ 支持 | ⚠️ 一般 |
| Spring 集成 | ✅ 支持 | ✅ 原生 | ⚠️ 需适配 | ⚠️ 需适配 | ✅ 支持 |
| 企业私有化 | ✅ 支持 | ✅ 支持 | ✅ 支持 | ⚠️ 偏云端 | ✅ 很强 |

---

## 十、本节小结

这一节课，我们系统梳理了 Java 生态里五个主流 Agent 框架的定位、核心能力和适用场景。

核心结论：

- **LangChain4j**：功能最全，新项目首选，RAG 和工具调用能力成熟
- **Spring AI**：Spring 开发者最低阻力的接入路径，官方背书
- **AgentScope Java**：多 Agent 协作的专业选手，适合复杂工作流
- **Google ADK**：Google 生态和标准化要求高的场景
- **OpenClaw**：企业私有化部署和多模型网关统一管理

框架只是工具，真正决定 Agent 系统质量的，是你对 Agent 核心概念的理解、对业务场景的分析，以及对工程化能力的把控。

从下一章开始，我们会深入 LangChain4j，逐一拆解它的核心模块。

---

## 十一、引用文档与延伸阅读

- LangChain4j 官方文档：https://docs.langchain4j.dev
- LangChain4j GitHub：https://github.com/langchain4j/langchain4j
- Spring AI 官方文档：https://docs.spring.io/spring-ai/reference/
- Spring AI GitHub：https://github.com/spring-projects/spring-ai
- AgentScope GitHub：https://github.com/modelscope/agentscope
- Google ADK 官方文档：https://google.github.io/adk-docs/
- Google ADK Java GitHub：https://github.com/google/adk-java
- OpenClaw GitHub：https://github.com/openclaw/openclaw

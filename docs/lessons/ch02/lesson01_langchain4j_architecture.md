# 第2章第1节：LangChain4j整体架构设计：核心模块与扩展点

大家好，我是秋天。

从这一节课开始，我们正式进入第二章：**LangChain4j 框架深度解析**。

LangChain4j 是目前 Java 生态里功能最完整、社区最活跃的 Agent 框架。在接下来的几节课里，我们会把它的核心模块逐一拆开来讲。

这节课是整章的起点，我们先做一件最重要的事：

**把 LangChain4j 的整体架构看清楚。**

很多同学学框架的方式是直接上手写代码，遇到问题再查文档。这种方式短期能跑起来，但很容易陷入"知其然不知其所以然"的状态——不知道为什么这样设计，不知道扩展点在哪里，遇到复杂场景就不知道怎么办。

所以这节课，我们先花时间把架构图看懂，再去写代码，效果会好很多。

---

## 一、LangChain4j 是什么，解决什么问题

LangChain4j 的核心目标，是把构建 LLM 应用所需的各种能力，用 Java 的方式封装成可组合的模块。

它解决的核心问题包括：

- **模型接入的碎片化**：不同厂商的 API 格式不同，LangChain4j 提供统一抽象
- **工具调用的复杂性**：Function Calling 的定义、路由、执行需要大量样板代码
- **记忆管理的繁琐**：多轮对话的上下文维护需要手动处理
- **RAG 的工程复杂度**：文档加载、切分、向量化、检索涉及多个系统
- **Agent 循环的实现难度**：ReAct 等推理模式需要复杂的循环控制逻辑

LangChain4j 把这些能力模块化，让你可以像搭积木一样组合出自己需要的 Agent 系统。

---

## 二、整体架构：三层结构

LangChain4j 的整体架构可以分为三层：

```Plain Text
┌─────────────────────────────────────────────┐
│              应用层（High-Level API）          │
│         AiServices / Chains / Agents         │
├─────────────────────────────────────────────┤
│              核心层（Core Abstractions）       │
│  ChatModel / EmbeddingModel / Tools          │
│  ChatMemory / ContentRetriever / OutputParser│
├─────────────────────────────────────────────┤
│              集成层（Integrations）            │
│  OpenAI / Anthropic / Azure / Ollama / ...   │
│  PgVector / Chroma / Milvus / Redis / ...    │
└─────────────────────────────────────────────┘

```

### 第一层：集成层

集成层负责对接外部系统，包括：

- **模型提供商**：OpenAI、Anthropic、Azure OpenAI、Google Gemini、Ollama（本地模型）、HuggingFace 等
- **向量数据库**：PgVector、ChromaDB、Milvus、Pinecone、Redis、Weaviate 等
- **文档加载器**：PDF、Word、网页、数据库等

集成层的设计原则是：**每个集成都是独立的 Maven 模块，按需引入，不引入不依赖。**

```XML
<!-- 只引入你需要的集成 -->
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-open-ai</artifactId>
    <version>0.36.0</version>
</dependency>

<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-anthropic</artifactId>
    <version>0.36.0</version>
</dependency>

<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-pgvector</artifactId>
    <version>0.36.0</version>
</dependency>

```

### 第二层：核心层

核心层定义了所有关键抽象接口，是整个框架的骨架。主要包括：

| 接口 | 职责 |
|-|-|
| `ChatLanguageModel` | 同步聊天模型调用 |
| `StreamingChatLanguageModel` | 流式聊天模型调用 |
| `EmbeddingModel` | 文本向量化 |
| `EmbeddingStore` | 向量存储与检索 |
| `ChatMemory` | 对话记忆管理 |
| `ToolSpecification` | 工具定义与描述 |
| `ContentRetriever` | RAG 内容检索 |
| `OutputParser` | 模型输出解析 |

### 第三层：应用层

应用层是面向开发者的高级 API，主要是 **AiServices**——用声明式接口定义 Agent，框架自动生成实现。

这一层是大多数开发者日常使用最多的部分。

---

## 三、核心模块详解

### 模块一：ChatLanguageModel（模型抽象）

这是整个框架最核心的接口，所有模型调用都通过它进行：

```Java
// 核心接口定义（简化版）
public interface ChatLanguageModel {
    String generate(String userMessage);
    Response<AiMessage> generate(List<ChatMessage> messages);
    Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications);
}

```

不同模型的实现方式不同，但对上层代码完全透明：

```Java
// OpenAI 实现
ChatLanguageModel openAiModel = OpenAiChatModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-4o")
        .temperature(0.7)
        .build();

// Anthropic 实现
ChatLanguageModel anthropicModel = AnthropicChatModel.builder()
        .apiKey(System.getenv("ANTHROPIC_API_KEY"))
        .modelName("claude-sonnet-4-6")
        .maxTokens(1024)
        .build();

// Ollama 本地模型实现
ChatLanguageModel ollamaModel = OllamaChatModel.builder()
        .baseUrl("http://localhost:11434")
        .modelName("llama3")
        .build();

// 对上层代码来说，三者完全一样
String response = openAiModel.generate("什么是 Agent？");

```

<callout emoji="💡">
这种设计的价值在于：你可以在开发阶段用 Ollama 本地模型节省成本，上线时切换到 GPT-4o 或 Claude，业务代码一行不改。
</callout>

### 模块二：ChatMemory（记忆管理）

ChatMemory 负责管理多轮对话的上下文：

```Java
// 窗口记忆：保留最近 N 条消息
ChatMemory windowMemory = MessageWindowChatMemory.withMaxMessages(10);

// Token 限制记忆：按 Token 数量控制上下文长度
ChatMemory tokenMemory = TokenWindowChatMemory.builder()
        .maxTokens(4096, new OpenAiTokenizer())
        .build();

```

记忆的存储也可以扩展，默认是内存，也可以持久化到数据库：

```Java
// 自定义持久化存储
public class RedisChatMemoryStore implements ChatMemoryStore {

    private final RedisTemplate<String, String> redisTemplate;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        // 从 Redis 读取历史消息
        String key = "chat:memory:" + memoryId;
        return deserialize(redisTemplate.opsForList().range(key, 0, -1));
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        // 写入 Redis
        String key = "chat:memory:" + memoryId;
        redisTemplate.delete(key);
        redisTemplate.opsForList().rightPushAll(key, serialize(messages));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        redisTemplate.delete("chat:memory:" + memoryId);
    }
}

```

### 模块三：Tools（工具调用）

工具是 Agent 连接外部世界的关键。LangChain4j 用注解方式定义工具：

```Java
public class AgentTools {

    @Tool("根据订单号查询订单状态")
    public String queryOrderStatus(@P("订单号") String orderId) {
        // 调用订单系统 API
        return orderService.getStatus(orderId);
    }

    @Tool("发送邮件通知给用户")
    public String sendEmail(
            @P("收件人邮箱") String email,
            @P("邮件主题") String subject,
            @P("邮件内容") String content) {
        emailService.send(email, subject, content);
        return "邮件已发送";
    }

    @Tool("查询商品库存")
    public int checkInventory(@P("商品ID") String productId) {
        return inventoryService.getStock(productId);
    }
}

```

框架会自动把工具描述注入到模型的系统提示词里，并在模型决定调用工具时自动路由执行，再把结果返回给模型继续推理。

### 模块四：EmbeddingModel + EmbeddingStore（RAG 基础）

RAG 的核心是把文档向量化存储，再按语义相似度检索：

```Java
// 向量化模型
EmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("text-embedding-3-small")
        .build();

// 向量存储（内存版，生产用 PgVector 或 Milvus）
EmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();

// 写入文档
TextSegment segment = TextSegment.from("LangChain4j 是一个 Java Agent 框架...");
Embedding embedding = embeddingModel.embed(segment).content();
embeddingStore.add(embedding, segment);

// 检索
Embedding queryEmbedding = embeddingModel.embed("什么是 LangChain4j？").content();
List<EmbeddingMatch<TextSegment>> matches = embeddingStore.findRelevant(queryEmbedding, 3);

```

### 模块五：OutputParser（输出解析）

OutputParser 把模型的文本输出解析成 Java 对象：

```Java
// 定义输出结构
record TaskAnalysis(
    String summary,
    List<String> subtasks,
    String priority,
    int estimatedHours
) {}

// 使用结构化输出
interface TaskAgent {
    @UserMessage("分析以下任务并给出拆解方案：{{task}}")
    TaskAnalysis analyze(@V("task") String task);
}

TaskAgent agent = AiServices.create(TaskAgent.class, model);
TaskAnalysis result = agent.analyze("开发一个用户登录功能，包含 JWT 认证");

System.out.println("摘要：" + result.summary());
System.out.println("子任务：" + result.subtasks());
System.out.println("优先级：" + result.priority());
System.out.println("预估工时：" + result.estimatedHours() + "小时");

```

---

## 四、AiServices：把所有模块组合起来

AiServices 是 LangChain4j 的核心高级 API，它把上面所有模块组合成一个完整的 Agent：

```Java
// 定义 Agent 接口
interface CustomerServiceAgent {

    @SystemMessage("你是一个专业的客服助手，负责处理用户的订单问题。回答要简洁、准确、有礼貌。")
    String chat(@MemoryId String userId, @UserMessage String userMessage);
}

```

```Java
// 组装 Agent
CustomerServiceAgent agent = AiServices.builder(CustomerServiceAgent.class)
        // 绑定模型
        .chatLanguageModel(anthropicModel)
        // 绑定工具
        .tools(new AgentTools())
        // 绑定记忆（按用户 ID 隔离）
        .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(20))
        // 绑定 RAG 检索
        .contentRetriever(EmbeddingStoreContentRetriever.from(embeddingStore))
        .build();

```

```Java
// 使用 Agent
String response1 = agent.chat("user-001", "我的订单 ORD-12345 到哪里了？");
String response2 = agent.chat("user-001", "能帮我取消这个订单吗？");
String response3 = agent.chat("user-002", "你们的退款政策是什么？");  // 不同用户，独立记忆

```

<callout emoji="✅">
AiServices 的设计哲学是：**你只需要定义接口，框架负责实现**。模型调用、工具路由、记忆管理、RAG 检索，全部在框架内部自动完成。
</callout>

---

## 五、扩展点：如何自定义框架行为

LangChain4j 的扩展性非常好，几乎每个核心接口都可以自定义实现。

### 扩展点一：自定义模型

如果你要对接一个 LangChain4j 不支持的模型（比如公司内部模型），只需实现 `ChatLanguageModel` 接口：

```Java
public class InternalModelClient implements ChatLanguageModel {

    private final String endpoint;
    private final OkHttpClient httpClient;

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecs) {
        // 把 messages 转成内部模型的请求格式
        String requestBody = buildRequest(messages, toolSpecs);

        // 调用内部模型 API
        String responseBody = callInternalApi(requestBody);

        // 把响应转成 LangChain4j 的格式
        return parseResponse(responseBody);
    }
}

```

### 扩展点二：自定义工具执行拦截

```Java
// 实现 ToolExecutionRequestHandler 可以在工具执行前后加入自定义逻辑
public class AuditableToolExecutor implements ToolExecutor {

    private final ToolExecutor delegate;
    private final AuditLogger auditLogger;

    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        // 执行前记录审计日志
        auditLogger.logToolCall(memoryId, request.name(), request.arguments());

        String result = delegate.execute(request, memoryId);

        // 执行后记录结果
        auditLogger.logToolResult(memoryId, request.name(), result);

        return result;
    }
}

```

### 扩展点三：自定义 RAG 检索策略

```Java
// 实现 ContentRetriever 可以自定义检索逻辑
public class HybridContentRetriever implements ContentRetriever {

    private final EmbeddingStoreContentRetriever vectorRetriever;
    private final KeywordSearchRetriever keywordRetriever;

    @Override
    public List<Content> retrieve(Query query) {
        // 向量检索
        List<Content> vectorResults = vectorRetriever.retrieve(query);

        // 关键词检索
        List<Content> keywordResults = keywordRetriever.retrieve(query);

        // 合并去重，按相关性重排序
        return mergeAndRerank(vectorResults, keywordResults);
    }
}

```

---

## 六、模块依赖关系总览

理解了各个模块之后，我们来看它们之间的依赖关系：

```Plain Text
AiServices（应用入口）
    ├── ChatLanguageModel（必须）
    │       └── 具体实现：OpenAI / Anthropic / Ollama / 自定义
    ├── ChatMemory（可选）
    │       └── 存储：内存 / Redis / 数据库
    ├── Tools（可选）
    │       └── 任意 Java 方法 + @Tool 注解
    ├── ContentRetriever（可选，RAG）
    │       ├── EmbeddingModel
    │       └── EmbeddingStore：内存 / PgVector / Milvus
    └── OutputParser（可选）
            └── 自动从接口返回类型推断

```

这个结构告诉我们：**AiServices 是组合器，其他模块都是可插拔的组件。** 你可以根据需要选择性地启用各个模块。

---

## 七、Spring Boot 集成

LangChain4j 提供了 Spring Boot Starter，让集成更简单：

```XML
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-spring-boot-starter</artifactId>
    <version>0.36.0</version>
</dependency>

<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-open-ai-spring-boot-starter</artifactId>
    <version>0.36.0</version>
</dependency>

```

```YAML
# application.yml
langchain4j:
  open-ai:
    chat-model:
      api-key: ${OPENAI_API_KEY}
      model-name: gpt-4o
      temperature: 0.7

```

```Java
// 直接注入使用
@Service
public class AgentService {

    // 框架自动创建并注入
    @AiService
    private CustomerServiceAgent agent;

    public String handleRequest(String userId, String message) {
        return agent.chat(userId, message);
    }
}

```

---

## 八、本节小结

这一节课，我们从架构层面把 LangChain4j 看清楚了。核心要点：

- **三层架构**：集成层（对接外部系统）→ 核心层（关键抽象接口）→ 应用层（AiServices 高级 API）
- **核心模块**：ChatLanguageModel、ChatMemory、Tools、EmbeddingModel + EmbeddingStore、OutputParser
- **AiServices**：把所有模块组合成完整 Agent 的核心入口，声明式接口，框架自动实现
- **扩展点**：每个核心接口都可以自定义实现，框架对扩展开放

理解了这个架构，后面每一节课讲具体模块时，你都能清楚地知道它在整体架构里的位置和作用。

下一节课，我们会深入 AiServices，把它的声明式 Agent 定义方式和服务生成机制讲透。

---

## 九、引用文档与延伸阅读

- LangChain4j 官方文档：https://docs.langchain4j.dev
- LangChain4j GitHub：https://github.com/langchain4j/langchain4j
- LangChain4j AiServices 文档：https://docs.langchain4j.dev/tutorials/ai-services
- LangChain4j Spring Boot 集成：https://docs.langchain4j.dev/tutorials/spring-boot-integration
- LangChain4j 工具调用文档：https://docs.langchain4j.dev/tutorials/tools
- LangChain4j RAG 文档：https://docs.langchain4j.dev/tutorials/rag
- Maven Central 最新版本：https://central.sonatype.com/artifact/dev.langchain4j/langchain4j

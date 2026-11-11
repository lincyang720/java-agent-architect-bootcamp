# 第2章第9节：LangChain4j的扩展性与自定义组件：如何编写自己的模型、工具、内存实现

大家好，我是秋天。

上一节课我们把 Memory 记忆管理讲透了。

这节课是第二章的最后一节，我们来系统梳理 LangChain4j 的**扩展性设计**——框架提供了哪些扩展点，以及如何编写自己的模型适配器、工具执行器、记忆存储等自定义组件。

很多同学学到这里，已经能用 LangChain4j 做出能跑的 Agent 了。但真正的生产项目，往往有大量框架没有内置支持的需求：

- 公司有自研模型，不在 LangChain4j 支持列表里
- 需要对工具调用加审计日志、鉴权拦截
- 记忆需要存到公司内部的 KV 存储，不是 Redis
- 需要接入内部的向量数据库，而不是开源的 PgVector

**这节课就告诉你：当框架满足不了需求时，如何优雅地扩展它，而不是 fork 源码或者绕过框架。**

学完这节课，你会掌握：

1. LangChain4j 的扩展点全景图
2. 自定义模型适配器（ChatLanguageModel）
3. 自定义工具执行拦截器（ToolExecutor）
4. 自定义记忆存储（ChatMemoryStore）
5. 自定义向量存储（EmbeddingStore）
6. 自定义内容检索器（ContentRetriever）
7. 扩展点的组合与最佳实践

---

## 一、LangChain4j 扩展点全景图

LangChain4j 几乎所有核心能力都定义为接口，你可以替换任意一个：

```Plain Text
AiServices（应用层，不需要扩展，用注解驱动）
    │
    ├── ChatLanguageModel          ← 扩展点①：接入自定义或内部模型
    ├── StreamingChatLanguageModel ← 扩展点②：接入自定义流式模型
    ├── ChatMemory                 ← 扩展点③：自定义记忆管理策略
    │       └── ChatMemoryStore   ← 扩展点④：自定义记忆持久化存储
    ├── ToolExecutor               ← 扩展点⑤：拦截并增强工具执行
    ├── ContentRetriever           ← 扩展点⑥：自定义 RAG 检索策略
    ├── EmbeddingModel             ← 扩展点⑦：接入自定义向量化模型
    ├── EmbeddingStore             ← 扩展点⑧：接入自定义向量数据库
    └── ChatModelListener          ← 扩展点⑨：监听模型请求/响应

```

原则：**对扩展开放，对修改关闭。** 你不需要改框架源码，只需实现对应接口。

---

## 二、扩展点①：自定义模型适配器

这是最常见的扩展需求——接入公司内部模型或 LangChain4j 尚未支持的第三方模型。

### 实现 ChatLanguageModel 接口

```Java
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.message.*;
import dev.langchain4j.agent.tool.ToolSpecification;

public class InternalModelAdapter implements ChatLanguageModel {

    private final String endpoint;
    private final String apiKey;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public InternalModelAdapter(String endpoint, String apiKey) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(120))
                .addInterceptor(chain -> {
                    // 统一注入鉴权 Header
                    Request req = chain.request().newBuilder()
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "application/json")
                            .build();
                    return chain.proceed(req);
                })
                .build();
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        return generate(messages, (List<ToolSpecification>) null);
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages,
                                         List<ToolSpecification> toolSpecifications) {
        try {
            // ① 把 LangChain4j 消息格式转成内部模型格式
            String requestBody = buildRequest(messages, toolSpecifications);

            // ② 发送请求
            Request httpRequest = new Request.Builder()
                    .url(endpoint + "/v1/chat/completions")
                    .post(RequestBody.create(requestBody,
                            MediaType.get("application/json")))
                    .build();

            try (okhttp3.Response httpResponse =
                         httpClient.newCall(httpRequest).execute()) {
                if (!httpResponse.isSuccessful()) {
                    throw new RuntimeException("模型调用失败，状态码：" + httpResponse.code());
                }
                String body = httpResponse.body().string();

                // ③ 把内部模型响应转成 LangChain4j 格式
                return parseResponse(body);
            }
        } catch (IOException e) {
            throw new RuntimeException("内部模型调用异常", e);
        }
    }

    private String buildRequest(List<ChatMessage> messages,
                                 List<ToolSpecification> tools) throws JsonProcessingException {
        List<Map<String, Object>> converted = messages.stream()
                .map(msg -> {
                    String role = switch (msg.type()) {
                        case SYSTEM -> "system";
                        case USER -> "user";
                        case AI -> "assistant";
                        case TOOL_EXECUTION_RESULT -> "tool";
                    };
                    String content = msg instanceof UserMessage u ? u.singleText()
                            : msg instanceof AiMessage a ? a.text()
                            : msg instanceof SystemMessage s ? s.text()
                            : msg.toString();
                    return Map.<String, Object>of("role", role, "content", content);
                })
                .collect(Collectors.toList());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", converted);
        body.put("max_tokens", 2048);

        if (tools != null && !tools.isEmpty()) {
            // 把工具规范转成内部模型支持的格式
            body.put("tools", convertTools(tools));
        }

        return objectMapper.writeValueAsString(body);
    }

    private Response<AiMessage> parseResponse(String json) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(json);
        String content = root.path("choices").get(0)
                .path("message").path("content").asText();
        return Response.from(AiMessage.from(content));
    }

    private List<Map<String, Object>> convertTools(List<ToolSpecification> tools) {
        // 把 LangChain4j 的 ToolSpecification 转成内部模型的工具格式
        return tools.stream()
                .map(t -> Map.<String, Object>of(
                        "name", t.name(),
                        "description", t.description()
                ))
                .collect(Collectors.toList());
    }
}

```

```Java
// 像使用任何内置模型一样使用
ChatLanguageModel model = new InternalModelAdapter(
        "https://internal-llm.company.com",
        System.getenv("INTERNAL_LLM_API_KEY")
);

CustomerServiceAgent agent = AiServices.builder(CustomerServiceAgent.class)
        .chatLanguageModel(model)
        .tools(tools)
        .build();

```

---

## 三、扩展点②：自定义流式模型适配器

```Java
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.chat.StreamingResponseHandler;

public class InternalStreamingModelAdapter implements StreamingChatLanguageModel {

    private final OkHttpClient httpClient;
    private final String endpoint;

    @Override
    public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
        // 使用 SSE 接收流式响应
        Request request = buildRequest(messages);

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onResponse(Call call, okhttp3.Response response) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body().byteStream()))) {

                    StringBuilder fullContent = new StringBuilder();
                    String line;

                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            String data = line.substring(6);
                            if ("[DONE]".equals(data)) break;

                            // 解析 SSE 数据，提取 token
                            String token = extractToken(data);
                            if (token != null) {
                                handler.onNext(token);      // 逐 token 回调
                                fullContent.append(token);
                            }
                        }
                    }
                    // 流式完成
                    handler.onComplete(Response.from(
                            AiMessage.from(fullContent.toString())));
                } catch (Exception e) {
                    handler.onError(e);
                }
            }

            @Override
            public void onFailure(Call call, IOException e) {
                handler.onError(e);
            }
        });
    }
}

```

---

## 四、扩展点③+④：自定义记忆存储

上一节课已经演示过 `ChatMemoryStore` 的 Redis 和 JPA 实现，这里补充一个接入公司内部 KV 存储的示例：

```Java
// ���入公司内部分布式 KV 存储（如 Tair、TiKV 等）
public class InternalKvChatMemoryStore implements ChatMemoryStore {

    private final InternalKvClient kvClient;
    private static final String KEY_PREFIX = "agent:memory:";
    private static final int EXPIRE_DAYS = 30;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String value = kvClient.get(KEY_PREFIX + memoryId);
        if (value == null || value.isBlank()) return Collections.emptyList();
        return ChatMessageDeserializer.messagesFromJson(value);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String json = ChatMessageSerializer.messagesToJson(messages);
        kvClient.setex(KEY_PREFIX + memoryId, json,
                TimeUnit.DAYS.toSeconds(EXPIRE_DAYS));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        kvClient.del(KEY_PREFIX + memoryId);
    }
}

```

---

## 五、扩展点⑤：自定义工具执行拦截器

工具执行拦截器允许你在工具调用的前后注入自定义逻辑：审计、鉴权、限流、监控等。

### 实现 ToolExecutor 接口

```Java
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;

// 带审计日志的工具执行器
public class AuditableToolExecutor implements ToolExecutor {

    private final ToolExecutor delegate;          // 原始执行器
    private final AuditLogger auditLogger;
    private final AuthorizationService authService;

    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        String userId = memoryId.toString();
        String toolName = request.name();

        // ① 鉴权：检查用户是否有权调用该工具
        if (!authService.canUseTools(userId, toolName)) {
            log.warn("用户 {} 无权调用工具 {}", userId, toolName);
            return "抱歉，您没有权限执行该操作。";
        }

        // ② 前置审计日志
        String auditId = auditLogger.logToolCallStart(userId, toolName,
                request.arguments());

        long startTime = System.currentTimeMillis();
        String result;

        try {
            // ③ 执行实际工具
            result = delegate.execute(request, memoryId);

            // ④ 记录成功日志
            auditLogger.logToolCallSuccess(auditId,
                    System.currentTimeMillis() - startTime, result);

        } catch (Exception e) {
            // ⑤ 记录失败日志
            auditLogger.logToolCallFailure(auditId, e.getMessage());
            throw e;
        }

        return result;
    }
}

```

### 注册自定义 ToolExecutor

```Java
// 包装所有工具，注入审计执行器
Map<String, ToolExecutor> toolExecutors = new HashMap<>();

// 获取默认的工具执行器
DefaultToolExecutorFactory factory = new DefaultToolExecutorFactory();
Map<String, ToolExecutor> defaultExecutors =
        factory.createExecutors(new OrderTools());

// 用 AuditableToolExecutor 包装每个默认执行器
defaultExecutors.forEach((name, executor) ->
        toolExecutors.put(name,
                new AuditableToolExecutor(executor, auditLogger, authService)));

CustomerServiceAgent agent = AiServices.builder(CustomerServiceAgent.class)
        .chatLanguageModel(model)
        .toolExecutors(toolExecutors)   // 注入自定义执行器
        .build();

```

---

## 六、扩展点⑥：自定义内容检索器

`ContentRetriever` 是 RAG 的核心扩展点，允许你自定义检索策略：混合检索、多路召回、重排序等。

```Java
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;

// 混合检索器：向量检索 + 关键词检索 + 结果融合
public class HybridContentRetriever implements ContentRetriever {

    private final EmbeddingStoreContentRetriever vectorRetriever;
    private final KeywordSearchRetriever keywordRetriever;
    private final RerankerService reranker;

    @Override
    public List<Content> retrieve(Query query) {
        // ① 并行执行两路检索
        CompletableFuture<List<Content>> vectorFuture =
                CompletableFuture.supplyAsync(() ->
                        vectorRetriever.retrieve(query));

        CompletableFuture<List<Content>> keywordFuture =
                CompletableFuture.supplyAsync(() ->
                        keywordRetriever.retrieve(query));

        List<Content> vectorResults = vectorFuture.join();
        List<Content> keywordResults = keywordFuture.join();

        // ② 合并去重
        List<Content> merged = mergeAndDeduplicate(vectorResults, keywordResults);

        // ③ 重排序（可选，提升结果相关性）
        if (reranker != null && !merged.isEmpty()) {
            return reranker.rerank(query.text(), merged, 5);
        }

        return merged.stream().limit(5).collect(Collectors.toList());
    }

    private List<Content> mergeAndDeduplicate(List<Content> vectorResults,
                                               List<Content> keywordResults) {
        // 用 LinkedHashSet 去重同时保留顺序，向量结果优先
        Map<String, Content> seen = new LinkedHashMap<>();
        vectorResults.forEach(c -> seen.put(c.textSegment().text(), c));
        keywordResults.forEach(c -> seen.putIfAbsent(c.textSegment().text(), c));
        return new ArrayList<>(seen.values());
    }
}

```

```Java
// 绑定到 Agent
KnowledgeAgent agent = AiServices.builder(KnowledgeAgent.class)
        .chatLanguageModel(model)
        .contentRetriever(new HybridContentRetriever(
                vectorRetriever, keywordRetriever, reranker))
        .build();

```

---

## 七、扩展点⑦+⑧：自定义向量模型与向量存储

### 自定义 EmbeddingModel

```Java
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;

// 接入内部向量化服务
public class InternalEmbeddingModel implements EmbeddingModel {

    private final String endpoint;
    private final OkHttpClient httpClient;

    @Override
    public Response<Embedding> embed(String text) {
        String requestBody = "{\"input\": \"" + text + "\"}";
        // 调用内部 Embedding 服务
        float[] vector = callEmbeddingService(requestBody);
        return Response.from(Embedding.from(vector));
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        // 批量向量化，提升效率
        List<String> texts = segments.stream()
                .map(TextSegment::text)
                .collect(Collectors.toList());
        List<float[]> vectors = callBatchEmbeddingService(texts);
        List<Embedding> embeddings = vectors.stream()
                .map(Embedding::from)
                .collect(Collectors.toList());
        return Response.from(embeddings);
    }
}

```

### 自定义 EmbeddingStore

```Java
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingMatch;

// 接入公司内部向量数据库
public class InternalVectorStore implements EmbeddingStore<TextSegment> {

    private final InternalVectorDbClient client;
    private final String collection;

    @Override
    public String add(Embedding embedding, TextSegment embedded) {
        String id = UUID.randomUUID().toString();
        client.upsert(collection, id,
                embedding.vector(), embedded.text(),
                embedded.metadata().asMap());
        return id;
    }

    @Override
    public List<EmbeddingMatch<TextSegment>> findRelevant(Embedding referenceEmbedding,
                                                           int maxResults,
                                                           double minScore) {
        List<VectorSearchResult> results = client.search(
                collection,
                referenceEmbedding.vector(),
                maxResults,
                minScore
        );

        return results.stream()
                .map(r -> new EmbeddingMatch<>(
                        r.score(),
                        r.id(),
                        Embedding.from(r.vector()),
                        TextSegment.from(r.text(), Metadata.from(r.metadata()))
                ))
                .collect(Collectors.toList());
    }
}

```

---

## 八、扩展点⑨：ChatModelListener 监听器

监听器是非侵入式地增强框架行为的最优雅方式，适合做观测、统计、安全审查。

```Java
import dev.langchain4j.model.chat.listener.*;

// 全链路可观测性监听器
@Component
public class ObservabilityListener implements ChatModelListener {

    @Autowired
    private MeterRegistry meterRegistry;  // Micrometer 指标

    @Autowired
    private TraceContext traceContext;    // 分布式追踪

    @Override
    public void onRequest(ChatModelRequestContext context) {
        // 记录请求开始时间（存入 context 的 attributes 传递给 onResponse）
        context.attributes().put("startTime", System.currentTimeMillis());
        context.attributes().put("traceId", traceContext.currentTraceId());

        // 统计请求数
        meterRegistry.counter("llm.request.total",
                "model", context.model().getClass().getSimpleName()).increment();

        log.debug("[{}] LLM 请求，消息数：{}",
                context.attributes().get("traceId"),
                context.messages().size());
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        long duration = System.currentTimeMillis()
                - (Long) context.attributes().get("startTime");

        // 记录延迟
        meterRegistry.timer("llm.request.duration",
                "model", context.model().getClass().getSimpleName())
                .record(duration, TimeUnit.MILLISECONDS);

        // 记录 Token 消耗
        TokenUsage usage = context.response().tokenUsage();
        if (usage != null) {
            meterRegistry.counter("llm.tokens.input").increment(usage.inputTokenCount());
            meterRegistry.counter("llm.tokens.output").increment(usage.outputTokenCount());
        }

        log.debug("[{}] LLM 响应，耗时：{}ms，输入 Token：{}，输出 Token：{}",
                context.attributes().get("traceId"), duration,
                usage != null ? usage.inputTokenCount() : "N/A",
                usage != null ? usage.outputTokenCount() : "N/A");
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        meterRegistry.counter("llm.request.error",
                "error_type", context.error().getClass().getSimpleName()).increment();
        log.error("[{}] LLM 请求失败：{}",
                context.attributes().get("traceId"),
                context.error().getMessage());
    }
}

```

```Java
// 注册监听器
@Bean
public ChatLanguageModel chatLanguageModel(ObservabilityListener listener) {
    return OpenAiChatModel.builder()
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .modelName("gpt-4o")
            .listeners(List.of(listener))  // 注入监听器
            .build();
}

```

---

## 九、扩展组件的组合示例

生产环境里，多个扩展点通常组合使用：

```Java
@Configuration
public class ProductionAgentConfig {

    @Bean
    public CustomerServiceAgent customerServiceAgent(
            // 自定义模型适配器（接入内部模型）
            InternalModelAdapter internalModel,
            // 可观测性监听器
            ObservabilityListener observabilityListener,
            // 自定义记忆存储（接入内部 KV）
            InternalKvChatMemoryStore kvMemoryStore,
            // 混合检索器
            HybridContentRetriever hybridRetriever,
            // 审计工具执行器
            AuditableToolExecutorFactory auditableFactory,
            // 工具类
            OrderTools orderTools,
            RefundTools refundTools) {

        // 模型注入监听器
        ChatLanguageModel model = internalModel.withListeners(
                List.of(observabilityListener));

        // 工具执行器包装审计
        Map<String, ToolExecutor> auditedExecutors =
                auditableFactory.wrap(orderTools, refundTools);

        return AiServices.builder(CustomerServiceAgent.class)
                .chatLanguageModel(model)
                .toolExecutors(auditedExecutors)
                .chatMemoryProvider(memoryId ->
                        TokenWindowChatMemory.builder()
                                .id(memoryId)
                                .maxTokens(6000, new OpenAiTokenizer("gpt-4o"))
                                .chatMemoryStore(kvMemoryStore)
                                .build())
                .contentRetriever(hybridRetriever)
                .build();
    }
}

```

---

## 十、扩展组件的测试策略

自定义组件需要有充分的单元测试，特别是与外部系统的集成部分。

```Java
// 模型适配器的单元测试
class InternalModelAdapterTest {

    private MockWebServer mockServer;
    private InternalModelAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();
        adapter = new InternalModelAdapter(
                mockServer.url("/").toString(), "test-api-key");
    }

    @Test
    void shouldReturnParsedResponse() {
        // 模拟内部模型响应
        mockServer.enqueue(new MockResponse()
                .setBody("""
                    {"choices":[{"message":{"content":"你好，我是测试回复"}}]}
                """)
                .addHeader("Content-Type", "application/json"));

        List<ChatMessage> messages = List.of(new UserMessage("你好"));
        Response<AiMessage> response = adapter.generate(messages);

        assertThat(response.content().text()).isEqualTo("你好，我是测试回复");
    }

    @Test
    void shouldHandleServerError() {
        mockServer.enqueue(new MockResponse().setResponseCode(500));

        assertThatThrownBy(() -> adapter.generate(List.of(new UserMessage("测试"))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("模型调用失败");
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }
}

```

---

## 十一、本节小结与第二章总结

### 本节核心要点

- **扩展点全景**：ChatLanguageModel、ChatMemoryStore、ToolExecutor、ContentRetriever、EmbeddingStore、EmbeddingModel、ChatModelListener——每个都是接口，都可以自定义实现
- **最小侵入原则**：实现接口即可扩展，无需改框架源码，无需 fork
- **组合使用**：多个扩展点可以自由组合，构建满足企业级需求的 Agent 系统
- **测试保障**：自定义组件要有充分的单元测试，用 MockWebServer 模拟外部依赖

### 第二章完整回顾

至此，第二章"LangChain4j 框架深度解析"全部讲完。我们覆盖了：

| 节次 | 主题 | 核心收获 |
|-|-|-|
| 第一节 | 整体架构 | 三层架构、核心模块、扩展点全局认知 |
| 第二节 | AiServices | 声明式 Agent 定义、注解驱动开发 |
| 第三节 | Chain 机制 | 多步骤处理管道的设计与实现 |
| 第四节 | Tool（工具） | Function Calling 底层原理与最佳实践 |
| 第五节 | 模型集成 | 适配器模式、多模型切换、降级策略 |
| 第六节 | 提示词模板 | 变量注入、动态模板、外部化管理 |
| 第七节 | OutputParser | 结构化输出、JSON Schema、自定义解析 |
| 第八节 | Memory | 窗口/Token 记忆、持久化、多用户隔离 |
| 第九节 | 扩展性 | 自定义组件、监听器、企业级扩展模式 |

从下一章开始，我们进入 **Spring AI 框架深度解析**——从 Spring 开发者最熟悉的视角，看 AI 能力如何无缝融入 Spring 生态。

---

## 十二、引用文档与延伸阅读

- LangChain4j 扩展指南官方文档：https://docs.langchain4j.dev/tutorials/custom-components
- LangChain4j ChatModelListener 文档：https://docs.langchain4j.dev/tutorials/logging
- LangChain4j ContentRetriever 接口：https://github.com/langchain4j/langchain4j/blob/main/langchain4j-core/src/main/java/dev/langchain4j/rag/content/retriever/ContentRetriever.java
- LangChain4j EmbeddingStore 接口：https://github.com/langchain4j/langchain4j/blob/main/langchain4j-core/src/main/java/dev/langchain4j/store/embedding/EmbeddingStore.java
- LangChain4j ChatMemoryStore 接口：https://github.com/langchain4j/langchain4j/blob/main/langchain4j-core/src/main/java/dev/langchain4j/store/memory/chat/ChatMemoryStore.java
- OkHttp MockWebServer 测试文档：https://square.github.io/okhttp/features/mock-web-server/
- Micrometer 监控文档：https://micrometer.io/docs
- GitHub 示例代码：https://github.com/langchain4j/langchain4j-examples

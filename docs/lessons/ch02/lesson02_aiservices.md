# 第2章第2节：AiServices详解：声明式Agent的定义与服务生成

大家好，我是秋天。

上一节课我们把 LangChain4j 的整体架构看清楚了，知道了 AiServices 是整个框架的核心高级 API。

这节课，我们就把 AiServices 彻底讲透。

AiServices 是 LangChain4j 里我最喜欢的设计之一。它的核心思想非常简单：

**你只需要定义一个 Java 接口，框架自动帮你生成一个完整的 Agent 实现。**

这种声明式的设计方式，让 Agent 的定义变得极其简洁，同时又保留了完整的可扩展性。

学完这节课，你会掌握：

1. AiServices 的基本用法和背后的工作原理
2. 如何用注解精确控制 Agent 的行为
3. 如何绑定工具、记忆、RAG 等能力
4. 多用户场景下的记忆隔离方案
5. 流式输出的实现方式
6. 常见的坑和最佳实践

---

## 一、从最简单的例子开始

先看一个最小的 AiServices 示例：

```Java
// 第一步：定义接口
interface Assistant {
    String chat(String userMessage);
}

```

```Java
// 第二步：创建模型
ChatLanguageModel model = OpenAiChatModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-4o")
        .build();

```

```Java
// 第三步：用 AiServices 生成实现
Assistant assistant = AiServices.create(Assistant.class, model);

// 第四步：直接调用
String response = assistant.chat("Java 21 的虚拟线程有什么优势？");
System.out.println(response);

```

就这四步，一个能对话的 Agent 就跑起来了。

你不需要手动构建 `ChatMessage` 列表，不需要处理 API 响应格式，不需要写任何样板代码。

这就是 AiServices 的价值：**把所有复杂性藏在框架里，让你专注在业务逻辑上。**

---

## 二、AiServices 背后做了什么

很多同学会好奇：我只定义了一个接口，框架是怎么生成实现的？

AiServices 在背后做了这几件事：

1. **动态代理**：用 Java 动态代理生成接口的实现类
2. **消息构建**：把方法参数转换成 `ChatMessage` 列表
3. **模型调用**：调用绑定的 `ChatLanguageModel`
4. **工具路由**：如果模型决定调用工具，自动执行并把结果返回给模型
5. **记忆管理**：自动维护对话历史
6. **输出解析**：把模型的文本输出解析成方法的返回类型

整个流程对你完全透明，你只需要关心接口定义。

---

## 三、核心注解详解

AiServices 通过注解来控制 Agent 的行为，掌握这些注解是用好 AiServices 的关键。

### @SystemMessage：定义系统提示词

```Java
interface CustomerServiceAgent {

    @SystemMessage("你是一个专业的客服助手，负责处理用户的订单和售后问题。" +
                   "回答要简洁、准确、有礼貌。如果不确定，请如实告知用户。")
    String chat(String userMessage);
}

```

系统提示词也支持模板变量：

```Java
interface RolePlayAgent {

    @SystemMessage("你是一个{{role}}，专注于{{domain}}领域。用{{language}}回答。")
    String chat(@V("role") String role,
                @V("domain") String domain,
                @V("language") String language,
                @UserMessage String question);
}

// 使用时动态注入角色
String response = agent.chat("资深架构师", "Java 性能优化", "中文", "如何优化 GC？");

```

### @UserMessage：自定义用户消息模板

默认情况下，方法的 String 参数会作为用户消息。但你可以用 `@UserMessage` 定制消息格式：

````Java
interface CodeReviewAgent {

    @UserMessage("请对以下{{language}}代码进行 Code Review，重点关注：\n" +
                 "1. 潜在的 Bug\n" +
                 "2. 性能问题\n" +
                 "3. 代码规范\n\n" +
                 "代码内容：\n```{{language}}\n{{code}}\n```")
    String review(@V("language") String language, @V("code") String code);
}

````

```Java
String review = agent.review("java", """
    public List<User> getUsers() {
        List<User> users = new ArrayList<>();
        for (int i = 0; i < userIds.size(); i++) {
            users.add(userRepository.findById(userIds.get(i)));
        }
        return users;
    }
""");

```

### @V：注入模板变量

`@V` 注解用于把方法参数注入到消息模板的占位符里：

```Java
interface TranslationAgent {

    @UserMessage("请把以下{{sourceLang}}文本翻译成{{targetLang}}，保持原文的语气和风格：\n\n{{text}}")
    String translate(
            @V("sourceLang") String sourceLang,
            @V("targetLang") String targetLang,
            @V("text") String text);
}

```

### @MemoryId：多用户记忆隔离

这是一个非常重要的注解，用于在多用户场景下隔离各自的对话记忆：

```Java
interface MultiUserAgent {

    @SystemMessage("你是一个智能助手，记住每个用户的偏好和历史对话。")
    String chat(@MemoryId String userId, @UserMessage String message);
}

```

```Java
// 不同用户的对话完全隔离
agent.chat("user-alice", "我喜欢 Java，不喜欢 Python");
agent.chat("user-bob", "我是 Python 开发者");

// Alice 的记忆不会影响 Bob
String aliceResponse = agent.chat("user-alice", "你还记得我喜欢什么语言吗？");
// 输出：你之前提到你喜欢 Java，不喜欢 Python

String bobResponse = agent.chat("user-bob", "你还记得我是做什么的吗？");
// 输出：你之前提到你是 Python 开发者

```

---

## 四、绑定工具

工具是 Agent 连接外部世界的关键。在 AiServices 里绑定工具非常简单：

```Java
// 定义工具类
public class OrderTools {

    @Tool("根据订单号查询订单的当前状态和物流信息")
    public String queryOrder(@P("订单号，格式如 ORD-12345") String orderId) {
        // 调用订单系统
        Order order = orderService.findById(orderId);
        return String.format("订单 %s 状态：%s，预计送达：%s",
                orderId, order.getStatus(), order.getEstimatedDelivery());
    }

    @Tool("取消指定订单，仅限未发货状态的订单")
    public String cancelOrder(@P("订单号") String orderId) {
        boolean success = orderService.cancel(orderId);
        return success ? "订单已成功取消" : "取消失败，订单可能已发货";
    }

    @Tool("查询用户的积分余额")
    public int queryPoints(@P("用户ID") String userId) {
        return pointsService.getBalance(userId);
    }
}

```

```Java
// 绑定工具到 Agent
CustomerServiceAgent agent = AiServices.builder(CustomerServiceAgent.class)
        .chatLanguageModel(model)
        .tools(new OrderTools())           // 单个工具类
        // .tools(new OrderTools(), new PaymentTools())  // 多个工具类
        .build();

```

工具调用的完整流程：

```Plain Text
用户："我的订单 ORD-12345 到哪里了？"
    ↓
模型分析：需要调用 queryOrder 工具
    ↓
框架自动执行：orderTools.queryOrder("ORD-12345")
    ↓
工具返回："订单 ORD-12345 状态：已发货，预计送达：明天下午"
    ↓
模型生成最终回复："您的订单 ORD-12345 已经发货，预计明天下午送达，请注意查收。"

```

<callout emoji="💡">
工具调用可能发生多次。模型可以在一次对话里连续调用多个工具，框架会自动处理这个循环，直到模型认为可以给出最终答案为止。
</callout>

---

## 五、绑定记忆

### 基础记忆绑定

```Java
Assistant agent = AiServices.builder(Assistant.class)
        .chatLanguageModel(model)
        .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
        .build();

// 现在 Agent 有了记忆
agent.chat("我叫张三，是一名 Java 开发者");
agent.chat("我在北京工作");
String response = agent.chat("你还记得我是谁吗？");
// 输出：你叫张三，是一名在北京工作的 Java 开发者

```

### 多用户记忆隔离

生产环境里，通常需要为每个用户维护独立的记忆：

```Java
interface MultiUserAssistant {
    String chat(@MemoryId String userId, @UserMessage String message);
}

MultiUserAssistant agent = AiServices.builder(MultiUserAssistant.class)
        .chatLanguageModel(model)
        // chatMemoryProvider 为每个 memoryId 创建独立的记忆实例
        .chatMemoryProvider(memoryId ->
                MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(20)
                        .chatMemoryStore(redisChatMemoryStore)  // 持久化到 Redis
                        .build())
        .build();

```

### Token 限制记忆

当对话很长时，需要控制 Token 消耗：

```Java
ChatMemory tokenMemory = TokenWindowChatMemory.builder()
        .maxTokens(8000, new OpenAiTokenizer("gpt-4o"))
        .build();

Assistant agent = AiServices.builder(Assistant.class)
        .chatLanguageModel(model)
        .chatMemory(tokenMemory)
        .build();

```

---

## 六、绑定 RAG

把知识库接入 Agent，让它能回答特定领域的问题：

```Java
// 构建知识库
EmbeddingModel embeddingModel = OpenAiEmbeddingModel.withApiKey(apiKey);
EmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();

// 加载文档
EmbeddingStoreIngestor.builder()
        .embeddingModel(embeddingModel)
        .embeddingStore(store)
        .build()
        .ingest(loadDocuments("docs/"));

// 构建检索器
ContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
        .embeddingStore(store)
        .embeddingModel(embeddingModel)
        .maxResults(3)
        .minScore(0.7)
        .build();

// 绑定到 Agent
interface KnowledgeAgent {
    @SystemMessage("你是一个知识库助手，只根据提供的文档内容回答问题。如果文档中没有相关信息，请如实告知。")
    String ask(String question);
}

KnowledgeAgent agent = AiServices.builder(KnowledgeAgent.class)
        .chatLanguageModel(model)
        .contentRetriever(retriever)
        .build();

```

---

## 七、结构化输出

AiServices 支持把模型输出直接映射到 Java 对象，不需要手动解析 JSON：

```Java
// 定义输出结构
record BugReport(
    String title,
    String severity,        // HIGH / MEDIUM / LOW
    List<String> affectedComponents,
    String rootCause,
    String suggestedFix
) {}

// 定义返回结构化对象的 Agent
interface BugAnalysisAgent {

    @UserMessage("分析以下异常堆栈，给出 Bug 报告：\n\n{{stackTrace}}")
    BugReport analyze(@V("stackTrace") String stackTrace);
}

BugAnalysisAgent agent = AiServices.create(BugAnalysisAgent.class, model);

BugReport report = agent.analyze("""
    java.lang.NullPointerException: Cannot invoke method getName() on null object
        at com.example.UserService.processUser(UserService.java:45)
        at com.example.OrderController.createOrder(OrderController.java:78)
""");

System.out.println("标题：" + report.title());
System.out.println("严重程度：" + report.severity());
System.out.println("根因：" + report.rootCause());
System.out.println("修复建议：" + report.suggestedFix());

```

框架会自动在提示词里加入 JSON 格式要求，并把模型返回的 JSON 解析成 `BugReport` 对象。

---

## 八、流式输出

对于需要实时展示生成内容的场景，AiServices 支持流式输出：

```Java
// 使用 TokenStream 作为返回类型
interface StreamingAssistant {
    TokenStream chat(String message);
}

// 绑定流式模型
StreamingChatLanguageModel streamingModel = OpenAiStreamingChatModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-4o")
        .build();

StreamingAssistant agent = AiServices.builder(StreamingAssistant.class)
        .streamingChatLanguageModel(streamingModel)
        .build();

// 流式消费
agent.chat("请详细解释 Java 虚拟线程的工作原理")
        .onNext(token -> System.out.print(token))           // 每个 token 实时输出
        .onComplete(response -> System.out.println("\n完成"))
        .onError(error -> System.err.println("出错：" + error.getMessage()))
        .start();

```

在 Spring Boot 里，可以结合 SSE 实现前端实时流式展示：

```Java
@GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> streamChat(@RequestParam String message) {
    return Flux.create(sink ->
            streamingAgent.chat(message)
                    .onNext(sink::next)
                    .onComplete(r -> sink.complete())
                    .onError(sink::error)
                    .start()
    );
}

```

---

## 九、完整示例：一个生产级客服 Agent

把上面所有能力组合起来，看一个接近生产级别的完整示例：

```Java
// 接口定义
interface CustomerServiceAgent {

    @SystemMessage("""
            你是"小智"，一个专业的电商客服助手。
            职责：处理订单查询、退换货申请、积分兑换等问题。
            原则：
            1. 回答简洁准确，不超过 200 字
            2. 遇到无法处理的问题，引导用户联系人工客服
            3. 始终保持礼貌和耐心
            """)
    String chat(@MemoryId String userId, @UserMessage String message);
}

```

```Java
// 工具定义
@Component
public class CustomerServiceTools {

    @Autowired private OrderService orderService;
    @Autowired private PointsService pointsService;
    @Autowired private RefundService refundService;

    @Tool("查询订单状态和物流信息")
    public String queryOrder(@P("订单号") String orderId) {
        return orderService.getOrderDetail(orderId).toSummary();
    }

    @Tool("提交退款申请")
    public String applyRefund(@P("订单号") String orderId, @P("退款原因") String reason) {
        RefundResult result = refundService.apply(orderId, reason);
        return result.isSuccess() ? "退款申请已提交，预计 3-5 个工作日到账" : "申请失败：" + result.getMessage();
    }

    @Tool("查询用户积分余额和到期时间")
    public String queryPoints(@P("用户ID") String userId) {
        PointsInfo info = pointsService.getInfo(userId);
        return String.format("当前积分：%d，其中 %d 积分将于 %s 到期",
                info.total(), info.expiring(), info.expiryDate());
    }
}

```

```Java
// Agent 组装（Spring Boot 配置类）
@Configuration
public class AgentConfig {

    @Bean
    public CustomerServiceAgent customerServiceAgent(
            ChatLanguageModel model,
            CustomerServiceTools tools,
            RedisChatMemoryStore memoryStore) {

        return AiServices.builder(CustomerServiceAgent.class)
                .chatLanguageModel(model)
                .tools(tools)
                .chatMemoryProvider(userId ->
                        MessageWindowChatMemory.builder()
                                .id(userId)
                                .maxMessages(20)
                                .chatMemoryStore(memoryStore)
                                .build())
                .build();
    }
}

```

```Java
// Controller 层
@RestController
@RequestMapping("/api/customer-service")
public class CustomerServiceController {

    @Autowired
    private CustomerServiceAgent agent;

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody ChatRequest request) {

        String response = agent.chat(userId, request.message());
        return ResponseEntity.ok(new ChatResponse(response));
    }
}

```

<callout emoji="✅">
这个示例展示了 AiServices 在生产环境里的完整用法：声明式接口 + 工具绑定 + 多用户记忆隔离 + Spring Boot 集成。整个 Agent 的核心逻辑不超过 50 行代码。
</callout>

---

## 十、常见坑和最佳实践

### 坑一：工具方法抛异常会中断 Agent

工具方法里如果抛出未捕获的异常，整个 Agent 调用会失败。建议在工具方法里做好异常处理：

```Java
@Tool("查询订单状态")
public String queryOrder(@P("订单号") String orderId) {
    try {
        return orderService.getStatus(orderId);
    } catch (OrderNotFoundException e) {
        return "未找到订单 " + orderId + "，请确认订单号是否正确";
    } catch (Exception e) {
        log.error("查询订单失败", e);
        return "查询失败，请稍后重试";
    }
}

```

### 坑二：记忆无限增长导致 Token 超限

默认的 `MessageWindowChatMemory` 按消息条数限制，但单条消息可能很长。生产环境建议用 `TokenWindowChatMemory`：

```Java
// 推荐：按 Token 数量控制
ChatMemory memory = TokenWindowChatMemory.builder()
        .maxTokens(6000, tokenizer)
        .build();

```

### 坑三：系统提示词过长影响效果

系统提示词不是越长越好。过长的系统提示词会稀释关键指令，建议控制在 500 字以内，聚焦最核心的行为约束。

### 最佳实践：接口方法命名要有语义

```Java
// 不好：方法名没有语义
String process(String input);

// 好：方法名清晰表达意图
String analyzeCode(String code);
String generateTestCases(String methodSignature);
String reviewPullRequest(String diff);

```

---

## 十一、本节小结

这节课我们把 AiServices 从头到尾讲透了。核心要点：

- **基本用法**：定义接口 → 创建模型 → `AiServices.create()` 或 `AiServices.builder()` 生成实现
- **核心注解**：`@SystemMessage`、`@UserMessage`、`@V`、`@MemoryId`
- **能力绑定**：`.tools()`、`.chatMemory()`、`.chatMemoryProvider()`、`.contentRetriever()`
- **结构化输出**：返回类型设为 Java 对象，框架自动解析
- **流式输出**：返回类型设为 `TokenStream`，配合 `StreamingChatLanguageModel`

AiServices 的设计哲学是：**把复杂性封装在框架里，把简洁性留给开发者。** 掌握了它，你就掌握了 LangChain4j 最核心的使用方式。

下一节课，我们会深入 Chain 机制，看看 LangChain4j 如何支持更复杂的链式调用场景。

---

## 十二、引用文档与延伸阅读

- LangChain4j AiServices 官方文档：https://docs.langchain4j.dev/tutorials/ai-services
- LangChain4j 工具调用文档：https://docs.langchain4j.dev/tutorials/tools
- LangChain4j 记忆管理文档：https://docs.langchain4j.dev/tutorials/chat-memory
- LangChain4j RAG 文档：https://docs.langchain4j.dev/tutorials/rag
- LangChain4j 流式输出文档：https://docs.langchain4j.dev/tutorials/ai-services#streaming
- LangChain4j Spring Boot 集成：https://docs.langchain4j.dev/tutorials/spring-boot-integration
- GitHub 示例代码：https://github.com/langchain4j/langchain4j-examples

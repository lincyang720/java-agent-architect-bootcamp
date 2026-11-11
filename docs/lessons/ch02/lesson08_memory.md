# 第2章第8节：内存管理（Memory）：支持窗口记忆、摘要记忆等

大家好，我是秋天。

上一节课我们把 OutputParser 讲透了，知道了如何把模型的文本输出自动解析成 Java 对象。

这节课，我们来深入 LangChain4j 的**内存管理（Memory）机制**——这是让 Agent 具备"记忆"能力的核心模块。

很多同学在第一次实现多轮对话时，会发现：每次请求都是独立的，模型不记得上一轮说了什么。这是因为 LLM 本身是无状态的，每次调用都从零开始。

**Memory 模块就是解决这个问题的：它在框架层维护对话历史，每次调用时自动把历史消息拼入上下文，让模型"看起来"拥有记忆。**

学完这节课，你会掌握：

1. LangChain4j 记忆管理的核心设计
2. 窗口记忆（MessageWindowChatMemory）的使用与配置
3. Token 限制记忆（TokenWindowChatMemory）的使用场景
4. 记忆的持久化：内存存储 vs Redis vs 数据库
5. 多用户记忆隔离方案
6. 记忆管理的生产实践与常见坑

---

## 一、记忆管理的核心设计

### LLM 为什么是无状态的

每次调用 LLM API，都是一次全新的、独立的请求。模型不知道你上一轮说了什么，也不会主动保存任何状态。

```Java
// 没有记忆的对话
model.generate("我叫张三");           // 模型回复：很高兴认识你，张三
model.generate("你还记得我叫什么？"); // 模型回复：抱歉，我不知道你叫什么

```

### LangChain4j 的解决方案

LangChain4j 的记忆管理做了三件事：

```Plain Text
① 用户发送消息
        ↓
② 从 ChatMemory 读取历史消息列表
        ↓
③ 把历史消息 + 当前用户消息组合成完整上下文发给模型
        ↓
④ 模型返回回复
        ↓
⑤ 把新的用户消息和 AI 回复写入 ChatMemory
        ↓
⑥ 下次对话从第②步重复

```

这样，模型每次都能"看到"完整的对话历史，表现出记忆能力。

### 核心接口

```Java
// ChatMemory 接口（简化版）
public interface ChatMemory {

    // 记忆的唯一标识（用于多用户隔离）
    Object id();

    // 添加一条消息
    void add(ChatMessage message);

    // 获取所有记忆中的消息
    List<ChatMessage> messages();

    // 清空记忆
    void clear();
}

```

---

## 二、MessageWindowChatMemory：窗口记忆

`MessageWindowChatMemory` 是最常用的记忆实现，按消息条数限制上下文窗口。

### 基础用法

```Java
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

// 保留最近 10 条消息（包含用户消息和 AI 回复）
ChatMemory memory = MessageWindowChatMemory.withMaxMessages(10);

```

### 与 AiServices 结合

```Java
interface Assistant {
    String chat(String userMessage);
}

ChatLanguageModel model = OpenAiChatModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-4o")
        .build();

Assistant assistant = AiServices.builder(Assistant.class)
        .chatLanguageModel(model)
        .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
        .build();

// 现在 Agent 有了记忆
assistant.chat("我叫李雷，是一名 Java 开发者");
assistant.chat("我在上海工作，主要做后端");
String response = assistant.chat("你还记得我是谁吗？");
// 输出：你叫李雷，是一名在上海工作的 Java 后端开发者

```

### 窗口大小的选择

| 窗口大小 | 适用场景 | 注意事项 |
|-|-|-|
| 5-10 条 | 简单问答、单次任务 | 历史记忆少，成本低 |
| 10-20 条 | 常规对话助手 | 平衡记忆深度和 Token 成本 |
| 20-50 条 | 复杂任务、长期协作 | Token 消耗较高，注意成本 |

**注意**：`MessageWindowChatMemory` 按消息条数限制，但单条消息可能非常长（如用户粘贴了大段代码）。生产环境更推荐按 Token 数限制。

---

## 三、TokenWindowChatMemory：Token 限制记忆

`TokenWindowChatMemory` 按 Token 数量限制记忆窗口，比按消息条数更精确，更适合生产环境。

### 基础用法

```Java
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiTokenizer;

ChatMemory memory = TokenWindowChatMemory.builder()
        .maxTokens(8000, new OpenAiTokenizer("gpt-4o"))
        .build();

```

当历史消息的总 Token 数超过限制时，框架会从最早的消息开始裁剪，保留最新的消息。

### 配合 SystemMessage 使用

```Java
// System Message 不会被裁剪，始终保留在上下文最前面
ChatMemory memory = TokenWindowChatMemory.builder()
        .maxTokens(6000, new OpenAiTokenizer("gpt-4o"))
        .build();

interface CustomerServiceAssistant {
    @SystemMessage("你是专业的客服助手，负责处理订单和售后问题。") // 永久保留
    String chat(String message);
}

CustomerServiceAssistant assistant = AiServices.builder(CustomerServiceAssistant.class)
        .chatLanguageModel(model)
        .chatMemory(memory)
        .build();

```

### 不同模型的 Tokenizer

```Java
// OpenAI 模型
ChatMemory openAiMemory = TokenWindowChatMemory.builder()
        .maxTokens(8000, new OpenAiTokenizer("gpt-4o"))
        .build();

// Anthropic 模型（使用近似 Tokenizer）
ChatMemory anthropicMemory = TokenWindowChatMemory.builder()
        .maxTokens(10000, new AnthropicTokenizer())
        .build();

```

---

## 四、记忆的持久化存储

默认情况下，记忆存储在 JVM 内存中，进程重启后会丢失。生产环境需要持久化存储。

### ChatMemoryStore 接口

```Java
public interface ChatMemoryStore {

    // 根据 memoryId 读取历史消息
    List<ChatMessage> getMessages(Object memoryId);

    // 更新指定 memoryId 的消息列表
    void updateMessages(Object memoryId, List<ChatMessage> messages);

    // 删除指定 memoryId 的所有消息
    void deleteMessages(Object memoryId);
}

```

### 实现 Redis 持久化存储

```Java
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;

@Component
public class RedisChatMemoryStore implements ChatMemoryStore {

    private static final String KEY_PREFIX = "chat:memory:";
    private static final Duration TTL = Duration.ofDays(7); // 记忆保留 7 天

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String key = KEY_PREFIX + memoryId;
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) return new ArrayList<>();
        return ChatMessageDeserializer.messagesFromJson(json);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String key = KEY_PREFIX + memoryId;
        String json = ChatMessageSerializer.messagesToJson(messages);
        redisTemplate.opsForValue().set(key, json, TTL);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        redisTemplate.delete(KEY_PREFIX + memoryId);
    }
}

```

```Java
// 使用 Redis 持久化记忆
@Bean
public Assistant assistant(ChatLanguageModel model, RedisChatMemoryStore memoryStore) {
    return AiServices.builder(Assistant.class)
            .chatLanguageModel(model)
            .chatMemory(MessageWindowChatMemory.builder()
                    .maxMessages(20)
                    .chatMemoryStore(memoryStore)  // 指定持久化存储
                    .build())
            .build();
}

```

### 实现数据库持久化存储

```Java
@Entity
@Table(name = "chat_memory")
public class ChatMemoryEntity {
    @Id
    private String memoryId;

    @Column(columnDefinition = "TEXT")
    private String messagesJson;

    private LocalDateTime updatedAt;
}

```

```Java
@Component
public class JpaChatMemoryStore implements ChatMemoryStore {

    @Autowired
    private ChatMemoryRepository repository;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        return repository.findById(memoryId.toString())
                .map(e -> ChatMessageDeserializer.messagesFromJson(e.getMessagesJson()))
                .orElse(new ArrayList<>());
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        ChatMemoryEntity entity = repository.findById(memoryId.toString())
                .orElse(new ChatMemoryEntity());
        entity.setMemoryId(memoryId.toString());
        entity.setMessagesJson(ChatMessageSerializer.messagesToJson(messages));
        entity.setUpdatedAt(LocalDateTime.now());
        repository.save(entity);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        repository.deleteById(memoryId.toString());
    }
}

```

---

## 五、多用户记忆隔离

生产环境里，最常见的需求是为每个用户维护独立的记忆。

### 使用 @MemoryId 实现隔离

```Java
interface MultiUserAssistant {
    // @MemoryId 标注的参数用于区分不同用户的记忆
    String chat(@MemoryId String userId, @UserMessage String message);
}

```

### chatMemoryProvider：按需创建记忆实例

```Java
@Bean
public MultiUserAssistant multiUserAssistant(
        ChatLanguageModel model,
        RedisChatMemoryStore memoryStore) {

    return AiServices.builder(MultiUserAssistant.class)
            .chatLanguageModel(model)
            // chatMemoryProvider 为每个 memoryId 创建独立的记忆实例
            .chatMemoryProvider(memoryId ->
                    MessageWindowChatMemory.builder()
                            .id(memoryId)
                            .maxMessages(20)
                            .chatMemoryStore(memoryStore)  // 持久化到 Redis
                            .build())
            .build();
}

```

### 验证隔离效果

```Java
// 不同用户的对话完全隔离
assistant.chat("user-alice", "我是 Alice，喜欢 Java");
assistant.chat("user-bob", "我是 Bob，喜欢 Python");

String aliceResponse = assistant.chat("user-alice", "你还记得我喜欢什么语言？");
// 输出：你之前提到你喜欢 Java

String bobResponse = assistant.chat("user-bob", "你还记得我是谁？");
// 输出：你叫 Bob，喜欢 Python

// Alice 的记忆不会出现在 Bob 的对话中，Bob 的记忆也不会影响 Alice

```

### 记忆的主动清除

```Java
// 某些场景需要主动清除记忆（如用户退出、开始新话题）
@Service
public class ConversationService {

    @Autowired
    private RedisChatMemoryStore memoryStore;

    public void clearUserMemory(String userId) {
        memoryStore.deleteMessages(userId);
    }

    // 也可以在接口里定义清除方法
}

// AiServices 接口支持定义 void 方法来清除记忆
interface ClearableAssistant {
    String chat(@MemoryId String userId, @UserMessage String message);

    // 调用此方法会清除指定用户的记忆
    void clearMemory(@MemoryId String userId);
}

```

---

## 六、记忆内容的查看与调试

开发阶段，经常需要查看当前记忆里有什么内容：

```Java
// 直接操作 ChatMemory 对象查看内容
ChatMemory memory = MessageWindowChatMemory.withMaxMessages(10);

// 模拟几轮对话
memory.add(new UserMessage("我叫王五"));
memory.add(new AiMessage("你好，王五！很高兴认识你。"));
memory.add(new UserMessage("我在深圳工作"));
memory.add(new AiMessage("深圳是个很棒的城市！"));

// 查看当前记忆内容
memory.messages().forEach(msg -> {
    String type = msg instanceof UserMessage ? "用户" : "AI";
    String content = msg instanceof UserMessage
            ? ((UserMessage) msg).singleText()
            : ((AiMessage) msg).text();
    System.out.println("[" + type + "] " + content);
});

```

在 Spring Boot 里，可以暴露一个调试接口：

```Java
@RestController
@RequestMapping("/debug/memory")
@Profile("dev") // 仅在开发环境暴露
public class MemoryDebugController {

    @Autowired
    private RedisChatMemoryStore memoryStore;

    @GetMapping("/{userId}")
    public List<Map<String, String>> getMemory(@PathVariable String userId) {
        return memoryStore.getMessages(userId).stream()
                .map(msg -> Map.of(
                        "type", msg.type().name(),
                        "content", msg instanceof UserMessage
                                ? ((UserMessage) msg).singleText()
                                : ((AiMessage) msg).text()
                ))
                .toList();
    }

    @DeleteMapping("/{userId}")
    public void clearMemory(@PathVariable String userId) {
        memoryStore.deleteMessages(userId);
    }
}

```

---

## 七、记忆管理的高级模式

### 摘要记忆（Summary Memory）

当对话非常长时，可以把早期对话压缩成摘要，节省 Token 同时保留关键信息：

```Java
// LangChain4j 目前没有内置 SummaryChatMemory，但可以自己实现
public class SummaryChatMemory implements ChatMemory {

    private final ChatLanguageModel summarizationModel;
    private final int maxMessagesBeforeSummary = 20;
    private final List<ChatMessage> recentMessages = new ArrayList<>();
    private String summary = "";

    @Override
    public void add(ChatMessage message) {
        recentMessages.add(message);
        if (recentMessages.size() >= maxMessagesBeforeSummary) {
            compressToSummary();
        }
    }

    @Override
    public List<ChatMessage> messages() {
        List<ChatMessage> result = new ArrayList<>();
        if (!summary.isEmpty()) {
            // 把摘要作为系统消息注入
            result.add(new SystemMessage("对话历史摘要：" + summary));
        }
        result.addAll(recentMessages);
        return result;
    }

    private void compressToSummary() {
        String prompt = "请将以下对话压缩成一段简洁的摘要，保留关键信息：\n"
                + recentMessages.stream()
                        .map(m -> m.type() + ": " + getText(m))
                        .collect(Collectors.joining("\n"));
        summary = (summary.isEmpty() ? "" : "之前摘要：" + summary + "\n新增：")
                + summarizationModel.generate(prompt);
        recentMessages.clear();
    }
}

```

### 分层记忆

把短期记忆（最近几轮）和长期记忆（关键事实）分开管理：

```Java
public class LayeredMemoryAssistant {

    private final ChatMemory shortTermMemory;     // 最近 10 条消息
    private final Map<String, String> longTermFacts; // 持久化的关键事实

    public String chat(String userId, String message) {
        // 先检查是否需要更新长期记忆
        if (isKeyFact(message)) {
            extractAndStoreFact(userId, message);
        }

        // 构建上下文：长期事实 + 短期记忆
        List<ChatMessage> context = buildContext(userId);
        context.add(new UserMessage(message));

        String response = model.generate(context).content().text();
        shortTermMemory.add(new UserMessage(message));
        shortTermMemory.add(new AiMessage(response));
        return response;
    }
}

```

---

## 八、Spring Boot 完整集成示例

```Java
// 配置类
@Configuration
public class MemoryConfig {

    @Bean
    public ChatMemoryStore chatMemoryStore(StringRedisTemplate redisTemplate) {
        return new RedisChatMemoryStore(redisTemplate);
    }

    @Bean
    public MultiUserAssistant multiUserAssistant(
            ChatLanguageModel model,
            ChatMemoryStore memoryStore) {

        return AiServices.builder(MultiUserAssistant.class)
                .chatLanguageModel(model)
                .chatMemoryProvider(memoryId ->
                        TokenWindowChatMemory.builder()
                                .id(memoryId)
                                .maxTokens(6000, new OpenAiTokenizer("gpt-4o"))
                                .chatMemoryStore(memoryStore)
                                .build())
                .build();
    }
}

```

```Java
// Controller
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    @Autowired
    private MultiUserAssistant assistant;

    @Autowired
    private ChatMemoryStore memoryStore;

    @PostMapping
    public ResponseEntity<String> chat(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody String message) {
        String response = assistant.chat(userId, message);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/memory")
    public ResponseEntity<Void> clearMemory(
            @RequestHeader("X-User-Id") String userId) {
        memoryStore.deleteMessages(userId);
        return ResponseEntity.noContent().build();
    }
}

```

---

## 九、常见坑和最佳实践

### 坑一：记忆无限增长导致 Token 超限

```Java
// 危险：无限制记忆，对话越长越贵，最终超出模型 Token 限制
ChatMemory memory = new UnlimitedChatMemory(); // 不存在，但等价于不设限制

// 正确：始终设置 Token 上限
ChatMemory memory = TokenWindowChatMemory.builder()
        .maxTokens(6000, tokenizer)
        .build();

```

### 坑二：多实例部署时记忆丢失

分布式部署时，不同实例的内存记忆无法共享：

```Java
// 单机可以，分布式不行
ChatMemory memory = MessageWindowChatMemory.withMaxMessages(20);
// 用户请求可能打到不同机器，记忆断裂

// 正确：使用集中式持久化存储
ChatMemory memory = MessageWindowChatMemory.builder()
        .maxMessages(20)
        .chatMemoryStore(redisChatMemoryStore)  // Redis 集中存储
        .build();

```

### 坑三：System Message 被误删

当 Token 超限需要裁剪时，`TokenWindowChatMemory` 会保护 System Message 不被删除；但 `MessageWindowChatMemory` 按消息条数计算时，System Message 也计入总数：

```Java
// 使用 TokenWindowChatMemory 可以确保 System Message 永远不被裁剪
ChatMemory memory = TokenWindowChatMemory.builder()
        .maxTokens(6000, tokenizer)
        .build();

```

### 最佳实践：记忆 TTL 设置

生产环境的 Redis 记忆存储要设置合理的过期时间，避免长期不活跃用户的记忆占用存储资源：

```Java
// Redis 存储实现中设置 TTL
redisTemplate.opsForValue().set(key, json, Duration.ofDays(30)); // 30 天后过期

```

---

## 十、本节小结

这节课我们把 LangChain4j 的记忆管理机制全面讲透了。核心要点：

- **记忆原理**：LLM 无状态，框架在每次调用前自动把历史消息拼入上下文
- **MessageWindowChatMemory**：按消息条数限制，简单易用，适合开发阶段
- **TokenWindowChatMemory**：按 Token 数限制，更精确，推荐生产环境使用
- **持久化存储**：实现 `ChatMemoryStore` 接口，支持 Redis、数据库等各类存储
- **多用户隔离**：`@MemoryId` + `chatMemoryProvider` 为每个用户创建独立记忆实例
- **生产实践**：始终设置上限、使用集中式存储、设置 TTL、System Message 优先保护

记忆管理是 Agent 系统的基础能力。掌握了它，你的 Agent 就能在多轮对话中保持连贯性，真正做到"记住用户、理解上下文"。

下一节课，我们来讲第二章的最后一节：**LangChain4j 的扩展性与自定义组件——如何编写自己的模型、工具、内存实现**，为大家系统梳理整个框架的扩展点。

---

## 十一、引用文档与延伸阅读

- LangChain4j 记忆管理官方文档：https://docs.langchain4j.dev/tutorials/chat-memory
- LangChain4j MessageWindowChatMemory 源码：https://github.com/langchain4j/langchain4j/blob/main/langchain4j/src/main/java/dev/langchain4j/memory/chat/MessageWindowChatMemory.java
- LangChain4j TokenWindowChatMemory 源码：https://github.com/langchain4j/langchain4j/blob/main/langchain4j/src/main/java/dev/langchain4j/memory/chat/TokenWindowChatMemory.java
- LangChain4j ChatMemoryStore 接口：https://github.com/langchain4j/langchain4j/blob/main/langchain4j-core/src/main/java/dev/langchain4j/store/memory/chat/ChatMemoryStore.java
- LangChain4j AiServices @MemoryId 文档：https://docs.langchain4j.dev/tutorials/ai-services#memory
- Spring Data Redis 官方文档：https://docs.spring.io/spring-data/redis/reference/
- GitHub 示例代码：https://github.com/langchain4j/langchain4j-examples/tree/main/other-examples/src/main/java/ServiceWithMemoryExample.java

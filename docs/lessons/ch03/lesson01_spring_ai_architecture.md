# 第3章第1节：Spring AI设计理念与整体架构：面向Spring开发者的AI抽象层

大家好，我是秋天。

从这一节课开始，我们进入第三章：**Spring AI 框架深度解析**。

如果说 LangChain4j 是 Java AI 生态的先行者，那么 Spring AI 就是 Spring 官方对 AI 时代的正式回应。

这节课，我们先从整体视角把 Spring AI 看清楚：**它为什么会出现、它解决什么问题、它和 LangChain4j 的本质区别在哪里，以及它的整体架构是怎么设计的。**

学完这节课，你会掌握：

1. Spring AI 的诞生背景与设计目标
2. Spring AI 的整体架构与核心模块
3. Spring AI 与 LangChain4j 的异同与选型策略
4. Spring AI 的快速上手：环境搭建与第一个 AI 应用
5. Spring AI 生态现状与未来方向

---

## 一、Spring AI 为什么会出现

### Spring 生态的一贯做法

如果你用过 Spring Data，你会发现：不管底层是 MySQL、MongoDB 还是 Redis，你操作数据的方式都是一样的——统一的 Repository 接口、统一的注解、统一的异常体系。

这就是 Spring 的核心价值：**把复杂的基础设施细节抽象掉，让开发者专注在业务逻辑上。**

Spring AI 做的事情完全一样，只不过面对的是 AI 基础设施：

- 不管底层是 OpenAI、Anthropic、Azure 还是 Ollama，调用模型的方式都一样
- 不管向量库是 PgVector、Redis 还是 Milvus，存取向量的方式都一样
- 不管是同步调用还是流式响应，API 风格都和 Spring 生态高度一致

### Spring AI 的三个核心目标

1. **可移植性**：业务代码不依赖具体 AI 提供商，随时可以切换
2. **生产级别**：开箱即用的可观测性、重试、限流、健康检查
3. **Spring 原生**：与 Spring Boot、Spring MVC、Spring WebFlux 无缝集成，开发者零额外学习成本

```Plain Text
如果你的团队是 Spring 技术栈，Spring AI 是阻力最小的 AI 接入路径。
你不需要学习全新的框架，只需要在已有体系里加几个 Bean 和注解。

```

---

## 二、Spring AI 整体架构

Spring AI 的架构分为四层：

```Plain Text
┌──────────────────────────────────────────────────────────────┐
│                     应用层（Application Layer）                │
│          ChatClient / Advisors / AI Functions                 │
├──────────────────────────────────────────────────────────────┤
│                     核心层（Core Abstractions）                │
│   ChatModel / EmbeddingModel / ImageModel / AudioModel        │
│   VectorStore / PromptTemplate / OutputConverter              │
├──────────────────────────────────────────────────────────────┤
│                     集成层（Integrations）                     │
│  OpenAI / Anthropic / Azure / Ollama / Google / Mistral ...  │
│  PgVector / Redis / Chroma / Milvus / Pinecone / Weaviate ... │
├──────────────────────────────────────────────────────────────┤
│                 Spring Boot 自动配置层（Auto-Configuration）    │
│      Starters / Properties / Health Indicators / Metrics      │
└──────────────────────────────────────────────────────────────┘

```

### 核心层：统一抽象接口

| 接口 | 职责 |
|-|-|
| `ChatModel` | 同步聊天模型调用 |
| `StreamingChatModel` | 流式聊天模型调用 |
| `EmbeddingModel` | 文本向量化 |
| `ImageModel` | 图像生成 |
| `AudioTranscriptionModel` | 语音转文字 |
| `VectorStore` | 向量存储与相似度检索 |

### 应用层：ChatClient

`ChatClient` 是 Spring AI 面向开发者的核心高级 API，类似于 LangChain4j 的 AiServices，但风格更接近 Spring WebClient 的流畅链式调用：

```Java
// Spring AI 的风格：流畅链式调用
String response = chatClient.prompt()
        .system("你是一个专业的 Java 架构师")
        .user("如何设计一个高可用的微服务系统？")
        .call()
        .content();

```

---

## 三、与 LangChain4j 的核心区别

很多同学会问：Spring AI 和 LangChain4j 哪个更好？

这个问题没有标准答案，但有清晰的选型逻辑：

| 维度 | Spring AI | LangChain4j |
|-|-|-|
| 设计风格 | Spring 原生，Builder + 自动配置 | 声明式接口 + 注解驱动 |
| 学习曲线 | Spring 开发者几乎零成本 | 需要学习 AiServices 体系 |
| 功能完整性 | 持续快速迭代，核心功能完备 | 功能更全，RAG 更成熟 |
| 多 Agent 支持 | Spring AI Graph（实验性） | 基础支持 |
| 社区生态 | Spring 官方，长期维护保障 | 开源社区，活跃度高 |
| 最适合场景 | 已有 Spring Boot 项目快速接入 AI | 新 AI 项目，需要完整 Agent 能力 |

**实战建议：两者并不互斥，可以组合使用。**

- 用 Spring AI 做基础的模型调用、向量存储、RAG
- 用 LangChain4j 的 AiServices 做声明式 Agent 定义
- 用 Spring AI 的 Advisors 做横切关注点（日志、安全、缓存）

---

## 四、快速上手：环境搭建

### Maven 依赖

Spring AI 通过 BOM 管理版本，推荐方式：

```XML
<!-- pom.xml -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>1.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- Spring Boot Web -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- Spring AI OpenAI Starter -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-openai-spring-boot-starter</artifactId>
    </dependency>

    <!-- 可选：向量存储（PgVector）-->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-pgvector-store-spring-boot-starter</artifactId>
    </dependency>
</dependencies>

```

### application.yml 配置

```YAML
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: gpt-4o
          temperature: 0.7
          max-tokens: 2048
      embedding:
        options:
          model: text-embedding-3-small

# 如果使用 Anthropic
# spring:
#   ai:
#     anthropic:
#       api-key: ${ANTHROPIC_API_KEY}
#       chat:
#         options:
#           model: claude-sonnet-4-6
#           max-tokens: 4096

```

### 自动配置效果

只需要加 Starter 依赖和配置 API Key，Spring AI 会自动创建并注入：

- `ChatModel` Bean（同步模型）
- `StreamingChatModel` Bean（流式模型）
- `EmbeddingModel` Bean（向量化模型）
- `ChatClient.Builder` Bean（高级 API 构建器）

---

## 五、第一个 Spring AI 应用

### 最简单的调用

```Java
@SpringBootApplication
public class SpringAiDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringAiDemoApplication.class, args);
    }
}

```

```Java
@RestController
@RequestMapping("/ai")
public class SimpleAiController {

    private final ChatClient chatClient;

    // ChatClient.Builder 由 Spring AI 自动注入
    public SimpleAiController(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @GetMapping("/ask")
    public String ask(@RequestParam String question) {
        return chatClient.prompt()
                .user(question)
                .call()
                .content();
    }
}

```

启动应用后，访问：

```Plain Text
GET /ai/ask?question=什么是Spring AI？

```

就能得到 AI 的回答。整个过程不需要写任何模型调用代码。

### 带系统提示词的调用

```Java
@Service
public class JavaAssistantService {

    private final ChatClient chatClient;

    public JavaAssistantService(ChatClient.Builder builder) {
        // 在构建时设置默认系统提示词
        this.chatClient = builder
                .defaultSystem("你是一个资深 Java 架构师，专注于企业级应用开发。" +
                               "回答要简洁专业，关键地方给出代码示例。")
                .build();
    }

    public String ask(String question) {
        return chatClient.prompt()
                .user(question)
                .call()
                .content();
    }

    // 动态覆盖系统提示词
    public String askAsRole(String role, String question) {
        return chatClient.prompt()
                .system("你是一个" + role)
                .user(question)
                .call()
                .content();
    }
}

```

---

## 六、ChatClient 核心 API 详解

`ChatClient` 是 Spring AI 的核心调用入口，支持多种使用方式。

### 同步调用

```Java
// 返回文本内容
String content = chatClient.prompt()
        .user("解释 Java 虚拟线程")
        .call()
        .content();

// 返回完整响应（包含 Token 使用量等元数据）
ChatResponse response = chatClient.prompt()
        .user("解释 Java 虚拟线程")
        .call()
        .chatResponse();

// 返回结构化对象
record JavaFeature(String name, String version, String description) {}

JavaFeature feature = chatClient.prompt()
        .user("介绍 Java 虚拟线程特性")
        .call()
        .entity(JavaFeature.class);  // 自动解析为 Java 对象

```

### 流式调用

```Java
// 返回 Flux<String>，适合 SSE 实时输出
Flux<String> stream = chatClient.prompt()
        .user("请详细介绍 Spring AI 的核心特性")
        .stream()
        .content();

// 在 Spring MVC Controller 中使用
@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> stream(@RequestParam String question) {
    return chatClient.prompt()
            .user(question)
            .stream()
            .content();
}

```

### 携带消息历史

```Java
// 手动管理消息历史
List<Message> history = new ArrayList<>();
history.add(new UserMessage("我叫王五"));
history.add(new AssistantMessage("你好王五！"));
history.add(new UserMessage("你还记得我叫什么？"));

String response = chatClient.prompt()
        .messages(history)
        .call()
        .content();

```

---

## 七、Spring AI 的 Advisors：横切关注点

`Advisor` 是 Spring AI 的一个核心设计——类似于 Spring AOP，它允许你在模型调用的前后注入横切逻辑。

内置的 Advisor 包括：

```Java
import org.springframework.ai.chat.client.advisor.*;

// ① 对话记忆 Advisor（最常用）
MessageChatMemoryAdvisor memoryAdvisor = new MessageChatMemoryAdvisor(
        new InMemoryChatMemory()  // 或 Redis、数据库实现
);

// ② 问题回答 Advisor（RAG）
QuestionAnswerAdvisor ragAdvisor = new QuestionAnswerAdvisor(vectorStore);

// ③ SafeGuard Advisor（内容安全过滤）
SafeGuardAdvisor safeGuardAdvisor = new SafeGuardAdvisor();

// ④ 日志记录 Advisor
SimpleLoggerAdvisor loggerAdvisor = new SimpleLoggerAdvisor();

```

```Java
// 把多个 Advisor 组合到 ChatClient
ChatClient chatClient = builder
        .defaultSystem("你是一个专业助手")
        .defaultAdvisors(
                memoryAdvisor,    // 自动管理对话记忆
                ragAdvisor,       // 自动检索相关知识
                loggerAdvisor     // 自动记录日志
        )
        .build();

// 调用时自动应用所有 Advisor
String response = chatClient.prompt()
        .user("帮我查一下上次的订单状态")
        .advisorParam(CHAT_MEMORY_CONVERSATION_ID_KEY, "user-001")  // 传递会话 ID
        .call()
        .content();

```

---

## 八、多模型提供商切换

Spring AI 的最大价值之一是无缝切换模型提供商。

### Anthropic 配置

```YAML
spring:
  ai:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
      chat:
        options:
          model: claude-sonnet-4-6
          max-tokens: 4096

```

```XML
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-anthropic-spring-boot-starter</artifactId>
</dependency>

```

### Ollama 本地模型配置

```YAML
spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        options:
          model: llama3.2
          temperature: 0.7

```

```XML
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-ollama-spring-boot-starter</artifactId>
</dependency>

```

### 业务代码完全不变

```Java
// 不管底层是 OpenAI、Anthropic 还是 Ollama，这段代码一行不改
@Service
public class MyService {

    private final ChatClient chatClient;

    public MyService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    public String process(String input) {
        return chatClient.prompt()
                .user(input)
                .call()
                .content();
    }
}

```

切换模型只需改 `pom.xml` 的 Starter 依赖和 `application.yml` 的配置，业务代码零修改。

---

## 九、Spring AI 的可观测性支持

Spring AI 与 Spring Boot Actuator 深度集成，开箱即用地提供：

### Metrics（指标）

自动暴露的 Micrometer 指标：

- `spring.ai.chat.client.operation.seconds`：请求延迟分布
- `spring.ai.chat.client.token.usage`：Token 消耗统计

```YAML
# application.yml 开启所有指标端点
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
  metrics:
    tags:
      application: ${spring.application.name}

```

```Bash
# 查看 AI 相关指标
GET /actuator/metrics/spring.ai.chat.client.operation.seconds

```

### Tracing（链路追踪）

```XML
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>

```

```YAML
management:
  tracing:
    sampling:
      probability: 1.0   # 开发阶段全量采样

```

每次 AI 请求都会自动生成 Trace，可以在 Jaeger、Zipkin 等系统中查看完整链路。

---

## 十、Spring AI 生态现状

### 当前版本稳定支持的能力

- **聊天模型**：OpenAI、Anthropic、Azure OpenAI、Google Gemini、Mistral、Ollama、HuggingFace 等 20+ 个提供商
- **向量数据库**：PgVector、Redis、Chroma、Milvus、Pinecone、Weaviate、Elasticsearch 等 15+ 个
- **RAG**：文档加载、文本切分、向量化、检索、QuestionAnswer Advisor
- **函数调用**：通过 `@Bean` 定义函数，调用时自动路由执行
- **结构化输出**：`entity(Class)` 直接返回 Java 对象
- **流式输出**：`stream()` 返回 `Flux`，天然支持 SSE

### 快速发展中的能力

- **Spring AI Graph**：多 Agent 工作流编排（类似 LangGraph，目前处于实验阶段）
- **MCP（Model Context Protocol）支持**：接入 Anthropic 的 MCP 标准
- **Agent 框架**：更完整的 Agent 能力正在规划中

---

## 十一、本节小结

这节课我们从整体视角把 Spring AI 看清楚了。核心要点：

- **设计目标**：可移植性、生产级别、Spring 原生——让 Spring 开发者零成本接入 AI
- **架构四层**：自动配置层 → 集成层 → 核心抽象层 → 应用层（ChatClient / Advisors）
- **核心 API**：`ChatClient` 流畅链��调用，支持同步、流式、结构化输出
- **Advisors**：横切关注点的优雅实现——记忆、RAG、安全、日志开箱即用
- **多模型切换**：换 Starter + 改配置，业务代码零修改
- **可观测性**：与 Actuator + Micrometer 深度集成，指标和链路追踪开箱即用

Spring AI 的价值不是"比 LangChain4j 更强"，而是"让 Spring 开发者能用最熟悉的方式接入 AI"。它降低了 Spring 团队的接入门槛，同时提供了生产级别的工程保障。

下一节课，我们深入 Spring AI 的 `ChatClient` 与 `Advisor` 机制，看看如何用它构建生产级别的对话 Agent。

---

## 十二、引用文档与延伸阅读

- Spring AI 官方文档：https://docs.spring.io/spring-ai/reference/
- Spring AI GitHub：https://github.com/spring-projects/spring-ai
- Spring AI 快速入门：https://docs.spring.io/spring-ai/reference/getting-started.html
- Spring AI 支持的模型列表：https://docs.spring.io/spring-ai/reference/api/chat.html
- Spring AI 支持的向量数据库：https://docs.spring.io/spring-ai/reference/api/vectordbs.html
- Spring AI Advisors 文档：https://docs.spring.io/spring-ai/reference/api/advisors.html
- Spring AI 可观测性文档：https://docs.spring.io/spring-ai/reference/observability/index.html
- Spring AI BOM 最新版本：https://central.sonatype.com/artifact/org.springframework.ai/spring-ai-bom
- GitHub 示例代码：https://github.com/spring-projects/spring-ai-examples

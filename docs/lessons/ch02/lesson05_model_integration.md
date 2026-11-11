# 第2章第5节：模型集成（Model Integration）：OpenAI、Azure、Ollama、HuggingFace的适配器模式

大家好，我是秋天。

上一节课我们把工具调用从底层原理到实践全部讲透了。

这节课，我们来讲 LangChain4j 的模型集成层：**如何通过适配器模式，把 OpenAI、Azure、Ollama、HuggingFace 等不同厂商的模型，统一接入到你的 Agent 系统里。**

这节课的核心价值在于：**你只需要写一套业务代码，就能自由切换任何模型提供商，不改一行业务逻辑。**

学完这节课，你会掌握：

1. LangChain4j 模型集成层的适配器模式设计
2. 主流模型提供商的接入方式：OpenAI、Azure OpenAI、Anthropic、Ollama、HuggingFace
3. 模型参数的配置与调优
4. 多模型并行与降级策略
5. 自定义模型适配器的实现方式

---

## 一、适配器模式：统一抽象的力量

### 为什么需要适配器模式

不同模型提供商的 API 格式完全不同：

- OpenAI 用 `/v1/chat/completions`，请求体有 `model`、`messages`、`temperature`
- Anthropic 用 `/v1/messages`，请求体有 `model`、`messages`、`max_tokens`，还需要 `anthropic-version` Header
- Ollama 是本地服务，用 `/api/chat`，格式又不一样
- Azure OpenAI 需要额外的 `api-version` 参数和不同的 endpoint

如果业务代码直接依赖这些具体实现，换一个模型就要改大量代码。

**LangChain4j 的解决方案是：定义统一的 ChatLanguageModel 接口，每个模型提供商实现自己的适配器。**

```Plain Text
业务层（AiServices / Chain）
        ↓ 只依赖接口
ChatLanguageModel（统一抽象接口）
        ↓ 各自实现
OpenAiChatModel | AnthropicChatModel | OllamaChatModel | AzureOpenAiChatModel | ...
        ↓ 各自调用
OpenAI API | Anthropic API | 本地 Ollama | Azure API | ...

```

### 核心接口

```Java
// 这是整个模型集成层的核心抽象（简化版）
public interface ChatLanguageModel {

    // 最简单的调用方式
    default String generate(String userMessage) { ... }

    // 标准调用方式
    Response<AiMessage> generate(List<ChatMessage> messages);

    // 带工具的调用方式
    Response<AiMessage> generate(List<ChatMessage> messages,
                                  List<ToolSpecification> toolSpecifications);
}

```

业务层只依赖这个接口，模型切换对业务层完全透明。

---

## 二、OpenAI 模型接入

OpenAI 是目前最主流的模型提供商，LangChain4j 对它的支持最完整。

### Maven 依赖

```XML
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-open-ai</artifactId>
    <version>0.36.0</version>
</dependency>

```

### 基础配置

```Java
import dev.langchain4j.model.openai.OpenAiChatModel;

ChatLanguageModel model = OpenAiChatModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-4o")                    // 模型名称
        .temperature(0.7)                        // 创造性（0-2，越高越发散）
        .topP(1.0)                               // 核采样（0-1）
        .maxTokens(2048)                         // 最大输出 Token 数
        .timeout(Duration.ofSeconds(60))         // 超时时间
        .maxRetries(3)                           // 自动重试次数
        .logRequests(true)                       // 打印请求日志（开发阶段用）
        .logResponses(true)                      // 打印响应日志
        .build();

```

### 流式模型

```Java
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

StreamingChatLanguageModel streamingModel = OpenAiStreamingChatModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-4o")
        .temperature(0.7)
        .build();

```

### 嵌入模型

```Java
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

EmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("text-embedding-3-small")    // 小模型，成本低
        // .modelName("text-embedding-3-large") // 大模型，效果更好
        .build();

```

### 常用模型对比

| 模型 | 特点 | 推荐场景 |
|-|-|-|
| `gpt-4o` | 最强，多模态，速度快 | 复杂推理、Agent 核心模型 |
| `gpt-4o-mini` | 性价比高，速度极快 | 简单任务、高并发场景 |
| `o1` | 深度推理，慢但准 | 数学、代码、复杂分析 |

---

## 三、Anthropic Claude 模型接入

```XML
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-anthropic</artifactId>
    <version>0.36.0</version>
</dependency>

```

```Java
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicChatModelName;

ChatLanguageModel model = AnthropicChatModel.builder()
        .apiKey(System.getenv("ANTHROPIC_API_KEY"))
        .modelName(AnthropicChatModelName.CLAUDE_SONNET_4_6)  // 使用枚举，避免拼写错误
        .maxTokens(4096)
        .temperature(0.7)
        .timeout(Duration.ofSeconds(60))
        .build();

```

Anthropic 模型名称枚举（避免硬编码字符串）：

```Java
// LangChain4j 提供的枚举常量
AnthropicChatModelName.CLAUDE_3_5_SONNET_20241022
AnthropicChatModelName.CLAUDE_3_5_HAIKU_20241022
AnthropicChatModelName.CLAUDE_OPUS_4
AnthropicChatModelName.CLAUDE_SONNET_4_6

```

---

## 四、Azure OpenAI 接入

企业用户常用 Azure OpenAI，它和 OpenAI API 格式基本一致，但 endpoint 和认证方式不同。

```XML
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-azure-open-ai</artifactId>
    <version>0.36.0</version>
</dependency>

```

```Java
import dev.langchain4j.model.azure.AzureOpenAiChatModel;

ChatLanguageModel model = AzureOpenAiChatModel.builder()
        .apiKey(System.getenv("AZURE_OPENAI_API_KEY"))
        .endpoint("https://your-resource.openai.azure.com/")  // Azure 资源 endpoint
        .deploymentName("gpt-4o")                             // 你的部署名称
        .serviceVersion("2024-05-01-preview")                 // API 版本
        .temperature(0.7)
        .maxTokens(2048)
        .build();

```

---

## 五、Ollama 本地模型接入

Ollama 让你在本地运行开源模型（Llama、Qwen、Mistral 等），非常适合：

- 开发阶段降低 API 成本
- 数据不出境的合规场景
- 离线环境部署

```XML
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-ollama</artifactId>
    <version>0.36.0</version>
</dependency>

```

```Bash
# 先在本地启动 Ollama 并拉取模型
ollama pull llama3.2
ollama pull qwen2.5:7b
ollama serve

```

```Java
import dev.langchain4j.model.ollama.OllamaChatModel;

ChatLanguageModel model = OllamaChatModel.builder()
        .baseUrl("http://localhost:11434")    // Ollama 默认地址
        .modelName("llama3.2")               // 已拉取的模型名
        .temperature(0.7)
        .timeout(Duration.ofMinutes(5))      // 本地模型通常较慢，超时要设长一些
        .build();

```

**切换到 OpenAI 时，业务代码一行不改：**

```Java
// 开发阶段：用本地 Ollama（零成本）
ChatLanguageModel model = OllamaChatModel.builder()
        .baseUrl("http://localhost:11434")
        .modelName("llama3.2")
        .build();

// 生产阶段：切换到 GPT-4o（高性能）
// ChatLanguageModel model = OpenAiChatModel.builder()
//         .apiKey(System.getenv("OPENAI_API_KEY"))
//         .modelName("gpt-4o")
//         .build();

// 业务代码完全不变
CustomerServiceAgent agent = AiServices.builder(CustomerServiceAgent.class)
        .chatLanguageModel(model)  // 只改这一行
        .tools(tools)
        .build();

```

---

## 六、HuggingFace 模型接入

HuggingFace 上有海量开源模型，LangChain4j 支持通过 Inference API 接入：

```XML
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-hugging-face</artifactId>
    <version>0.36.0</version>
</dependency>

```

```Java
import dev.langchain4j.model.huggingface.HuggingFaceChatModel;

ChatLanguageModel model = HuggingFaceChatModel.builder()
        .accessToken(System.getenv("HF_API_TOKEN"))
        .modelId("meta-llama/Llama-3.2-3B-Instruct")  // HuggingFace 模型 ID
        .timeout(Duration.ofSeconds(60))
        .temperature(0.7)
        .maxNewTokens(512)
        .build();

```

---

## 七、Spring Boot 自动配置

在 Spring Boot 项目里，LangChain4j 提供了自动配置支持，无需手动创建 Bean：

```XML
<!-- OpenAI Spring Boot Starter -->
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
      max-tokens: 2048
      timeout: 60s
      max-retries: 3
      log-requests: true
      log-responses: false
    embedding-model:
      api-key: ${OPENAI_API_KEY}
      model-name: text-embedding-3-small
    streaming-chat-model:
      api-key: ${OPENAI_API_KEY}
      model-name: gpt-4o

```

```Java
// 直接注入，无需手动配置
@Service
public class MyService {

    @Autowired
    private ChatLanguageModel chatModel;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private StreamingChatLanguageModel streamingModel;
}

```

对应的 Ollama Starter：

```YAML
langchain4j:
  ollama:
    chat-model:
      base-url: http://localhost:11434
      model-name: llama3.2
      temperature: 0.7
      timeout: 5m

```

---

## 八、多模型策略：降级与路由

生产环境里，通常需要多模型配合使用，实现降级和路由。

### 主备降级

```Java
public class FallbackChatModel implements ChatLanguageModel {

    private final ChatLanguageModel primary;    // 主模型：GPT-4o
    private final ChatLanguageModel fallback;   // 备用模型：GPT-4o-mini 或本地 Ollama

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        try {
            return primary.generate(messages);
        } catch (Exception e) {
            log.warn("主模型调用失败，切换到备用模型: {}", e.getMessage());
            return fallback.generate(messages);
        }
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages,
                                         List<ToolSpecification> toolSpecifications) {
        try {
            return primary.generate(messages, toolSpecifications);
        } catch (Exception e) {
            log.warn("主模型调用失败，切换到备用模型: {}", e.getMessage());
            return fallback.generate(messages, toolSpecifications);
        }
    }
}

```

```Java
// 使用降级模型
ChatLanguageModel model = new FallbackChatModel(
        OpenAiChatModel.builder().apiKey(apiKey).modelName("gpt-4o").build(),
        OllamaChatModel.builder().baseUrl("http://localhost:11434").modelName("llama3.2").build()
);

```

### 按场景路由

```Java
public class RoutingChatModel implements ChatLanguageModel {

    private final ChatLanguageModel powerfulModel;    // 强模型：复杂任务
    private final ChatLanguageModel efficientModel;   // 轻量模型：简单任务
    private final TaskClassifier classifier;           // 任务分类器

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        TaskComplexity complexity = classifier.classify(messages);
        ChatLanguageModel selected = switch (complexity) {
            case HIGH -> powerfulModel;
            case LOW -> efficientModel;
        };
        log.debug("任务复杂度：{}，使用模型：{}", complexity, selected.getClass().getSimpleName());
        return selected.generate(messages);
    }
}

```

---

## 九、自定义模型适配器

如果你需要接入 LangChain4j 尚未支持的模型（如公司内部自研模型、国内模型等），只需实现 `ChatLanguageModel` 接口：

```Java
public class CustomModelAdapter implements ChatLanguageModel {

    private final String endpoint;
    private final String apiKey;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public CustomModelAdapter(String endpoint, String apiKey) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(60))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        try {
            // 1. 把 LangChain4j 的消息格式转成内部模型的格式
            String requestBody = buildRequestBody(messages);

            // 2. 发送 HTTP 请求
            Request request = new Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody, MediaType.get("application/json")))
                    .build();

            try (okhttp3.Response httpResponse = httpClient.newCall(request).execute()) {
                String responseBody = httpResponse.body().string();

                // 3. 把响应转成 LangChain4j 的格式
                String content = parseResponse(responseBody);
                return Response.from(AiMessage.from(content));
            }
        } catch (IOException e) {
            throw new RuntimeException("自定义模型调用失败", e);
        }
    }

    private String buildRequestBody(List<ChatMessage> messages) throws JsonProcessingException {
        // 把 LangChain4j 的 ChatMessage 列表转成你的内部模型 API 需要的格式
        List<Map<String, String>> converted = messages.stream()
                .map(msg -> Map.of(
                        "role", msg.type().name().toLowerCase(),
                        "content", ((TextContent) msg).text()
                ))
                .toList();
        return objectMapper.writeValueAsString(Map.of("messages", converted));
    }

    private String parseResponse(String responseBody) throws JsonProcessingException {
        // 解析你的内部模型 API 的响应格式
        JsonNode root = objectMapper.readTree(responseBody);
        return root.path("choices").get(0).path("message").path("content").asText();
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages,
                                         List<ToolSpecification> toolSpecifications) {
        // 如果内部模型支持 Function Calling，在这里实现
        // 如果不支持，可以用提示词方式模拟
        throw new UnsupportedOperationException("该模型不支持工具调用");
    }
}

```

```Java
// 像使用任何其他模型一样使用自定义适配器
ChatLanguageModel model = new CustomModelAdapter(
        "https://your-internal-model.company.com/v1/chat",
        System.getenv("INTERNAL_MODEL_API_KEY")
);

CustomerServiceAgent agent = AiServices.builder(CustomerServiceAgent.class)
        .chatLanguageModel(model)
        .tools(tools)
        .build();

```

---

## 十、模型参数调优指南

不同场景下，模型参数需要针对性调整。

### Temperature（温度）

控制输出的随机性和创造性：

```Java
// 低温度（0.0-0.3）：确定性强，适合结构化任务
// 用于：代码生成、数据提取、格式化输出、工具调用
ChatLanguageModel codeModel = OpenAiChatModel.builder()
        .temperature(0.1)
        .build();

// 中温度（0.5-0.8）：平衡创造性和准确性
// 用于：对话助手、问答、摘要
ChatLanguageModel chatModel = OpenAiChatModel.builder()
        .temperature(0.7)
        .build();

// 高温度（1.0-1.5）：创造性强，适合创意任务
// 用于：文案写作、故事创作、头脑风暴
ChatLanguageModel creativeModel = OpenAiChatModel.builder()
        .temperature(1.2)
        .build();

```

### maxTokens 与 Token 成本控制

```Java
// 简单问答：控制在较小范围，节省成本
ChatLanguageModel economyModel = OpenAiChatModel.builder()
        .maxTokens(512)    // 约 350-400 个中文字
        .build();

// 长文本生成：放大
ChatLanguageModel longFormModel = OpenAiChatModel.builder()
        .maxTokens(4096)
        .build();

```

---

## 十一、本节小结

这节课我们把 LangChain4j 的模型集成层全面讲透了。核心要点：

- **适配器模式**：`ChatLanguageModel` 统一接口，屏蔽不同模型提供商的 API 差异
- **主流接入**：OpenAI、Anthropic、Azure OpenAI、Ollama、HuggingFace 各有对应的适配器模块
- **Spring Boot 集成**：通过 Starter + YAML 配置，无需手写 Bean
- **多模型策略**：主备降级、按场景路由，让系统更健壮
- **自定义适配器**：实现 `ChatLanguageModel` 接口，即可接入任何内部或第三方模型
- **参数调优**：Temperature 控制创造性，maxTokens 控制成本，按场景针对性配置

掌握了模型集成层，你的 Agent 系统就拥有了"模型无关性"——无论底层用什么模型，业务代码始终保持稳定。

下一节课，我们会深入提示词模板（Prompt Templates）与变量注入机制，看看如何优雅地管理复杂的提示词逻辑。

---

## 十二、引用文档与延伸阅读

- LangChain4j 模型集成官方文档：https://docs.langchain4j.dev/integrations/language-models
- LangChain4j OpenAI 集成：https://docs.langchain4j.dev/integrations/language-models/open-ai
- LangChain4j Anthropic 集成：https://docs.langchain4j.dev/integrations/language-models/anthropic
- LangChain4j Azure OpenAI 集成：https://docs.langchain4j.dev/integrations/language-models/azure-open-ai
- LangChain4j Ollama 集成：https://docs.langchain4j.dev/integrations/language-models/ollama
- LangChain4j HuggingFace 集成：https://docs.langchain4j.dev/integrations/language-models/hugging-face
- Ollama 官方文档：https://ollama.com/docs
- OpenAI 模型列表：https://platform.openai.com/docs/models
- Anthropic 模型列表：https://docs.anthropic.com/en/docs/about-claude/models
- GitHub 示例代码：https://github.com/langchain4j/langchain4j-examples

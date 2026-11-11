# 第1章第4节：Java生态与LLM的交互方式：HTTP Client、WebClient、OkHttp、RestTemplate对比

大家好，我是秋天。

这一节课，我们不讲抽象概念，而是直接回到 Java 工程实践里，一个非常现实的问题：

**当我们在 Java 生态里接入大语言模型时，到底应该用哪种 HTTP 调用方式？**

很多同学第一次做 LLM 应用时，会把重点放在 Prompt、模型参数、RAG、Agent 工作流上，但真正落地到 Java 项目里时，第一个绕不开的问题往往不是"模型怎么选"，而是：

- 怎么发 HTTP 请求？
- 怎么处理流式输出？
- 怎么管理超时、连接池和重试？
- Spring 项目里应该用原生 JDK，还是 WebClient，还是 OkHttp？
- 老项目里已经有 RestTemplate，要不要换？

<callout emoji="💡">
LLM 接入，表面上是"调用一个模型 API"，本质上是"设计一个稳定、可维护、可扩展的 AI 通信层"。如果这个通信层设计得不好，后面你做多模型切换、流式响应、工具调用、观测埋点、鉴权管理、成本统计，都会非常痛苦。
</callout>

这一节课，我们就系统把 Java 生态里四种最常见的调用方式讲清楚，重点回答四个问题：它们分别适合什么场景？接 LLM 时最大的差异点是什么？如果你要做流式输出、SSE、异步并发，应该怎么选？在企业 Java 项目里，什么方案最值得长期投入？

---

## 一、接 LLM，本质上是在做 HTTP 客户端选型

绝大多数 LLM 服务，不管你接的是 OpenAI 风格接口、Anthropic 风格接口，还是企业内部模型网关，本质上都离不开这几件事：

- 发起 HTTPS 请求，传 JSON 请求体，带鉴权 Header
- 解析 JSON 响应，可能处理流式返回
- 处理超时、限流、重试、日志与观测

换句话说，**LLM 接入不是特殊魔法，它首先是一个典型的 HTTP Client 工程问题。**

最小请求示例：

```Java
String requestBody = """
{
  "model": "claude-sonnet-4-6",
  "max_tokens": 1024,
  "messages": [
    {"role": "user", "content": "请总结一下 Transformer 的核心思想"}
  ]
}
""";

```

```HTTP
Content-Type: application/json
Authorization: Bearer <API_KEY>
Anthropic-Version: 2023-06-01

```

真正的差别不是"能不能调"，而是：调得优不优雅、调得稳不稳定、能不能支持后续复杂需求。

---

## 二、四种方案对比总览

| **方案** | **定位** | **优点** | **缺点** | **推荐度** |
|-|-|-|-|-|
| `HttpClient` | JDK 原生 | 无额外依赖、轻量、标准化 | 流式与高级能力不如专业库顺手 | 中高 |
| `WebClient` | Spring Reactive | 异步、响应式、流式处理强、适合现代 Spring | 学习成本高，需理解 Reactor | 很高 |
| `OkHttp` | 通用高性能客户端 | 成熟、易用、拦截器强、生态好 | 需额外依赖，与 Spring 体系不完全一体 | 很高 |
| `RestTemplate` | 传统 Spring 同步客户端 | 老项目常见、接入成本低 | 已进入维护模式，不适合流式 LLM 场景 | 低 |

<callout emoji="✅">
新项目优先考虑 **WebClient** 或 **OkHttp**；JDK 原生 `HttpClient` 适合轻量集成；`RestTemplate` 适合维护老系统，不建议作为新一代 LLM 接入主方案。
</callout>

---

## 三、方案一：JDK 原生 HttpClient

从 Java 11 开始，JDK 提供了标准的 `java.net.http.HttpClient`，无需额外引依赖。优势：标准库自带、API 现代、支持同步与异步、适合轻量项目和 SDK 封装。

```Java
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class LlmHttpClientDemo {
    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        String requestBody = """
        {
          "model": "claude-sonnet-4-6",
          "max_tokens": 512,
          "messages": [
            {"role": "user", "content": "请解释什么是 Agent 的自主性"}
          ]
        }
        """;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.anthropic.com/v1/messages"))
                .timeout(Duration.ofSeconds(30))
                .header("content-type", "application/json")
                .header("x-api-key", System.getenv("ANTHROPIC_API_KEY"))
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println(response.statusCode());
        System.out.println(response.body());
    }
}

```

**不足**：流式输出处理没有 WebClient 自然；拦截器、统一观测、重试机制没有 OkHttp 顺手。

**适合场景**：轻量级 Java SDK、非 Spring 项目、希望部署依赖极简。

---

## 四、方案二：Spring WebClient

`WebClient` 是 Spring WebFlux 提供的现代 HTTP 客户端，天然适合异步、流式、高并发场景。对于 LLM 场景来说，它最大的优势是：**和流式生成特别契合。**

### 普通调用示例

```Java
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

public class WebClientLlmDemo {

    private final WebClient webClient = WebClient.builder()
            .baseUrl("https://api.anthropic.com")
            .defaultHeader("x-api-key", System.getenv("ANTHROPIC_API_KEY"))
            .defaultHeader("anthropic-version", "2023-06-01")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();

    public Mono<String> callModel(String body) {
        return webClient.post()
                .uri("/v1/messages")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class);
    }
}

```

### 流式输出示例

```Java
import reactor.core.publisher.Flux;

public Flux<String> streamModel(String body) {
    return webClient.post()
            .uri("/v1/messages")
            .bodyValue(body)
            .retrieve()
            .bodyToFlux(String.class);
}

```

**优点**：非常适合流式响应；和 Spring 生态整合度高；天然支持异步和高并发。

**挑战**：团队需要理解 Reactor、Mono、Flux；同步阻塞风格项目强行引入响应式会增加复杂度。

<callout emoji="🎁">
如果团队对 Reactor 完全不熟，而项目是典型同步 MVC 风格，WebClient 虽然强，但不一定是最省成本的起步方案。
</callout>

**适合场景**：Spring Boot 新项目、流式输出/SSE/Token 级别 streaming、准备长期建设 AI 能力中台。

---

## 五、方案三：OkHttp

OkHttp 是 Java/Kotlin 生态里非常成熟的 HTTP 客户端库，在 Android、后端服务、SDK 封装里都非常常见。更像一个：**独立、成熟、强控制力、工程体验很好的通用客户端。**

### 典型调用示例

```Java
import okhttp3.*;
import java.io.IOException;

public class OkHttpLlmDemo {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public static void main(String[] args) throws IOException {
        OkHttpClient client = new OkHttpClient.Builder().build();

        String body = """
        {
          "model": "claude-sonnet-4-6",
          "max_tokens": 512,
          "messages": [
            {"role": "user", "content": "请解释 WebClient 和 OkHttp 的区别"}
          ]
        }
        """;

        Request request = new Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("content-type", "application/json")
                .addHeader("x-api-key", System.getenv("ANTHROPIC_API_KEY"))
                .addHeader("anthropic-version", "2023-06-01")
                .post(RequestBody.create(body, JSON))
                .build();

        try (Response response = client.newCall(request).execute()) {
            System.out.println(response.code());
            System.out.println(response.body().string());
        }
    }
}

```

### 拦截器——统一治理的利器

```Java
OkHttpClient client = new OkHttpClient.Builder()
        .addInterceptor(chain -> {
            Request original = chain.request();
            Request request = original.newBuilder()
                    .header("x-api-key", System.getenv("ANTHROPIC_API_KEY"))
                    .header("anthropic-version", "2023-06-01")
                    .build();
            return chain.proceed(request);
        })
        .build();

```

真实企业项目里，你经常需要统一处理 API Key 注入、TraceId 注入、请求日志、响应耗时统计、错误码转换，OkHttp 的拦截器在这类场景里非常好用。

**适合场景**：可复用的 LLM SDK、需要拦截器统一治理、不被 Spring 绑定、传统 Java 服务想要比 RestTemplate 更现代的方案。

---

## 六、方案四：RestTemplate

`RestTemplate` 是 Spring 早期非常经典的同步 HTTP 客户端，很多老项目都在用。

```Java
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

public class RestTemplateLlmDemo {
    public static void main(String[] args) {
        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", System.getenv("ANTHROPIC_API_KEY"));
        headers.set("anthropic-version", "2023-06-01");

        String body = """
        {
          "model": "claude-sonnet-4-6",
          "max_tokens": 256,
          "messages": [
            {"role": "user", "content": "什么是 Agent 的反应性？"}
          ]
        }
        """;

        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(
                "https://api.anthropic.com/v1/messages",
                HttpMethod.POST,
                entity,
                String.class
        );

        System.out.println(response.getStatusCode());
        System.out.println(response.getBody());
    }
}

```

在 LLM 场景下，`RestTemplate` 的最大问题不是"不能用"，而是**它不适合未来**：官方已不再建议作为新项目首选；流式输出支持不优雅；异步与响应式能力弱。

**建议**：老项目维护可继续用；新项目建设不建议作为主方案；向 AI 升级时优先考虑逐步迁移到 WebClient 或 OkHttp。

---

## 七、从 LLM 工程视角：四者真正差在哪

### 流式输出（差距最大）

- `WebClient`：最自然，`bodyToFlux` 直接消费
- `OkHttp`：可做，需自己管理读取逻辑
- `HttpClient`：能做，工程体验一般
- `RestTemplate`：最不推荐

### 工程治理能力（统一鉴权、日志、重试）

- `OkHttp` 拦截器体验很好
- `WebClient` 过滤器和响应式链路也很强
- `HttpClient` 需要自己补很多工程层能力
- `RestTemplate` 偏传统，不够现代

### 实战选型决策树

- **新 Spring Boot 项目 + 流式输出** → **WebClient**
- **通用 LLM SDK / 跨项目复用** → **OkHttp**
- **轻量集成 / 避免额外依赖** → **JDK HttpClient**
- **老 Spring MVC 项目快速验证** → **RestTemplate（过渡方案）**

---

## 八、建议的分层设计：把模型调用抽象成基础设施

不要把 HTTP 调用散落在业务代码里。更合理的方式是做三层：

```Java
// 第一层：统一接口
public interface LlmClient {
    String chat(String prompt);
}

```

```Java
// 第二层：具体实现（可按需替换）
public class AnthropicOkHttpClient implements LlmClient {
    private final OkHttpClient httpClient;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public AnthropicOkHttpClient() {
        this.httpClient = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    Request req = chain.request().newBuilder()
                            .header("x-api-key", System.getenv("ANTHROPIC_API_KEY"))
                            .header("anthropic-version", "2023-06-01")
                            .build();
                    return chain.proceed(req);
                })
                .build();
    }

    @Override
    public String chat(String prompt) {
        String body = "{\"model\":\"claude-sonnet-4-6\",\"max_tokens\":1024," +
                "\"messages\":[{\"role\":\"user\",\"content\":\"" + prompt + "\"}]}";
        Request request = new Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .post(RequestBody.create(body, JSON))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            return response.body().string();
        } catch (Exception e) {
            throw new RuntimeException("LLM call failed", e);
        }
    }
}

```

```Java
// 第三层：业务层，只依赖接口，不关心底层实现
public class LessonSummaryService {
    private final LlmClient llmClient;

    public LessonSummaryService(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public String summarize(String content) {
        return llmClient.chat("请总结以下内容：" + content);
    }
}

```

<callout emoji="✅">
这样做的好处：换模型厂商时不影响业务层；换 HTTP 客户端时不影响业务层；更容易统一加重试、超时、日志、观测；更容易做 mock / 集成测试 / 限流治理。
</callout>

---

## 九、本节小结

这一节课，我们围绕 Java 生态里四种主流 HTTP 调用方式，系统讨论了它们在 LLM 场景中的适用性。核心结论如下：

- `HttpClient`：JDK 原生、轻量、适合基础调用和 SDK 封装
- `WebClient`：最适合现代 Spring + 流式 LLM 场景
- `OkHttp`：通用性强、工程体验好、适合封装可复用 SDK
- `RestTemplate`：老项目可用，但不建议作为未来主方案

最重要的不是"谁能发请求"，而是**谁更适合承载你未来的 LLM 能力演进**。如果你的目标是做真正可扩展的 AI 系统，那么优先考虑的应该是：明天能不能支持流式输出、后天能不能支持多模型、再往后能不能支持统一观测、限流、成本治理和 Agent 编排。

---

## 十、引用文档与延伸阅读

- Java 11+ `HttpClient` 官方文档：https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html
- Spring `WebClient` 官方文档：https://docs.spring.io/spring-framework/reference/web/webflux-webclient.html
- OkHttp 官方文档：https://square.github.io/okhttp/
- Spring `RestTemplate` 官方说明：https://docs.spring.io/spring-framework/reference/integration/rest-clients.html
- Anthropic Messages API：https://docs.anthropic.com/en/api/messages
- Anthropic Streaming API：https://docs.anthropic.com/en/api/messages-streaming

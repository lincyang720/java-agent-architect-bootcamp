# 第三章第2节：ChatClient 详解：流畅 API 设计、同步与异步调用

大家好，我是秋天。

这一节我们正式开干 Spring AI 的核心对象——`ChatClient`。很多人刚从 LangChain4j 转过来，会先找 `AiServices`，找了一圈发现 Spring AI 里根本没有这个东西，于是慌了：那我怎么跟大模型聊天？别慌，Spring AI 的设计哲学跟 LangChain4j 不一样，它走的是"流式构建器（Fluent Builder）"路线，`ChatClient` 就是这条路线上的统一入口。

先说清楚这节课讲什么、学完你能掌握什么：

- 讲什么：`ChatClient` 的定位与创建方式、流畅 API 的链式调用结构、同步 `call()` 与异步/流式 `stream()` 的区别与选型、如何注入 `ChatModel`、以及如何用 `ChatClient.Builder` 配置默认值。
- 学完掌握：你能独立搭一个最小可运行的 Spring AI 工程，用 `ChatClient` 完成一次同步问答和一次流式问答；你能说清楚"什么时候该用同步、什么时候该用流式"；你能在面试里把"Spring AI 和 LangChain4j 在调用层设计上的差异"讲明白。

<callout emoji="💡"><p>这一能力在招聘 JD 里的对应要求：几乎所有"Java Agent 工程师"岗位都写"熟悉 Spring AI / Spring Boot 3.x"。会 `ChatClient` 是最低门槛，但能讲清楚同步/异步选型、能写出生产级配置（超时、重试、降级）的候选人，薪资档位直接上一档。</p></callout>

---

## 一、ChatClient 到底是什么：定位与心智模型

在 Spring AI 里，`ChatClient` 是一个"门面（Facade）"。它不直接实现模型调用，而是把底层的 `ChatModel`（OpenAI、DashScope、Ollama、智谱等具体实现）包装起来，给你一套统一的、链式的方法调用。

你回忆一下第一章讲的：底层模型差异巨大（OpenAI 的 `/chat/completions`、DashScope 的 `Generation`、Ollama 的本地 HTTP），如果每个模型都写一套代码，维护成本爆炸。Spring AI 的解法是两层：

```
┌──────────────────────────────────────────────┐
│                 你的业务代码                   │
│         chatClient.prompt()...call()          │
└───────────────────┬──────────────────────────┘
                    │ 统一门面
┌───────────────────▼──────────────────────────┐
│               ChatClient (门面)               │
│   组装 Prompt / 系统消息 / 参数 / Advisors     │
└───────────────────┬──────────────────────────┘
                    │ 适配
┌───────────────────▼──────────────────────────┐
│   ChatModel 接口 (OpenAiChatModel /           │
│   DashScopeChatModel / OllamaChatModel ...)   │
└───────────────────┬──────────────────────────┘
                    │ HTTP/gRPC
┌───────────────────▼──────────────────────────┐
│        具体大模型服务 (DashScope/OpenAI/...)   │
└──────────────────────────────────────────────┘
```

关键点：`ChatClient` 是"无状态"的（大部分情况下），真正的可配置项（模型、温度、超时）都在 `ChatModel` 里。`ChatClient` 负责把一次"请求"拼装好，交给 `ChatModel` 执行，再把"响应"解析成你想要的类型（字符串、`ChatResponse`、或者一个 POJO）。

很多同学会问：那我用 `ChatModel.call(prompt)` 不行吗？行，但 `ChatClient` 的流畅 API 让你少写一堆样板代码，尤其是后面要做的结构化输出、工具调用、Advisor 拦截，全是在 `ChatClient` 这一层串起来的。所以——直接用 `ChatClient`，别回头去碰裸 `ChatModel`。

---

## 二、先把工程跑起来：最小依赖与配置

我们先把工程的"地基"打好。Spring AI 1.0.x 已经 GA，依赖坐标稳定，注意别再引 `1.0.0-M6` 这种里程碑版了（那个版本 `@Tool` 还是实验性的，API 跟 GA 有出入）。

`pom.xml`（模块 `ch03-spring-ai`，Java 17 / Spring Boot 3.3+）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.5</version>
        <relativePath/>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>ch03-spring-ai</artifactId>
    <version>1.0.0</version>

    <properties>
        <java.version>17</java.version>
        <spring-ai.version>1.0.0</spring-ai.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <!-- 这里演示用 OpenAI 兼容接口；接通义千问可换成 dashscope starter -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-openai</artifactId>
        </dependency>
    </dependencies>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

配置文件 `application.yml`，**密钥一律走环境变量，绝不硬编码**，这是生产红线：

```yaml
server:
  port: 8080

spring:
  application:
    name: ch03-spring-ai
  ai:
    openai:
      # 通义千问等兼容 OpenAI 协议的模型，可改 base-url
      base-url: ${OPENAI_BASE_URL:https://api.openai.com}
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: ${OPENAI_MODEL:gpt-4o-mini}
          temperature: 0.7
```

<callout emoji="⚠️"><p>注意：`spring-ai-bom` 的 `import` 作用域必须放在 `dependencyManagement` 里。漏了这一步，Maven 解析不到 `spring-ai-starter-model-openai` 的版本，会报 "version is missing" 的经典错误。这是新手踩坑第一名。</p></callout>

---

## 三、ChatClient 的两种创建方式

Spring AI 给你两种拿到 `ChatClient` 的方式，记住一句话：**优先用自动注入的 `ChatClient.Builder`，别自己 `new`**。

### 方式一：注入 `ChatClient.Builder`（推荐）

Spring Boot 的自动配置会给你一个配好的 `ChatClient.Builder` Bean，你直接注入，调 `.build()` 即可。

```java
package com.example.ch03;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        // 在这里设置"默认值"：系统提示词、默认温度等
        return builder
                .defaultSystem("你是一名资深 Java 后端工程师，回答要简洁、可落地。")
                .defaultOptions(org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .withTemperature(0.3)
                        .build())
                .build();
    }
}
```

### 方式二：直接用 `ChatClient.create(ChatModel)`（快速原型）

```java
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

// 仅用于 Demo / 测试，生产里还是交给 Spring 容器管理
ChatClient quick = ChatClient.create(chatModel);
```

两者的区别：方式一通过 `defaultSystem` / `defaultOptions` 把"全局默认"固化进 Builder，后续每次 `prompt()` 都自动带上；方式二每次都要手写。生产工程里，系统提示词、超时、默认模型这些"全局配置"就该放在 Builder 层，业务代码只关心"这一次用户问了什么"。

<callout emoji="✅"><p>工程经验：把系统提示词、默认温度、超时时间放在 `ChatClient.Builder` 的 default 配置里，业务方法里只写 `user("...")`。这样换模型、调语气时改一处即可，符合"配置与代码分离"原则，也是后面接 Prompt 管理平台（第四章 lesson07）的基础。</p></callout>

---

## 四、流畅 API 的链式结构：一张图看懂

`ChatClient` 的调用是一条链，每个环节返回的对象类型都不一样，这是很多人读源码会迷路的地方。我把这条链画出来：

```
chatClient                              -> ChatClient
    .prompt()                           -> ChatClient.PromptSpec
    .system("...")                      -> PromptSpec  (可选，覆盖 default)
    .user("...")                        -> PromptSpec
    .params(Map)                        -> PromptSpec  (给模板传参，见 lesson03)
    .advisors(a -> ...)                 -> PromptSpec  (RAG/记忆拦截器，见 ch07)
    .tools(new XxxTools())             -> PromptSpec  (工具调用，见 lesson05)
    .call()                             -> ChatClient.CallResponseSpec   【同步】
        .content()                      -> String
        .chatResponse()                 -> ChatResponse
    .stream()                           -> ChatClient.StreamResponseSpec  【异步/流式】
        .content()                      -> Flux<String>
        .chatResponse()                 -> Flux<ChatResponse>
```

你看，`call()` 和 `stream()` 是两个分叉口：

- `call()` 返回 `CallResponseSpec`，最终 `content()` 给你一个**完整的 `String`**（或者 `chatResponse()` 给你带元数据的 `ChatResponse`）。这是**阻塞**的——方法不返回，线程就一直等模型吐完。
- `stream()` 返回 `StreamResponseSpec`，`content()` 给你一个 `Flux<String>`——**响应式流**，模型每吐一个 token 你就收到一个，适合打字机效果。

### 同步调用最小示例

```java
package com.example.ch03;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatController {

    private final ChatClient chatClient;

    // 构造注入上面配置的 ChatClient
    public ChatController(ChatClient chatClient) {
        this.chatClient = chatClient;
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

跑起来：`curl "http://localhost:8080/ask?question=用一句话解释什么是Spring%20Bean"`，你会拿到一句完整回答。这就是最朴素的同步调用。

---

## 五、异步与流式：为什么 production 里你多半要它

同步调用的痛点很直接：一个请求卡 3 秒，Servlet 线程就被占 3 秒。并发一上来，线程池打满，整个服务雪崩。大模型推理慢是客观事实（首 token 延迟几百毫秒到几秒），所以**面向用户的对话接口，几乎都应该用流式**。

Spring AI 的流式基于 Project Reactor 的 `Flux`，配合 WebFlux 或 Spring MVC 的 `text/event-stream` 都能用。

### 流式调用示例（SSE 打字机效果）

```java
package com.example.ch03;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class StreamChatController {

    private final ChatClient chatClient;

    public StreamChatController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @GetMapping(value = "/ask/stream", produces = "text/event-stream")
    public Flux<String> askStream(@RequestParam String question) {
        return chatClient.prompt()
                .user(question)
                .stream()
                .content();   // Flux<String>，每个元素是一小段文本
    }
}
```

前端用 `EventSource` 或 `fetch` + `ReadableStream` 逐段渲染，用户立刻看到"字一个个蹦出来"，体感延迟从"3 秒黑屏"降到"200ms 出第一个字"。这就是流式最大的价值——**不是更快，而是更早给人反馈**。

### 想拿到完整的 `ChatResponse` 而不是纯文本？

有时候你不但要文本，还要元数据（用了哪个模型、token 消耗、停止原因）。同步和异步都有对应方法：

```java
// 同步：要元数据
org.springframework.ai.chat.model.ChatResponse resp =
        chatClient.prompt().user("讲个冷笑话").call().chatResponse();
System.out.println("模型=" + resp.getResult().getMetadata().getModel());
System.out.println("耗时token=" + resp.getMetadata().getUsage());

// 异步：要元数据流
Flux<org.springframework.ai.chat.model.ChatResponse> respFlux =
        chatClient.prompt().user("讲个冷笑话").stream().chatResponse();
```

<callout emoji="💡"><p>选型口诀：<b>内部批处理、测试脚本、一次性问答 → 用 `call()`</b>；<b>任何面向用户、需要实时反馈、或者结果很长（报告生成/代码生成）的接口 → 用 `stream()`</b>。在企业落地里，"数字员工生成周报"这种动辄上千字的场景，流式是刚需，否则用户会以为系统挂了。</p></callout>

---

## 六、结合企业场景：客服问答与对账助手的调用层设计

光讲 API 太虚，我们落到一个真实场景：**某电商的"智能对账助手"**。运营同学问"上个月华东区退款总额多少、异常订单有哪些"，背后要调数据库、调规则引擎。这里 `ChatClient` 调用层要回答三个问题：

1. 同步还是异步？——运营在后台点一下查，等 2~3 秒能接受，但为了体验，我们用流式把"正在分析…"先推出来，所以**外层流式、内层工具调用同步**（工具调用见 lesson05）。
2. 系统提示词写什么？——固定写"你是财务对账专家，只基于工具返回的数据回答，禁止编造金额"。
3. 失败怎么兜底？——模型超时返回降级文案，而不是 500。

```java
package com.example.ch03.reconcile;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/reconcile")
public class ReconcileController {

    private final ChatClient chatClient;

    public ReconcileController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    // 流式返回，用户先看到"正在查询数据库..."的假提示由前端处理，
    // 这里直接流式吐模型结果
    @GetMapping(value = "/ask", produces = "text/event-stream")
    public Flux<String> ask(@RequestParam String question) {
        try {
            return chatClient.prompt()
                    .system("你是财务对账专家，只基于工具返回的数据回答，禁止编造金额。")
                    .user(question)
                    .tools(new ReconcileTools())   // lesson05 详解，这里先占位
                    .stream()
                    .content();
        } catch (Exception e) {
            // 降级：模型不可用时给兜底，而不是 500
            return Flux.just("【系统繁忙】对账服务暂不可用，请稍后重试或联系财务中台。");
        }
    }
}
```

```java
// 工具类的壳，具体实现 lesson05 补
package com.example.ch03.reconcile;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class ReconcileTools {

    @Tool(description = "按区域和月份查询退款总额")
    public String queryRefund(@ToolParam(description = "区域如 华东") String region,
                              @ToolParam(description = "月份如 2026-10") String month) {
        // 真实场景查数据库 / 数仓
        return region + " " + month + " 退款总额：1,234,567.89 元";
    }
}
```

<callout emoji="⚠️"><p>这里埋个伏笔：`.tools()` 必须在 `ChatClient` 链路里，而且工具方法要标 `@Tool`。这是 lesson05 的主角。你现在只要知道——调用层的编排位置在 `prompt()` 之后、`call()/stream()` 之前。</p></callout>

---

## 七、常见坑与调试技巧

### 坑 1：注入 `ChatClient.Builder` 失败

报错 `No qualifying bean of type 'ChatClient.Builder'`。原因：没引任何 `spring-ai-starter-model-*` 依赖。自动配置只有在检测到 `ChatModel` Bean 时才会生成 `ChatClient.Builder`。**解决**：确认 pom 里有 `spring-ai-starter-model-openai`（或 dashscope 等）。

### 坑 2：流式接口返回整段而不是逐字

检查两点：① `produces = "text/event-stream"` 是否设置；② 前端是否真按流解析。如果前端用普通 `fetch` 一次性读 `response.text()`，那当然看不到逐字效果。

### 坑 3：想给每次请求动态改系统提示词

`defaultSystem` 是默认值，但每次 `prompt().system("...")` 会**覆盖**它，而不是拼接。如果你既要全局角色又要本次上下文，用 `prompt()` 里再补 user 消息，或者把动态部分放 user 消息里。

### 调试：打开请求/响应日志

`application.yml` 里加：

```yaml
logging:
  level:
    org.springframework.ai.chat.client: DEBUG
    org.springframework.ai.openai: DEBUG
```

这样每次调用打印出发给模型的完整 payload，排查"为什么模型答非所问"时极其有用。

<callout emoji="💡"><p>面试加分项：能被问到"Spring AI 的 `ChatClient` 和 LangChain4j 的 `AiServices` 设计哲学差异"。答案要点——LangChain4j 用接口 + 注解把 LLM 调用"伪装"成一个 Java 接口（声明式），Spring AI 用流式 Builder 把调用"显式"拼出来（命令式）。前者写业务爽，后者调试、组合 Advisor/工具时更可控。没有谁更好，看团队习惯。</p></callout>

---

## 八、Advisors：在调用链上挂"横切能力"

`ChatClient` 里还有一个极易被忽视、但生产里极重要的概念——**Advisor（顾问）**。它类似 Spring 的 AOP，能在"请求发出前"和"响应回来后"插入逻辑，而且可以链式叠加。常见用途：RAG 检索（`QuestionAnswerAdvisor`）、对话记忆（`MessageChatMemoryAdvisor`）、日志、敏感词过滤、限流。

```java
package com.example.ch03;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemory;

public class AdvisedClient {

    public ChatClient build(ChatClient.Builder builder) {
        ChatMemory memory = new InMemoryChatMemory();
        return builder
                .defaultSystem("你是客服助手。")
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(memory).build()  // 自动管理多轮上下文
                )
                .build();
    }

    public String chat(ChatClient client, String cid, String q) {
        return client.prompt()
                .advisors(a -> a.param("conversationId", cid))
                .user(q)
                .call()
                .content();
    }
}
```

Advisor 的执行顺序：`prompt()` → Advisor1(前) → Advisor2(前) → 模型 → Advisor2(后) → Advisor1(后) → `content()`。理解这个顺序，你才能预判"记忆是何时被加进 prompt 的""RAG 检索结果插在哪"。

<callout emoji="💡"><p>面试常问："多轮对话怎么实现？"答案不是每次把历史拼进 user 消息（那样会越攒越长、还会泄露），而是用 `MessageChatMemoryAdvisor` + 记忆存储（内存/Redis/pgvector），由 Advisor 自动按 `conversationId` 注入最近 N 轮。第六章会系统讲记忆机制。</p></callout>

---

## 九、生产三件套：超时、重试与降级

Demo 不需要考虑这些，生产必须。Spring AI 本身不内置重试，但你可以组合 Spring Retry / Resilience4j，或直接在调用处包一层。

```java
package com.example.ch03;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class ResilientChat {

    private final ChatClient chatClient;

    public ResilientChat(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public String askWithFallback(String q) {
        try {
            return chatClient.prompt().user(q).call().content();
        } catch (Exception e) {
            // 降级：返回兜底话术，而不是把 500 抛给用户
            return "【系统繁忙】服务暂时不可用，请稍后再试或联系人工客服。";
        }
    }
}
```

更完整的生产做法是用 Resilience4j 包一层：

```xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
</dependency>
```

```java
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;

@CircuitBreaker(name = "chatClient", fallbackMethod = "fallback")
@Retry(name = "chatClient")
public String ask(String q) {
    return chatClient.prompt().user(q).call().content();
}

public String fallback(String q, Exception e) {
    return "【降级】模型服务异常，已转人工。";
}
```

这样模型抖动、超时、限流都不会击穿你的主服务——第十章"异常处理与降级"的提前剧透。

---

## 十、企业场景：代码生成助手（流式 + 记忆）

再落一个场景："内部代码生成助手"，开发者问"给我写个防重提交的注解"，助手流式吐代码，并记得上下文（"再加个超时参数"）。这把前面能力串起来：

```java
package com.example.ch03.codegen;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/codegen")
public class CodeGenController {

    private final ChatClient chatClient;

    public CodeGenController(ChatClient.Builder builder) {
        this.chatClient = builder
                .defaultSystem("你是资深 Java 架构师，只输出可编译代码，附简短说明。")
                .defaultAdvisors(
                        org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor
                                .builder(new org.springframework.ai.chat.memory.InMemoryChatMemory())
                                .build())
                .build();
    }

    @GetMapping(value = "/ask", produces = "text/event-stream")
    public Flux<String> ask(@RequestParam String cid, @RequestParam String q) {
        return chatClient.prompt()
                .advisors(a -> a.param("conversationId", cid))
                .user(q)
                .stream()
                .content();
    }
}
```

<callout emoji="⚠️"><p>设计原则：不要为了用工具而用工具。代码生成助手主要靠模型知识 + 记忆，不需要查外部系统，就别硬加工具，减少出错面。工具只在"模型自身无法获取真实数据"时才上（lesson05）。</p></callout>

---

## 十一、性能与成本：你该知道的几个事实

讲完 API，必须讲钱和性能，因为企业最关心这个：

- **首 token 延迟（TTFT）**：流式体验关键。通义千问 qwen-plus 通常 300~800ms。一个"问完等 3 秒"的接口必须上流式。
- **吞吐**：Java 侧瓶颈通常在 Servlet 线程池，流式用 WebFlux 可扛更高并发。
- **Token 成本**：系统提示词每次都随请求发送，越长越贵；要精简。
- **缓存**：配合 Higress 可做提示词缓存（lesson07），相同前缀不重复计费。

---

## 十二、常见面试题自测

1. **ChatClient 和 ChatModel 关系？** —— ChatClient 是门面，委托 ChatModel 执行；前者拼装、后者真正调模型。
2. **call() 与 stream() 返回类型？** —— call() 返回 `CallResponseSpec`（`.content()`→String，`.chatResponse()`→ChatResponse）；stream() 返回 `StreamResponseSpec`（`.content()`→`Flux<String>`）。
3. **没注入 ChatClient.Builder？** —— 漏了 `spring-ai-starter-model-*` 依赖，自动配置不生效。
4. **多轮对话不丢上下文？** —— `MessageChatMemoryAdvisor` + 记忆存储，按 `conversationId` 注入。

---

## 十三、小结

这一节我们打通了 Spring AI 的"入口"：

1. `ChatClient` 是门面，底层是 `ChatModel`，它负责把请求拼装 + 响应解析一气呵成。
2. 工程地基：`spring-ai-bom` + `spring-ai-starter-model-*` 起步依赖，密钥走环境变量。
3. 两种创建方式：注入 `ChatClient.Builder`（默认配置固化在 Builder）+ 快速 `ChatClient.create()`。
4. 流畅链：`prompt() → system/user → call()/stream() → content()/chatResponse()`。
5. 同步 `call()` 返回完整结果（阻塞），异步 `stream()` 返回 `Flux<String>`（流式），面向用户场景优先用流式。
6. 企业落地（对账助手）里，调用层要同时考虑"流式体验 + 工具注入 + 失败降级"。

下一节（lesson03）我们专门拆"提示词工程"——把系统提示词、模板、多角色消息这套东西系统化，你会学到怎么用 `PromptTemplate` 把提示词变成可维护、可版本化的代码资产。

---

## 延伸阅读 / 引用文档

1. Spring AI 官方文档 · ChatClient API：https://docs.spring.io/spring-ai/reference/api/chatclient.html
2. Spring AI 1.0 GA 发布说明（含 ChatClient 设计）：https://spring.io/blog/2025/05/20/your-first-spring-ai-1
3. Spring AI GitHub 仓库（含 chat-client 示例）：https://github.com/spring-projects/spring-ai
4. Spring AI Alibaba 快速开始（DashScope 接入）：https://java2ai.com/docs/1.0.0.2/tutorials/quick-start/
5. OpenAI 流式（SSE）协议说明：https://platform.openai.com/docs/guides/streaming
6. Project Reactor 官方文档（Flux 响应式流）：https://projectreactor.io/docs/core/release/reference/

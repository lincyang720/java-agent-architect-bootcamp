# 第五章第1节：Google ADK 概述——定位、架构与核心概念

大家好，我是秋天。

这节课我们正式进入第五章，聊聊 Google 家的 Agent 开发框架——**Google ADK for Java**。很多同学一听到"Google 又出框架了"，第一反应是：不是已经有 LangChain4j、Spring AI 了吗，为什么还要学 ADK？是不是又是 KPI 产物？

我先给一个判断框架：**框架不是越多越好，而是要看它站在哪一层、解决哪一类问题**。LangChain4j 和 Spring AI 更偏向"在 Java 应用里调用 LLM、串工具、做 RAG"；而 Google ADK 的定位是"**端到端的 Agent 运行时 + 编排引擎 + 云服务打通**"，它把 Agent、工具、会话、编排、部署、评测做成了一整套，并且天然和 Gemini、Vertex AI 深度绑定。

学完这节课，你能掌握三件事：

1. ADK 在整个 Java Agent 生态里的定位，以及它和 LangChain4j / Spring AI 的本质差异；
2. ADK 的分层架构——Agent、Tool、Session、Runner、Flow 各自负责什么；
3. 用 Maven 把 ADK 跑起来，写出第一个能对话的 `LlmAgent`，理解 `Event` / `Session` / `InvocationContext` 这些核心概念。

---

## 一、ADK 是什么：定位与一句话定义

先把话说清楚。Google ADK（Agent Development Kit）是 Google 在 2025 年开源的 Agent 开发框架，官方定位是"**开源、代码优先（code-first）、多语言**"的 Agent 构建工具包。它和 Google 内部给 Gemini 用的 Agent 技术栈同源，所以你能感觉到它从设计上一开始就为"真要上线、真要编排、真要上云"服务，而不是一个 demo 玩具。

ADK 同时提供 Python 和 Java 两个官方 SDK。我们这门课是 Java 版，所以全程用 `com.google.adk` 这个包。

<callout emoji="💡"><p>为什么 Java 工程师要关心 ADK？因为 ADK for Java 是目前少数"原生 Java、且和企业级云（Vertex AI）打通"的 Agent 框架。如果你的公司在用 GCP，或者你的业务需要 Gemini 的多模态和超大上下文，ADK 几乎是默认选项。</p></callout>

### 1.1 ADK 和 LangChain4j / Spring AI 的本质差异

很多同学会问，这三个到底选哪个？我给你一张对比表，记住一句话：**LangChain4j 是"胶水"，Spring AI 是"抽象层"，ADK 是"运行时 + 编排 + 云"。**

```text
┌────────────────────────────────────────────────────────────────────┐
│                     Java Agent 框架选型地图                          │
├───────────────┬───────────────┬───────────────┬────────────────────┤
│ 维度          │ LangChain4j   │ Spring AI     │ Google ADK for Java│
├───────────────┼───────────────┼───────────────┼────────────────────┤
│ 核心定位      │ LLM 胶水/链   │ 统一抽象层    │ Agent 运行时+编排  │
│ 编排能力      │ 弱（需自拼）  │ 中（Graph）   │ 强（内置 Sequential│
│               │               │               │ /Parallel/Loop）   │
│ 工具生态      │ 丰富          │ 丰富          │ 官方内置+Google系  │
│ 云绑定        │ 无            │ 弱（多厂商）  │ 强（Vertex AI）    │
│ 会话/状态     │ 需自建        │ 需自建        │ 内置 Session 服务  │
│ 适合场景      │ 快速接 LLM    │ Spring 体系   │ 复杂编排+GCP 上线  │
└───────────────┴───────────────┴───────────────┴────────────────────┘
```

记住这个判断：**如果你的 Agent 是"单 Agent + 几个工具"，LangChain4j / Spring AI 足够；如果是"多 Agent 编排 + 需要稳定会话 + 要上 GCP"，ADK 更顺手。**

<callout emoji="⚠️"><p>招聘映射：2026 年国内大厂 JD 里，"熟悉至少两种 Agent 框架，理解其编排模型差异"是常见加分项。能把 ADK 的 Sequential/Parallel/Loop 和 Spring AI Alibaba 的 Graph 讲清楚的同学，面试时很能打。</p></callout>

---

## 二、ADK 的分层架构：五层模型

ADK 的架构可以拆成五层，每一层职责单一，这也是它能撑住复杂编排的原因。

```text
┌──────────────────────────────────────────────────────────────────┐
│  Layer 5  部署/云层   Vertex AI Agent Engine / Cloud Run / K8s    │
├──────────────────────────────────────────────────────────────────┤
│  Layer 4  运行层(Runner)  会话调度、事件流、生命周期管理           │
├──────────────────────────────────────────────────────────────────┤
│  Layer 3  编排层(Flow)    Sequential / Parallel / Loop / 子Agent   │
├──────────────────────────────────────────────────────────────────┤
│  Layer 2  Agent 层       LlmAgent / BaseAgent（含指令/模型/工具）  │
├──────────────────────────────────────────────────────────────────┤
│  Layer 1  基座层         Model(Gemini) / Tools / Session / Events  │
└──────────────────────────────────────────────────────────────────┘
```

### 2.1 核心概念逐一拆解

下面这些概念是后面四节课的地基，你现在不熟悉没关系，但要知道每个词指代什么：

- **Agent**：能感知、决策、行动的执行单元。ADK 里分 `BaseAgent`（抽象基类）和 `LlmAgent`（由 LLM 驱动的智能体）。
- **Tool**：Agent 能调用的能力单元，比如搜索、写代码、查数据库。ADK 用 `@AdkTool` 注解把一个普通 Java 方法变成工具。
- **Session**：一次用户会话的完整状态容器，存对话历史、用户状态、临时变量。ADK 提供 `InMemorySessionService` 和基于数据库的会话服务。
- **Event**：Agent 运行过程中产生的事件流（文本、工具调用、状态变更）。ADK 用响应式流（RxJava）把事件吐出来，你订阅就能拿到。
- **InvocationContext**：一次"调用"的上下文，携带当前 Session、用户消息、运行配置。
- **Runner**：把 Agent、Session 串起来的"发动机"，负责驱动一次完整的执行。
- **Flow**：编排执行流程的引擎，决定 Agent 怎么被调用、子 Agent 怎么切换。

<callout emoji="✅"><p>记忆点：Agent 是"谁"，Tool 是"能做什么"，Session 是"记得什么"，Event 是"发生了什么"，Runner 是"怎么跑"，Flow 是"怎么排"。这六个词背下来，ADK 就入门了。</p></callout>

---

## 三、环境准备：用 Maven 把 ADK 跑起来

ADK for Java 是标准 Maven 依赖。我们按课程工程规范，用 Java 17 + Spring Boot 3.3 的基线（即使不用 Spring，Java 17 是硬性要求）。

### 3.1 Maven 坐标

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.camp</groupId>
    <artifactId>ch05-google-adk</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <properties>
        <maven.compiler.release>17</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <adk.version>1.0.0</adk.version>
        <genai.version>1.0.0</genai.version>
    </properties>

    <dependencies>
        <!-- Google ADK for Java 核心 -->
        <dependency>
            <groupId>com.google.adk</groupId>
            <artifactId>google-adk</artifactId>
            <version>${adk.version}</version>
        </dependency>

        <!-- Google GenAI SDK（Gemini 模型客户端） -->
        <dependency>
            <groupId>com.google.genai</groupId>
            <artifactId>google-genai</artifactId>
            <version>${genai.version}</version>
        </dependency>

        <!-- 响应式流，ADK 事件流基于 RxJava3 -->
        <dependency>
            <groupId>io.reactivex.rxjava3</groupId>
            <artifactId>rxjava</artifactId>
            <version>3.1.9</version>
        </dependency>

        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-simple</artifactId>
            <version>2.0.16</version>
        </dependency>
    </dependencies>
</project>
```

<callout emoji="⚠️"><p>版本号要随官方发布节奏调整。ADK for Java 仍在快速迭代，写代码前先去 Maven Central 查 `com.google.adk:google-adk` 的最新版本。本课程的示例以"builder 流式 API"为准，这是 ADK 的稳定风格，即使版本升级也基本兼容。</p></callout>

### 3.2 配置 API Key（绝不硬编码）

ADK 默认走 Google AI Studio（Gemini Developer API）或 Vertex AI。本地开发用 Gemini Developer API 最简单，Key 从环境变量读：

```bash
# ~/.zshrc 或 CI 环境变量，绝不写进代码
export GOOGLE_API_KEY="你的-gemini-developer-api-key"
```

`application.yml`（如果你集成 Spring）也只放占位，真正的值走环境变量：

```yaml
google:
  adk:
    # 用 ${ENV} 注入，避免密钥入库
    api-key: ${GOOGLE_API_KEY}
    model: gemini-2.0-flash
    project-id: ${GCP_PROJECT_ID}
    location: us-central1
```

---

## 四、第一个 Agent：从"Hello ADK" 看核心概念

我们写一个最小可运行的 `LlmAgent`，跑通"用户说话 → Agent 回复"的完整链路。这段代码把前面讲的 Runner / Session / Event 一次性串起来。

```java
package com.camp.adk;

import com.google.adk.agents.LlmAgent;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;

import java.util.Optional;

/**
 * 第一个 ADK Agent：最小可运行示例。
 * 目的：理解 Agent / Runner / Session / Event 四件套。
 */
public class HelloAdk {

    public static void main(String[] args) throws Exception {
        // 1) 定义 Agent：名字 + 指令 + 模型
        LlmAgent agent = LlmAgent.builder()
                .name("hello_agent")
                .instruction("你是一个简洁友好的中文助手，回答不超过两句话。")
                .model("gemini-2.0-flash")
                .build();

        // 2) 会话服务：这里用内存版，生产用数据库版
        InMemorySessionService sessionService = new InMemorySessionService();

        // 3) Runner：把 Agent 和会话服务组合起来
        Runner runner = new Runner(agent, sessionService);

        // 4) 创建一次会话（appName / userId / 初始状态 / sessionId）
        Session session = sessionService
                .createSession("camp_app", "user_001", null, "sess_001")
                .blockingGet();

        // 5) 构造用户消息：Content = 一组 Part
        Content userMessage = Content.fromParts(Part.fromText("用一句话介绍 Google ADK。"));

        // 6) 运行：拿到响应式事件流，逐个打印
        Flowable<Event> events = runner.runAsync(
                session.id(),
                "user_001",
                userMessage);

        events.blockingForEach(event -> {
            Optional<Content> content = event.content();
            if (content.isPresent()) {
                content.get().parts().ifPresent(parts ->
                        parts.forEach(p -> p.text().ifPresent(System.out::println)));
            }
        });
    }
}
```

把这段代码拆开看，你能学到四个关键事实：

1. **Agent 是 builder 模式构造的**——`.name().instruction().model()`，这是 ADK 的统一风格。
2. **Session 必须先创建再使用**——ADK 把"状态"当作一等公民，没有 session 跑不起来。
3. **执行结果是事件流（Flowable<Event>）**——不是"调用一次返回一个字符串"，而是流式吐出文本、工具调用、状态变更。这个设计是为复杂编排和多轮交互埋的伏笔。
4. **Content / Part 是消息的统一结构**——Gemini 的多模态（文本、图片、函数调用）都塞进 Part 里。

<callout emoji="💡"><p>为什么 ADK 用流式事件而不是返回值？因为真正的 Agent 会"边想边说""边调工具边输出"，中途还会切换子 Agent。如果设计成"阻塞返回一个完整答案"，就支撑不了第四节要讲的循环 Agent 和工具中途插话。这是 ADK 和很多"一次性问答"框架在底层最本质的区别。</p></callout>

---

## 五、ADK 的执行模型：一次 Invocation 到底发生了什么

很多同学跑通 hello world 后有个误区：以为 `runner.runAsync()` 就是"把问题丢给模型拿答案"。实际上 ADK 在里面跑了一套**循环推理流程**。我给你画一张执行时序图：

```text
用户消息
   │
   ▼
┌─────────────┐
│ Runner      │  创建 InvocationContext（携带 Session + 消息 + 配置）
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ Flow        │  决定调用哪个 Agent（这里是 LlmAgent）
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ LlmAgent    │  1. 拼指令 + 历史 + 工具声明
│ (推理循环)  │  2. 调 Gemini
└──┬───┬───┬──┘
   │   │   │
   │   │   └──(b) 模型说"我要调工具" → 执行 Tool → 结果回填 → 再次推理
   │   │
   │   └──────(c) 模型说"我说完了" → 产出最终 Event
   │
   └──────────(a) 每生成一段文本就发一个 Event（流式）
       │
       ▼
   Event 流  →  你的订阅者逐条处理（打印/存库/转发前端）
```

关键点：**Agent 内部是一个"推理—调用工具—回填结果—再推理"的循环**，直到模型认为任务完成。这个循环就是后面第三节"LoopAgent"要系统讲的东西，而 `LlmAgent` 内部其实已经内置了一个基础循环（单轮内多工具调用）。

<callout emoji="✅"><p>面试高频：面试官常问"你的 Agent 怎么保证一次任务跑完而不是只答一半？"答案就在 ADK 的推理循环 + 工具回填机制里——模型通过 function calling 表达意图，框架执行后把结果塞回上下文，模型再决定下一步，直到 `Event` 标记为结束（final）。</p></callout>

---

## 六、ADK 的"代码优先"哲学与企业落地

ADK 反复强调 code-first（代码优先），这对企业落地非常重要。为什么？

1. **可版本化、可 review**：Agent 的指令、工具、编排都是 Java 代码，能进 Git、能走 PR、能跑 CI。比"在网页上配一个 Agent"可维护得多。
2. **可测试**：因为一切都是对象，你可以对 Agent 做单元测试、对 Tool 做集成测试。我们第十七章会专门讲评测闭环，这里先记住——ADK 的设计天然适配测试。
3. **可组合**：Agent 是对象，子 Agent、并行 Agent 都是把对象传进去，组合成本极低。

### 6.1 企业场景：智能客服的"分层 Agent"

举一个真实落地场景。某电商客服需要同时处理"查订单""退换货政策""投诉安抚"。用 ADK 你会这样分层：

```text
                    ┌─────────────────────┐
   用户消息 ───────▶ │   Root Agent        │  总控，判断意图
                    │  (LlmAgent)         │
                    └──────────┬──────────┘
              ┌────────────────┼────────────────┐
              ▼                ▼                ▼
     ┌────────────┐  ┌──────────────┐  ┌──────────────┐
     │ 订单 Agent │  │ 政策 Agent   │  │ 投诉 Agent   │
     │ (子Agent)  │  │ (子Agent)    │  │ (子Agent)    │
     └────────────┘  └──────────────┘  └──────────────┘
         │                 │                  │
     查单工具          知识库工具          安抚话术+转人工
```

这种"一个总控 + 多个专家子 Agent"的结构，在 ADK 里用 `subAgents` 就能声明，下一节课我们手把手写。

<callout emoji="💡"><p>为什么不直接用一个"万能 Agent"？因为指令越长、工具越多，模型越容易"选错工具"或"答非所问"。分层之后每个子 Agent 指令聚焦、工具精简，准确率和可维护性都更高。这在招聘 JD 里对应"多 Agent 协作 / Supervisor 模式"的要求。</p></callout>

---

## 七、ADK 生态：工具、模型与部署三件套

ADK 不是孤军奋战，它和 Google 生态三件套天然打通：

- **模型层**：默认 Gemini 系列（2.0 Flash / 2.5 Pro 等），支持超大上下文、原生多模态。
- **工具层**：官方内置 `GoogleSearchTool`、`CodeInterpreterTool`，也能用 `@AdkTool` 自定义（第四节详讲）。
- **部署层**：ADK Agent 可以一键部署到 **Vertex AI Agent Engine**，获得托管运行时、自动扩缩、可观测性。这是它相对 LangChain4j 最大的"云优势"——第五节专门展开。

```text
┌──────────────────────────────────────────────────────────┐
│  ADK 生态三件套                                            │
│                                                            │
│   [Gemini 模型]  ──▶  [ADK Agent/编排]  ──▶  [Vertex 部署] │
│        ▲                    ▲                    ▲          │
│        │                    │                    │          │
│   多模态/长上下文     内置工具/自定义工具     托管/弹性/可观测│
└──────────────────────────────────────────────────────────┘
```

<callout emoji="⚠️"><p>注意一个现实：ADK 强绑定 Google 生态。如果你的公司技术栈是 Azure / 阿里云 / 私有化部署，要评估"被锁定"的风险。框架选型没有银弹，ADK 适合 GCP 重度用户，但在混合云场景要谨慎。</p></callout>

---

## 八、本章后续路线图

这节课建立了全局认知，后面四节是逐步深入的：

- **lesson02**：把"创建 Agent"这件事拆透——基础 Agent、子 Agent、并行 Agent 三种形态怎么写、怎么配。
- **lesson03**：循环 Agent 与动态编排，讲清楚"多轮交互、条件分支、动态路由"怎么实现。
- **lesson04**：内置工具 Google Search / Code Interpreter 的原理与用法，以及怎么写你自己的工具。
- **lesson05**：ADK 与 Vertex AI 集成，企业级部署、扩缩、可观测、权限。

<callout emoji="✅"><p>学习建议：ADK 的 API 变化快，但"分层架构 + 流式事件 + 编排原语"这几个核心概念是稳定的。把这几节的概念吃透，即使 API 换了方法名，你也能快速上手。</p></callout>

---

## 九、ADK 与 Spring Boot 整合：Java 工程师的生产姿势

很多同学问：ADK 是独立框架，和我们天天用的 Spring Boot 怎么共处？答案是：**ADK 的 Agent / Runner 就是普通 Java 对象，完全可以交给 Spring 容器管理**。你不需要为了用 ADK 就抛弃 Spring 全套基础设施。

```java
package com.camp.adk.config;

import com.google.adk.agents.LlmAgent;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AdkConfig {

    @Value("${google.adk.model:gemini-2.0-flash}")
    private String model;

    @Bean
    public LlmAgent helloAgent() {
        return LlmAgent.builder()
                .name("spring_managed_agent")
                .instruction("你是集成在 Spring 应用里的助手，回答简洁。")
                .model(model)
                .build();
    }

    @Bean
    public Runner agentRunner(LlmAgent helloAgent) {
        // Runner 由 Spring 管理，可注入 Controller / Service
        return new Runner(helloAgent, new InMemorySessionService());
    }
}
```

```java
package com.camp.adk.controller;

import com.google.adk.runner.Runner;
import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/agent")
public class AgentController {

    private final Runner runner;
    public AgentController(Runner runner) { this.runner = runner; }

    @PostMapping("/chat")
    public List<String> chat(@RequestParam String sessionId,
                             @RequestParam String userId,
                             @RequestBody String message) {
        List<String> replies = new ArrayList<>();
        Content msg = Content.fromParts(Part.fromText(message));
        Flowable<Event> events = runner.runAsync(sessionId, userId, msg);
        events.blockingForEach(e -> e.textContent().ifPresent(replies::add));
        return replies;
    }
}
```

<callout emoji="💡"><p>为什么要交给 Spring？因为生产 Agent 不是"跑个 main 就完事"，它要接 Web 层、接数据库、接配置中心、接鉴权。把 Agent / Runner 当 Spring Bean，天然复用整套企业基础设施。这也是我们课程技术主线"Spring AI + ADK 协同"的落地姿势。</p></callout>

## 十、Session 与状态管理深入

上节课提到 Session 是"状态容器"。这里展开：Session 里到底装什么，以及多轮对话怎么靠它保持连续。

```java
package com.camp.adk;

import com.google.adk.sessions.Session;
import com.google.adk.sessions.InMemorySessionService;

import java.util.HashMap;
import java.util.Map;

public class SessionDeepDive {
    public static void main(String[] args) {
        InMemorySessionService svc = new InMemorySessionService();
        // Session 的 state 是一张 KV 表，跨轮共享
        Map<String, Object> state = new HashMap<>();
        state.put("user_name", "张三");
        state.put("vip_level", "3");
        Session s = svc.createSession("app", "u1", state, "s1").blockingGet();

        // 下一轮对话，Agent 能从 state 读到 user_name / vip_level
        System.out.println(s.state());   // {user_name=张三, vip_level=3}
    }
}
```

```text
Session 内部结构示意：
┌─────────────────────────────────────────┐
│ Session                                  │
│  id: s1                                  │
│  userId: u1                              │
│  state: {user_name, vip_level, order_id}│  <- 结构化记忆
│  events: [e1, e2, ...]                   │  <- 历史对话记忆
└─────────────────────────────────────────┘
```

<callout emoji="✅"><p>记忆点：Session 的 state 是"结构化记忆"，events 是"对话历史记忆"。前者给 Agent 稳定的用户上下文（我是谁、什么等级），后者重建对话连续性。两者配合，多轮对话才连贯。第六章会把这个思想系统化。</p></callout>

## 十一、ADK 事件流模型深入：背压与订阅

ADK 用 RxJava 的 `Flowable<Event>`，意味着你可以用响应式全套能力——背压、变换、过滤。生产里常做"边生成边推前端"。

```java
package com.camp.adk;

import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;

import java.util.concurrent.atomic.AtomicInteger;

public class StreamingDemo {
    public static void streamToFrontend(Flowable<Event> events) {
        AtomicInteger tokenCount = new AtomicInteger(0);
        events
            .filter(Event::partial)                 // 只取流式片段
            .map(e -> e.textContent().orElse(""))
            .doOnNext(tok -> tokenCount.incrementAndGet())
            .blockingForEach(tok -> {
                // 伪代码：通过 SSE 推给前端
                System.out.print(tok);
            });
        System.out.println("\n[本轮共 " + tokenCount + " 个流式片段]");
    }
}
```

```text
响应式事件流处理链：
  Flowable<Event> ─▶ filter(部分) ─▶ map(取文本) ─▶ doOnNext(计数) ─▶ 前端SSE
                        │
                 非流式事件（工具调用/完成）走另一条分支处理
```

<callout emoji="💡"><p>为什么流式重要？因为用户不愿等一个 10 秒才蹦出完整答案的 Agent。流式输出让"首字延迟"降到几百毫秒，体验天差地别。这也是 ADK 选响应式而非阻塞返回的工程原因。</p></callout>

## 十二、多模态：Gemini 图像输入

ADK 默认接 Gemini，Gemini 原生多模态。一张图直接塞进 `Part` 就能让 Agent"看图说话"。

```java
package com.camp.adk;

import com.google.adk.agents.LlmAgent;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

public class MultimodalDemo {
    public static void main(String[] args) throws Exception {
        LlmAgent agent = LlmAgent.builder()
                .name("vision_agent")
                .instruction("你擅长看图。描述图片内容并回答用户关于图的问题。")
                .model("gemini-2.0-flash")
                .build();

        Runner runner = new Runner(agent, new InMemorySessionService());

        // 把本地图片读成 base64，作为 image Part
        byte[] img = Files.readAllBytes(Path.of("chart.png"));
        String b64 = Base64.getEncoder().encodeToString(img);
        Part imagePart = Part.fromBytes(b64, "image/png");
        Part textPart = Part.fromText("这张图讲了什么趋势？");

        Content msg = Content.fromParts(imagePart, textPart);
        // runner.runAsync(...) 返回事件流，处理方式同前
    }
}
```

<callout emoji="⚠️"><p>企业提醒：多模态输入会显著增大 token 消耗（图片按"token 块"计费）。生产要对图片做预处理（压缩分辨率、裁剪），别直接把 10MB 原图丢给模型。成本意识始终在线。</p></callout>

## 十三、常见误区与避坑清单

我踩过、学生也常踩的坑，列出来帮你省时间：

```text
坑1：把 API Key 写死在代码        → 用环境变量 / Secret Manager
坑2：不设 maxIterations 就跑 Loop  → 烧钱死循环
坑3：子 Agent 不写边界指令        → 越界乱答 / 不委派
坑4：所有工具一股脑塞给一个 Agent  → 模型选错工具
坑5：用全量历史当记忆             → 窗口爆 / 上下文腐烂
坑6：本地能跑就以为生产没问题      → 缺鉴权/可观测/持久化
坑7：低估 GCP 绑定风险            → 数据合规过不了
坑8：忽略模型版本差异             → 升级 Gemini 后行为漂移
```

## 十四、企业落地检查清单（第五章预热）

```text
✅ Agent 逻辑可单测（工具可 mock）
✅ 配置外置（模型/温度/工具开关）
✅ 密钥零硬编码
✅ 多 Agent 边界清晰、委派可控
✅ 循环有上限、有终止条件
✅ 工具输入校验 + 沙箱
✅ 会话持久化 + 多租户隔离（生产必做）
✅ 可观测（事件流日志/追踪）
```

<callout emoji="💡"><p>这份清单会在 lesson05 进一步补全"部署 / 成本 / 扩缩"维度。先建立意识：Agent 能不能上线，看的是这堆工程细节，不是模型多聪明。</p></callout>

## 十五、ADK 开发工具链与本地调试

写 Agent 不能只靠 println。ADK 生态提供一套本地开发工具链，让你快速迭代。

```bash
# 本地开发常用命令（ADK CLI，示意）
adk dev ui              # 启动本地 Web UI，可视化调试 Agent
adk eval ./tests        # 跑评测用例（第十六章详讲）
adk deploy vertex_ai \  # 部署到 Vertex
  --project xxx --region us-central1
```

```text
本地调试工作流：
  改指令 → adk dev ui 对话测试 → 看事件流 → 调指令 → 再测
  比"改代码-编译-跑main"快一个数量级
```

<callout emoji="💡"><p>工具链价值：可视化看到"模型调了什么工具、返回什么、最终说了什么"，比看日志高效十倍。强烈建议写 Agent 全程开着 dev ui，不要盲调。</p></callout>

## 十六、模型路由：按任务选模型

ADK 里可以给不同 Agent 配不同模型。这是成本治理的基础——简单任务用小模型，复杂任务用大模型。

```java
package com.camp.adk;

import com.google.adk.agents.LlmAgent;
import java.util.List;

public class ModelRoutingDemo {
    public LlmAgent build() {
        // 路由用便宜快的 flash，专家用强的 pro
        LlmAgent router = LlmAgent.builder()
                .name("router").model("gemini-2.0-flash")
                .instruction("判断意图并委派子Agent。").build();
        LlmAgent expert = LlmAgent.builder()
                .name("expert").model("gemini-2.5-pro")
                .instruction("处理复杂推理任务。").build();
        return router; // 实际组合见 lesson02/03
    }
}
```

<callout emoji="✅"><p>成本口诀："让小模型干粗活，大模型干细活"。路由/质检/摘要用 flash，生成/推理/代码用 pro。这一条能砍掉 40%+ 的模型成本，是架构师的标配思维。</p></callout>

## 小结

这节课我们建立了对 Google ADK for Java 的整体认知：

1. **定位**：ADK 是"Agent 运行时 + 编排引擎 + 云打通"的端到端框架，区别于 LangChain4j（胶水）和 Spring AI（抽象层）。
2. **架构**：五层模型——基座（Model/Tool/Session）、Agent 层、Flow 编排层、Runner 运行层、部署云层。
3. **六个核心概念**：Agent、Tool、Session、Event、InvocationContext、Runner，分别对应"谁、能做什么、记得什么、发生了什么、怎么调用、怎么跑"。
4. **执行模型**：Agent 内部是"推理—调工具—回填—再推理"的循环，执行结果以响应式事件流返回。
5. **落地价值**：code-first 让 Agent 可版本化、可测试、可组合，适合企业级复杂编排和多 Agent 协作。

记住一句话：**ADK 不是又一个"调 LLM 的封装"，它真正的竞争力在"编排"和"上云"这两件事上。**

---

## 延伸阅读 / 引用文档

1. Google ADK 官方文档（Java）：<https://google.github.io/adk-docs/get-started/>
2. Google ADK GitHub 仓库：<https://github.com/google/adk-java>
3. Gemini Developer API 快速开始：<https://ai.google.dev/gemini-api/docs/quickstart>
4. Vertex AI Agent Engine 概览：<https://cloud.google.com/vertex-ai/generative-ai/docs/agent-engine/overview>
5. Google GenAI Java SDK：<https://github.com/googleapis/java-genai>
6. ADK 编排概念（Sequential/Parallel/Loop）：<https://google.github.io/adk-docs/agents/multi-agents/>

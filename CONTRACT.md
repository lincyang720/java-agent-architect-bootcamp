# 配套代码编写契约（所有写码子代理必读）

目标：交付一个 **能 `mvn compile` 通过、尽可能离线可运行** 的 Java Agent 课程代码库。
技术栈：Spring Boot 3.4.13 + Java 17 + Spring AI 1.0.0 + Spring AI Alibaba 1.1.2.4 + LangChain4j 1.0.1。

## 0. 目录与包约定
- 根目录：`/Users/mac/WorkBuddy/2026-11-10-21-05-32/course/code/`
- 所有 Java 源码在 `src/main/java/com/qiutian/agent/` 下，按章节分包：
  `ch01_02_langchain4j` `ch03_springai` `ch04_multiagent` `ch06_memory` `ch07_rag`
  `ch09_patterns` `ch10_engineering` `ch11_mcp` `ch12_security` `ch15_context`
  `ch16_eval` `ch17_deployment`。
- 你**只**能在分配给你的那个包目录下创建/修改 `.java` 文件。**严禁**修改 `pom.xml`、其他包、或 `common/`。
- 每个示例都是带 `public static void main(String[] args)` 的独立类，类名以 `Demo`/`Example` 结尾。

## 1. 已提供的公共类（直接用，不要重复定义）
- `com.qiutian.agent.common.Env`：`Env.get("OPENAI_API_KEY")` / `Env.getOrDefault(...)` 读取密钥。
- `com.qiutian.agent.common.Demo`：`Demo.banner("标题")`、`Demo.note("...")`、`Demo.ensureKey("OPENAI_API_KEY")`、
  `Demo.sleep(ms)`。返回 false 表示缺密钥，调用方应 `return`。

## 2. 可用依赖（pom 已声明，直接用，勿新增）
- Spring AI：`org.springframework.ai` —— `ChatClient`（`ChatClient.Builder` 注入）、`ChatModel`、
  `spring-ai-advisors-vector-store` 的 `QuestionAnswerAdvisor`、`spring-ai-starter-mcp-server/client`。
  ChatClient 用法：`chatClient.prompt().user("...").system("...").call().content()`；
  流式：`.stream().content()` 返回 `Flux<String>`。
- Spring AI Alibaba：`com.alibaba.cloud.ai` —— `spring-ai-alibaba-starter-dashscope`（DashScope / 通义）、
  `spring-ai-alibaba-graph`（`StateGraph` / `NodeAction` / `OverAllState` / `CompiledGraph`）。
- LangChain4j：`dev.langchain4j` —— `OpenAiChatModel`、`OpenAiEmbeddingModel`、`AiServices`、`@Tool`、
  `ChatMemory`/`MessageWindowChatMemory`、`InMemoryEmbeddingStore`、`EmbeddingStoreContentRetriever`、
  `EmbeddingStoreContentRetriever`、`Content`/`TextSegment`。
- 可观测：`io.micrometer` `Observation` / `MeterRegistry`（稳定，优先用，不要直接碰 OTel 内部 API）。
- 容错：`io.github.resilience4j` 的 `CircuitBreakerRegistry` / `CircuitBreaker`（**用编程式 API**，别用注解）。
- 工具：`jackson-dataformat-yaml`、`commons-lang3`、`lombok`（可选，需 `@Getter` 等时可用）。

## 3. 编译安全铁律（最重要）
1. **所有需要真实模型的调用，必须先 `if (!Demo.ensureKey("OPENAI_API_KEY")) return;`**（DashScope 用 `DASHSCOPE_API_KEY`）。
   这样无密钥环境下 `mvn compile` 与 `java -cp` 都不会抛异常。
2. **不确定某个框架 API 是否精确时，优先用「纯 Java 实现该模式」+ 注释里给出「框架标准写法」**。
   例如多 Agent 编排用自写的 `Workflow`/`Graph` runner；MCP 用自写的最小 JSON-RPC server/client；
   Durable Execution 用自写的「状态持久化 + 断点续跑」；这些都能离线编译运行，再注释说明如何换成
   Spring AI Alibaba Graph / Spring AI MCP / Temporal / Restate。
3. **不要引入 pom 里没有的第三方依赖**（尤其是 google-adk、agentscope、temporal、restate、ragas、sentiment 等）。
   这些用纯 Java 参考实现 + 注释替代。
4. 每个文件顶部写 `package com.qiutian.agent.<你的包>;`，import 写全，无编译警告优先。
5. 避免 `var` 造成歧义；明确泛型。

## 4. 质量与风格
- 贴近课程对应章节（可先读 `../content/chXX/lessonNN_*.md` 的对口小节），但代码要**自洽可跑**，不要只贴片段。
- 每个 Demo 类：开头 `Demo.banner(...)`；中间分步 `Demo.note(...)` 解释在做什么；结尾打印结果。
- 真实可编译：不得出现 TODO 占位未实现的方法体（如需示意，用注释或小桩函数实现掉）。
- 一个包里至少 2~4 个 Demo 类，覆盖该章核心知识点。
- 中文注释为主，关键 API 保留英文。

## 5. 自检
写完你负责的所有文件后，在 `course/code` 目录执行（仅编译你的包相关依赖即可，但全量编译更稳）：
`mvn -q -o compile` 或 `mvn -q compile`（首次会下载依赖，较慢，耐心等）。
确保 **0 错误**。若报依赖未下载，去掉 `-o` 重新来。把最终错误（如有）一并回报给我。

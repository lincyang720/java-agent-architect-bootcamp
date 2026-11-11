# Java Agent 架构师训练营 · 配套代码

> 《Java 版 Agent 架构师训练营》课程的**全部配套可运行示例**。
> 对应课程大纲（飞书）：基于 LangChain4j / Spring AI / Spring AI Alibaba / Google ADK 的 Java Agent 开发体系，
> 并根据 2026 年企业对 AI Agent 岗位的招聘需求，新增了**上下文工程、Agent 评测与质量闭环、Agent 部署与平台工程、模型微调与推理优化**四大专章。

---

## 一、技术栈

| 类别 | 选型 |
|---|---|
| 语言 / 构建 | Java 17 + Maven 3.x |
| 基础框架 | Spring Boot 3.4.x |
| 国产大模型接入 | Spring AI Alibaba 1.0.0.2（DashScope / 通义） |
| Agent 框架 | LangChain4j 1.0.1、Spring AI 1.0.0 |
| 可观测 | Micrometer Tracing + OpenTelemetry（导出 OTLP） |
| 容错 | Resilience4j 2.2 |
| 微调 / 推理优化（参考） | Python：transformers + peft + vLLM |

---

## 二、项目结构（按章节分包）

所有源码在 `src/main/java/com/qiutian/agent/` 下，每个章节一个包，包内均为**带 `main` 方法的独立示例**：

| 包 | 对应章节 | 演示要点 |
|---|---|---|
| `ch01_02_langchain4j` | 第 1–2 章 LangChain4j | ChatModel、AiServices Agent、@Tool 工具调用、记忆、最小 RAG、结构化输出 |
| `ch03_springai` | 第 3 章 Spring AI | ChatClient（同步/流式）、PromptTemplate、结构化输出、Function Calling、DashScope |
| `ch04_multiagent` | 第 4 章 多 Agent 编排 | 极简 StateGraph、Supervisor、Sequential+Loop、HumanInTheLoop、PlanNotebook、安全沙箱 |
| `ch06_memory` | 第 6 章 记忆 | 短期窗口、长期语义记忆、向量库选型对比、压缩、遗忘（时间衰减） |
| `ch07_rag` | 第 7 章 RAG | 文档分割、Embedding、混合检索(RRF)、重排、端到端管线、查询改写/溯源 |
| `ch09_patterns` | 第 9 章 架构模式 | PromptChain、Routing、Parallelization、Orchestrator-Workers、Evaluator-Optimizer、事件驱动 |
| `ch10_engineering` | 第 10 章 工程化 | 可观测(Observation)、工具调用测试、Prompt 版本化、Resilience4j 熔断、成本治理 |
| `ch11_mcp` | 第 11 章 MCP/A2A | 最小 MCP Server/Client（JSON-RPC）、跨框架互操作、Agent-to-Agent 协议骨架 |
| `ch12_security` | 第 12 章 安全治理 | 工具权限网关、入参校验/清洗、输出护栏(脱敏)、行为审计 |
| `ch15_context` | 第 15 章 上下文工程 | Write / Select / Compress / Isolate 四大策略 |
| `ch16_eval` | 第 16 章 评测闭环 | 组件指标(HitRate/MRR)、RAGAS 风格启发式、LLM-as-Judge、评测闭环（含 Python RAGAS 脚本） |
| `ch17_deployment` | 第 17 章 部署/平台工程 | 运行时架构、Durable Execution（状态持久化续跑）、沙箱执行 |

第 5 章（Google ADK）与第 8 章（认知架构）、第 13–14 章（趋势/生态）以课程文稿为主，代码示例已融入上述可运行包。
第 18 章（微调 / 推理优化）为 Python 参考实现，见 `ch18_finetune/python/`。

课程全部文稿（与飞书同源）位于 `docs/lessons/`，按 `ch01` ~ `ch18` 分目录归档，共 **18 章 97 节**（含第 1、2 章与第 3 章第 1 节）。

---

## 三、如何构建与运行

### 1. 编译
```bash
mvn -q compile      # 已验证 0 错误
```

### 2. 配置 API Key（需要真实模型调用的示例）
直接在终端 export，无需改文件：
```bash
export OPENAI_API_KEY=sk-xxx        # OpenAI / 兼容端点
export DASHSCOPE_API_KEY=sk-xxx     # 通义千问（Spring AI Alibaba）
```
> 未设置 Key 时，涉及真实模型调用的示例会**友好提示并退出**，不会抛异常；
> 大量「编排 / 模式 / 安全 / 上下文 / 部署」类示例为**纯 Java 桩逻辑，完全离线可跑**。

### 3. 运行单个示例
```bash
# 编译后直接用 java 运行某个 Demo（注意把依赖放进 classpath）
mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "target/classes:$(cat cp.txt)" \
     com.qiutian.agent.ch03_springai.ChatClientDemo

# 或跑项目入口看章节总览
java -cp "target/classes:$(cat cp.txt)" com.qiutian.agent.CourseApplication
```

### 4. 第 18 章 Python 参考（可选）
```bash
cd ch18_finetune/python
pip install -r requirements.txt
python3 finetune_lora.py --help
python3 inference_optimize.py --help
```

---

## 四、设计约定

1. **公共工具**：`common/Env`（读密钥）、`common/Demo`（横幅/Key 守卫）。
2. **编译安全**：所有真实模型调用前统一 `if (!Demo.ensureKey(...)) return;`；
   对版本易漂移的框架（Spring AI Alibaba Graph / Temporal / Restate / Google ADK / AgentScope）
   采用「纯 Java 实现该模式 + 注释给出框架标准写法」，保证仓库**开箱即编译、大量示例离线可跑**。
3. **依赖最小化**：仅引入 spring-ai 1.0.0、spring-ai-alibaba 1.0.0.2、langchain4j 1.0.1、resilience4j、micrometer 等稳定依赖，
   不在 pom 中引入 google-adk / agentscope / temporal / rag 等，避免版本/构件漂移导致构建失败。

---

## 五、与课程大纲的对应关系

本课程共 18 章（含 V2 新增 4 章）。每节课的完整文稿在飞书课程大纲中，并在 `docs/lessons/` 同步归档。
本仓库代码按"章节包"组织，便于你学完某章后直接运行对应包下的示例。

祝学习顺利 —— 秋天

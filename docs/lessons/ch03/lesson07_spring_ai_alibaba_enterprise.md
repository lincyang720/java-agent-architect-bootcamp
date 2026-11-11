# 第三章第7节：【新增】Spring AI Alibaba 企业级能力：Prompt 管理、RAG、可观测与 Nacos/Higress

大家好，我是秋天。

上一节我们把 Graph 跑通了，能编排多步工作流。但你把这套东西往生产一放，马上会被架构师和运维怼回来一堆问题："提示词改一句要不要发版？""知识库怎么接？""出错了怎么查？""几十个 Agent 的 API 怎么统一治理、限流、灰度？"

这一节就是 Spring AI Alibaba 真正"企业级"的部分：**Prompt 管理、RAG 知识库、可观测性、以及与 Nacos / Higress 的深度集成**。这些东西单靠 `ChatClient` 是给不了的，是阿里云百炼生态附带的工程化能力。

这节课讲什么、学完掌握什么：

- 讲什么：Prompt 管理平台（提示词版本化/灰度/回滚）、Spring AI Alibaba 的 RAG 知识库方案（文档解析→Embedding→向量检索→生成）、可观测性接入（ARMS / Langfuse / OpenTelemetry）、Nacos 做配置/服务/MCP 注册、Higress 做统一网关与限流。
- 学完掌握：你理解企业级 Agent 为什么不能只靠代码里的字符串；你能画出"从提示词到知识库到可观测到网关治理"的完整企业架构图；你知道这些组件各自解决什么痛点。

<callout emoji="💡"><p>招聘映射：JD 里的"平台工程能力""Prompt 管理""RAG 全链路""可观测性""Spring Cloud / Nacos / Gateway 治理"——这一节几乎覆盖了一遍。能讲清"Demo 到生产的鸿沟怎么填"，是高级 Agent 工程师和初学者的分水岭。</p></callout>

---

## 一、先看清：Demo 和生产的鸿沟在哪

一个本地跑通的 Agent Demo，离生产还差这几座山：

```
┌─────────────────── 本地 Demo ───────────────────┐
│  提示词硬编码在 Java │ 知识靠 prompt 塞 │ 无监控 │ 直连模型 │
└────────────────────────────────────────────────┘
                        │  鸿沟
                        ▼
┌─────────────────── 企业生产 ───────────────────┐
│  Prompt 平台管理  │ RAG 知识库  │ 可观测  │ Nacos/Higress 治理 │
└────────────────────────────────────────────────┘
```

每一座山对应 Spring AI Alibaba 的一个能力模块。我们逐个拆。

---

## 二、Prompt 管理：把提示词从"代码"变成"资产"

lesson03 我们讲了把提示词放 `application.yml`。但这还不够——运营要改文案、算法要 A/B 测试、合规要审计"某天用的什么提示词"。这些需求靠 yml 满足不了，需要**Prompt 管理平台**。

Spring AI Alibaba 提供了 Prompt 管理的抽象，可以把提示词存到外部存储（数据库 / 配置中心 / 专用 Prompt 服务），运行时按"名称 + 版本"拉取：

```java
package com.example.ch03.enterprise;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

// 概念示意：Prompt 从管理平台按版本拉取，而非硬编码
@Service
public class ManagedPromptService {

    private final ChatClient chatClient;
    private final PromptRepository promptRepo; // 假设接入 Prompt 管理平台

    public ManagedPromptService(ChatClient chatClient, PromptRepository promptRepo) {
        this.chatClient = chatClient;
        this.promptRepo = promptRepo;
    }

    public String reply(String userQuestion, String promptName, String version) {
        // 1. 从平台拉取指定版本提示词模板
        String template = promptRepo.getTemplate(promptName, version);
        // 2. 填充变量
        PromptTemplate pt = new PromptTemplate(template);
        // 3. 调用
        return chatClient.prompt()
                .user(u -> u.text(template).params(Map.of("question", userQuestion)))
                .call()
                .content();
    }
}
```

平台带来的核心能力：
- **版本化**：每次改提示词生成一个版本，可回滚。
- **灰度**：新版本先放 5% 流量，对比效果（配合第十六章评测）。
- **审计**：谁、什么时候、改了什么，全留痕——金融/政企的合规刚需。
- **多环境**：dev / test / prod 用不同提示词，互不干扰。

<callout emoji="✅"><p>工程经验：提示词管理的本质是把"AI 行为"从代码里解耦出来，变成可运营的配置。这跟 Spring Cloud Config / Nacos 配置管理的思路一脉相承。记住一句话——能不发版就改的，都别写死在代码里。</p></callout>

---

## 三、RAG 知识库：让 Agent 基于企业私有知识回答

 lesson03 提到"知识层来自检索"，这就是 RAG（检索增强生成）。Spring AI Alibaba 对 RAG 全链路有完整支持，流程是经典的"索引—检索—生成"：

```
文档(PDF/Word/Excel)
   │  ① 解析切分 (Tika/PDFBox/POI/EasyExcel，第七章细讲)
   ▼
Embedding 向量
   │  ② 写入向量库 (Milvus / pgvector / Redis)
   ▼
用户问题 ─▶ Embedding ─▶ ③ 相似检索 ─▶ 相关片段
                                        │
                                        ▼
                              ④ ChatClient + 片段 → 回答(带引用)
```

Spring AI 的 `QuestionAnswerAdvisor` 是开箱即用的 RAG 接入点，可以和 `ChatClient` 串起来：

```java
package com.example.ch03.enterprise;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.stereotype.Service;

@Service
public class RagAssistant {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;   // 已建好的企业知识库

    public RagAssistant(ChatClient chatClient, VectorStore vectorStore) {
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;
    }

    public String ask(String question) {
        return chatClient.prompt()
                .user(question)
                // RAG Advisor：先去向量库检索相关片段，拼进 prompt
                .advisors(QuestionAnswerAdvisor.builder(vectorStore).build())
                .call()
                .content();
    }
}
```

`QuestionAnswerAdvisor` 背后自动做：把问题向量化 → 检索 top-k → 拼进系统/用户消息 → 让模型"只基于检索内容回答"。这样客服 Agent 就能基于"企业私有政策文档"作答，而不是靠模型记忆瞎编。

<callout emoji="⚠️"><p>RAG 不等于"接个向量库就完事"。企业级 RAG 的坑在第七章展开：切分策略、Embedding 选型、混合检索、重排序（Rerank）、查询改写、上下文压缩、引用溯源。随便接个库效果会很差。这一节你先建立"RAG 是 Advisor 接入 ChatClient"的心智即可。</p></callout>

---

## 四、可观测性：没有监控的 Agent 等于裸奔

Agent 调用链长（模型 + 工具 + 检索 + 下游服务），出了错你不知道卡在哪。Spring AI Alibaba 支持接入 ARMS（阿里云应用实时监控）、Langfuse（LLM 专用观测）、以及 OpenTelemetry 标准。

关键观测维度：
- **链路追踪**：一次对话经过了哪些节点/工具/检索，各耗时多少。
- **Token 与成本**：每个请求消耗多少 token、花多少钱（第十章成本治理）。
- **质量指标**：幻觉率、工具调用成功率、用户满意度。
- **告警**：模型超时、工具异常率突增、成本超预算。

接入 OpenTelemetry 的最小配置（概念示意）：

```yaml
# application.yml
otel:
  exporter:
    otlp:
      endpoint: ${OTEL_ENDPOINT:http://otel-collector:4317}
  resource:
    attributes: service.name=ch03-agent
management:
  tracing:
    sampling:
      probability: 1.0
```

配合 `spring-ai` 自带的 `Observation` 埋点，模型调用会自动生成 span，进 Jaeger / Grafana 可视化。这一步是"能上线"和"能运维"的分界。

<callout emoji="💡"><p>为什么重要：面试常问"你的 Agent 怎么保证质量、怎么排查问题"。答案不是"我加了日志"，而是"全链路可观测 + 评测闭环（第十六章）"。可观测性让你看见 Agent 内部，评测让你量化 Agent 好坏——两者合起来才是生产级。</p></callout>

---

## 五、Nacos：配置、服务、MCP 注册中心一把抓

在 Java 企业生态里，Nacos 是"配置中心 + 服务注册 + 动态 DNS"的老朋友。Spring AI Alibaba 把 Nacos 用在三个地方：

**1. 配置管理**：提示词、模型参数、路由规则放 Nacos，动态下发，不用重启。

```yaml
# bootstrap.yml
spring:
  cloud:
    nacos:
      config:
        server-addr: ${NACOS_ADDR:nacos:8848}
        file-extension: yaml
      discovery:
        server-addr: ${NACOS_ADDR:nacos:8848}
```

**2. MCP 注册与发现**：Nacos 3.x 支持 **MCP Registry**——把你写的工具（MCP Server）注册到 Nacos，Agent 启动时自动发现、动态路由，不用硬编码工具地址。这是 Spring AI Alibaba 的亮点：

```xml
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-starter-nacos-mcp-client</artifactId>
</dependency>
```

配置后，`ToolCallbackProvider` 自动从 Nacos 拉取已注册的 MCP 工具，Agent 直接 `.tools(provider)` 就能用，工具上下线对代码透明。

**3. 多租户/环境隔离**：不同租户的提示词、知识库、模型路由，用 Nacos 的 namespace 隔离。

<callout emoji="✅"><p>设计价值：MCP + Nacos 组合，让你从"把一个工具写死在代码里"升级到"工具作为服务被注册、发现、治理"。这是 Agent 从单体走向微服务化、平台化的关键一步，对应 JD 里"熟悉 MCP / Nacos / 服务治理"。</p></callout>

---

## 六、Higress：AI 网关统一治理

模型 API 直接暴露给前端是大忌——没限流、没鉴权、没灰度、没缓存。Higress（阿里云开源的云原生网关，兼容 Envoy）在 Agent 架构里承担"AI 网关"角色：

```
前端/其他服务
     │
     ▼
┌─────────── Higress (AI Gateway) ───────────┐
│  鉴权 │ 限流 │ 灰度 │ 提示词缓存 │ 模型路由 │
│  审计 │ 敏感词过滤 │ Token 配额              │
└───────────────────┬───────────────────────┘
                    ▼
          Spring AI Alibaba Agent 集群
                    ▼
          DashScope / 多模型后端
```

Higress 能做的事：
- **统一入口**：所有 Agent 的 API 走同一个网关，URL 规整、便于治理。
- **限流与配额**：按租户/按 API Key 限流，防止模型被刷爆（对应第十章限流熔断）。
- **模型路由与灰度**：A/B 测试不同模型（qwen-max vs qwen-plus），按比例分流。
- **提示词缓存**：相同 prompt 命中缓存，省 token（对应第十五章上下文缓存）。
- **安全护栏**：敏感词过滤、输出审计，满足合规。

<callout emoji="⚠️"><p>别把 DashScope 的 API Key 直接放前端！正确架构是前端 → Higress（带鉴权/限流）→ 你的 Spring AI Alibaba 后端（持有 Key）→ 模型。Key 永远不离开服务端。这是安全红线。</p></callout>

---

## 七、企业落地全景图：把这一章的能力拼起来

把 lesson02~07 的能力串成一张企业 Agent 平台架构图：

```
┌──────────────────────── 企业 Agent 平台 ────────────────────────┐
│                                                                  │
│  前端/业务系统                                                   │
│       │ HTTPS                                                    │
│       ▼                                                          │
│  ┌───────── Higress AI 网关 ─────────────────────────────┐      │
│  │ 鉴权 / 限流 / 灰度 / 缓存 / 审计 / 敏感过滤           │      │
│  └───────────────────────┬──────────────────────────────┘      │
│                          ▼                                       │
│  ┌──────── Spring AI Alibaba Agent 应用集群 ────────────┐        │
│  │  ChatClient (DashScope)                              │        │
│  │  ├─ Prompt 管理平台 (版本/灰度/审计)                  │        │
│  │  ├─ Graph 工作流编排 (多步 Agent)                    │        │
│  │  ├─ 工具调用 (MCP, Nacos 注册发现)                   │        │
│  │  └─ RAG Advisor (向量库知识检索)                     │        │
│  └───────────┬──────────────────────┬─────────────────┘        │
│              ▼                      ▼                            │
│  ┌──── Nacos ────┐        ┌── 可观测 (ARMS/Langfuse/OTel) ──┐   │
│  │ 配置/服务/MCP │        │ Trace / Metric / 成本 / 告警    │   │
│  └───────────────┘        └────────────────────────────────┘   │
│              ▼                      ▼                            │
│        向量库(Milvus/pgvector)   百炼平台(模型/知识库/RAG)       │
└──────────────────────────────────────────────────────────────────┘
```

这张图就是你在面试里能画出来的"企业级 Agent 架构"。每一块都对应我们这一章讲的能力，也对应 JD 里的具体要求。

<callout emoji="💡"><p>求职导向：能把上面的架构图讲清楚，并说清每块解决什么痛点，你在这个方向就是"有架构视野"的候选人，而不是"只会调 API"的执行层。很多企业招高级 Agent 工程师，考的就是这种"从 Demo 到生产"的全局能力。</p></callout>

---

## 九、多租户与权限隔离

企业里一套 Agent 平台服务多个租户（子公司/客户），必须隔离数据与模型访问。Nacos 的 namespace 做配置/服务隔离，`ToolContext`（lesson05）注入可信 `tenantId`，工具层按 tenant 过滤数据。

```java
package com.example.ch03.enterprise;

import org.springframework.ai.tool.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class TenantOrderTool {

    @Tool(description = "查询当前租户的订单")
    public String query(@ToolParam(description = "订单号") String orderId,
                        ToolContext ctx) {
        String tenant = (String) ctx.getContext().get("tenantId");  // 可信注入
        return orderService.queryForTenant(orderId, tenant);  // 租户维度过滤
    }
}
```

<callout emoji="⚠️"><p>安全红线：绝不能用模型传入的"租户标识"做隔离——模型可被诱导伪造。隔离标识必须来自你注入的可信上下文（网关 JWT 解析后的 tenantId），工具层强制过滤。这是多租户 Agent 平台的生死线。</p></callout>

---

## 十、成本治理初探（第十章预告）

Agent 跑起来，token 账单会让你肉疼。企业级平台必须治理成本：

- **模型路由**：简单问题用便宜模型（qwen-turbo），复杂问题才用贵模型（qwen-max）。
- **提示词缓存**：相同前缀命中缓存不重复计费（Higress/上下文缓存）。
- **配额与预算**：按租户/API Key 设日额度，超限降级。
- **压缩与摘要**：长上下文做摘要（第十五章），省 token。

```yaml
# 概念配置：模型路由（简单/复杂分流）
app:
  model-routing:
    simple: qwen-turbo
    complex: qwen-max
    budget-per-day: 1000   # 元
```

这部分第十章"成本治理"会系统展开，这里先建立"Agent 要算账"的意识。

---

## 十一、灰度发布与回滚

提示词、Graph 结构、模型版本都能灰度。做法借力 Spring Cloud / K8s：

```
新提示词 v2 ─▶ 5% 流量（影子/真实）
        │  评测（第十六章）对比 v1 vs v2
        ▼
   效果好 → 全量；效果差 → 一键回滚到 v1
```

提示词平台（lesson07 上半节）存版本，Nacos 下发版本号，Agent 启动时拉指定版本。回滚 = 把版本号指回旧版，无需重新发版。

---

## 十二、与 Spring Cloud 生态整合

Spring AI Alibaba 不是孤岛，它长在 Spring Cloud 生态上：Nacos（配置/注册）、Sentinel（限流熔断）、Seata（分布式事务）、Gateway（网关，与 Higress 互补）。一个企业 Agent 平台的技术栈大致是：

```
Spring Boot 3.x + Spring AI Alibaba (Agent/Graph)
   ├─ Nacos: 配置/服务/MCP 注册
   ├─ Sentinel: 工具调用限流熔断
   ├─ Higress: AI 网关
   └─ 可观测: ARMS/Langfuse/OTel + Prometheus
```

这意味着你原有的 Java 微服务经验（Spring Cloud）可以直接平移到 Agent 平台——这也是 Java 工程师转 Agent 的最大优势：不必另起炉灶。

<callout emoji="💡"><p>求职导向：能说出"我的 Agent 平台是 Spring Cloud 技术栈的自然延伸，Nacos/Sentinel/Higress 都能复用"，面试官会认为你是"能落地"的工程师，而不是只玩过 notebook demo。政企/大厂极其看重这点。</p></callout>

---

## 十三、面试自测

1. **提示词为什么不能硬编码？** —— 运营要改、要 A/B、要审计，必须外置到平台/配置中心。
2. **RAG 在 Spring AI 怎么接？** —— `QuestionAnswerAdvisor` + `VectorStore` 注入 ChatClient。
3. **可观测性看什么？** —— 链路 trace、token/成本、质量指标、告警。
4. **Higress 干嘛？** —— AI 网关：鉴权/限流/灰度/缓存/审计，Key 不离开服务端。

---

## 十五、RAG 全链路质量：从"能检索"到"检索得准"

lesson07 提了 RAG 用 `QuestionAnswerAdvisor` 接入，但"接进来"≠"效果好"。企业级 RAG 质量链路（第七章细讲，这里先建认知）：

```
原始文档
  → 解析切分(PDFBox/Tika)      ← 切错段落，后面全错
  → Embedding(选型)            ← 模型不匹配，语义距离失真
  → 向量库存储(Milvus/pgvector)
  → 检索(混合检索+重排序)       ← 召回不准，答非所问
  → 查询改写/压缩              ← 用户问法模糊时改写
  → 生成(带引用溯源)           ← 标注出处，可核查
```

每一环都能成为质量瓶颈。Spring AI Alibaba 的 RAG 方案把"切分→Embedding→检索→生成"标准化，但你仍要为"切分策略、Embedding 选型、重排序"做调优——这些决定回答质量，不是接个库就完事。

<callout emoji="💡"><p>面试加分：能说出"RAG 质量 = 切分 × Embedding × 检索 × 重排 的连乘，任何一环是 0 整体就崩"，并点出"重排序(Rerank)和混合检索是效果提升的关键杠杆"，说明你理解 RAG 工程而非只会 `QuestionAnswerAdvisor`。</p></callout>

---

## 十六、可观测性的三大信号：Trace / Metric / Log

lesson07 提了可观测，这里把三类信号讲清，它们是 Agent 运维的"三件套"：

```
Trace（链路）：一次对话经过哪些节点/工具/检索，各耗时 → 定位慢点
Metric（指标）：token 消耗、调用成功率、延迟 P99 → 量化健康度
Log（日志）：每次 tool_call 的入参/出参/异常 → 复盘 badcase
```

在 Spring AI Alibaba 里，Trace 靠 OpenTelemetry 自动埋点（每次模型调用生成 span），Metric 靠 Micrometer 暴露给 Prometheus，Log 靠结构化日志 + 审计。三者汇入 Grafana 统一看板。

<callout emoji="⚠️️<p>没有 Metric 的 Agent 上线 = 盲飞。至少监控：单次 token 成本、P99 延迟、工具调用失败率、人工确认堆积数。这四项任一异常都直接告警。第十章成本治理 + 第十六章评测会系统接上。</p></callout>

---

## 十七、Spring AI Alibaba 与 LangChain4j 的取舍

课程第一章提到 LangChain4j，这里给选型参考（第三章是 Spring AI 体系，但你要知道何时选谁）：

| 维度 | Spring AI Alibaba | LangChain4j |
|------|------------------|-------------|
| 生态 | 阿里系、DashScope、Nacos、Higress | 多模型、轻量 |
| Graph 编排 | 强（graph-core） | 较弱（偏 AiServices） |
| 企业集成 | Nacos/MCP/百炼深 | Spring 生态也支持 |
| 适合 | 阿里云/政企/需 Graph 编排 | 轻量快速集成多模型 |

结论：已在 Spring Cloud 技术栈、用阿里云、需要 Graph 编排 → Spring AI Alibaba；要快速对接多家模型、轻量 → LangChain4j。两者都符合"Java Agent"主线，不冲突。

---

## 十九、Prompt 管理的灰度与回滚实战

lesson07 提了 Prompt 管理平台，这里给灰度/回滚的代码形态（概念）：

```java
// 概念：按用户哈希分流到不同提示词版本
String version = (userId.hashCode() % 100) < 5 ? "v2" : "v1";  // 5% 流量走 v2
String template = promptRepo.getTemplate("classifier", version);

// 回滚：出问题一键把版本指回 v1（配置中心/Nacos 动态下发）
// promptRepo 读的是配置中心值，改配置即生效，无需发版
```

灰度 + 回滚让"提示词变更"从"高风险发版"变成"可灰度、可秒回"的配置操作。对应 lesson03 的"提示词即配置"思想，在平台层落地。

<callout emoji="✅"><p>工程价值：没有灰度/回滚的提示词平台只是"换个地方存字符串"。真正的企业级能力是"改提示词像改配置一样安全"——可灰度、可评估、可回滚。这是质量闭环（第十六章）的入口。</p></callout>

---

## 二十、Nacos MCP Registry 深入

lesson07 提了 Nacos 做 MCP 注册发现，这里补它的价值：传统做法 Agent 硬编码工具地址，工具上下线要改代码；Nacos MCP Registry 让工具"注册即被发现"：

```
[工具服务A] ──注册──▶ [Nacos MCP Registry] ◀──发现── [Agent 应用]
[工具服务B] ──注册──▶ [Nacos MCP Registry]
```

Agent 启动时从 Nacos 拉取当前可用的 MCP 工具列表，动态注入 `ToolCallbackProvider`。工具扩缩容、版本升级对 Agent 透明。这是"Agent 平台工程"里服务治理的关键一环。

---

## 二十一、Higress 的提示词缓存与成本

lesson07 提了 Higress 做缓存，这里补机制：相同前缀的 prompt（如长系统提示词 + 知识库模板）命中缓存后，模型只计费"变化部分"，省大量 token。

```
请求1: [系统提示词2k字 + 用户A问题] → 计费 2k + 问题
请求2: [系统提示词2k字 + 用户B问题] → 命中缓存，仅计费 问题
```

配合"系统提示词固定 + 用户问题变化"的模式，缓存命中率极高。这是第十章成本治理在网关层的落地。

---

## 二十二、小结

这一节补齐了 Spring AI Alibaba 的"企业级"拼图：

1. **Prompt 管理**：提示词从代码解耦为可版本化/灰度/审计的资产，呼应 Spring Cloud 配置管理思想。
2. **RAG 知识库**：通过 `QuestionAnswerAdvisor` 接入 `ChatClient`，让 Agent 基于企业私有知识作答（全链路坑在第七章）。
3. **可观测性**：ARMS / Langfuse / OpenTelemetry 全链路追踪，是"能运维"的前提。
4. **Nacos**：配置中心 + 服务注册 + **MCP Registry**，工具作为服务被治理。
5. **Higress**：AI 网关做鉴权/限流/灰度/缓存/审计，Key 永不离开服务端。
6. 最终拼成"企业 Agent 平台全景图"——从前端到网关到 Agent 集群到 Nacos/可观测/向量库/百炼。

到这一节，第三章（Spring AI 框架深度解析）全部讲完：ChatClient → 提示词 → 结构化输出 → 工具调用 → Graph 编排 → 企业级能力。下一章我们进入**多 Agent 协作与编排模式**，看多个 Agent 怎么配合、怎么由 Graph 编排、怎么引入人工审核——这是 Agent 从"单兵"到"军团"的跃迁。

---

## 延伸阅读 / 引用文档

1. Spring AI Alibaba 提示词管理（Prompt Management）：https://java2ai.com/docs/1.0.0.2/tutorials/prompt-management/
2. Spring AI Alibaba RAG / 知识库方案：https://java2ai.com/docs/1.0.0.2/tutorials/rag/
3. Spring AI Alibaba 可观测性（观测与调试）：https://java2ai.com/docs/1.0.0.2/tutorials/observability/
4. Nacos MCP Registry 官方文档：https://nacos.io/docs/latest/ecology/mcp-registry/
5. Higress AI 网关文档：https://higress.cn/docs/latest/
6. 阿里云百炼平台（模型/RAG 知识库）：https://help.aliyun.com/zh/model-studio/

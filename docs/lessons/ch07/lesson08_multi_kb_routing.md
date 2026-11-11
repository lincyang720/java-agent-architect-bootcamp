# 第七章第八节：【新增】多知识库路由与企业级 RAG 架构

大家好，我是秋天。

前面七节我们把"单知识库 RAG"的每一个环节都拆透了。但真实企业里，知识从来不是一坨——它有**产品库、法务库、工单库、HR 制度库、技术文档库**，各自权限不同、更新频率不同、向量模型可能都不同。把所有文档塞进一个库暴力检索，结果就是"问产品问到法务条款、普通员工搜到 confidential 合同"。这节课讲 RAG 真正落地的形态：**多知识库路由 + 企业级架构**。

学完这节课，你能掌握：

1. **多知识库路由（Router）**：怎么让系统判断"这个问题该去哪个库搜"，以及路由的几种实现路线。
2. **企业级 RAG 的整体架构**：从接入、解析、索引到检索、生成、观测的全链路分层设计。
3. **权限隔离与多租户**：不同角色看到不同知识，这是企业 RAG 的合规红线。

<callout emoji="💡"><p>招聘映射：JD 高频词"企业级 RAG 架构""多知识库""权限隔离"。面试官问"你们 RAG 怎么保证普通员工搜不到机密文档"，这节课就是标准答法。</p></callout>

---

## 一、为什么单库暴力检索在企业里行不通

```
单库混搜的灾难：
┌──────────────────────────────────────────────┐
│ 全量文档（产品/法务/工单/HR/机密合同）         │
│         │ 统一向量化                          │
│         ▼                                     │
│   一个 pgvector 表                            │
│         │ 用户"怎么报销"                      │
│         ▼                                     │
│ 召回 Top3 里混进了《高管薪酬保密协议》片段 ❌   │
└──────────────────────────────────────────────┘
```

三个无法回避的问题：

1. **权限**：不同角色应有不同可见范围，单库无法做行级权限。
2. **相关性**：跨域文档互相污染，产品问题被法务条款干扰。
3. **运维**：所有文档一个索引，更新频率不同的文档被迫一起重建。

---

## 二、多知识库路由（Router）：让对的问题进对的库

### 2.1 路由的三种实现路线

```
路由策略对比：
┌──────────────┬──────────────────┬──────────────────────┐
│ 策略          │ 做法              │ 利弊                  │
├──────────────┼──────────────────┼──────────────────────┤
│ 元数据/规则   │ 按字段硬路由      │ 快、可控，但死板      │
│ 分类模型      │ 小模型判类别      │ 准、便宜，需训练标注  │
│ LLM 路由      │ 大模型判去哪个库  │ 最灵活，但慢且贵      │
└──────────────┴──────────────────┴──────────────────────┘
```

### 2.2 规则/元数据路由（最简单可靠）

用户问题里常带明确信号，比如"合同""报销""bug"，用关键词/正则直接路由，零延迟零成本。

```java
// ch07-rag/src/main/java/demo/router/RuleRouter.java
package demo.router;

import java.util.Map;
import java.util.regex.Pattern;

public class RuleRouter {
    private static final Map<Pattern, String> RULES = Map.of(
        Pattern.compile("合同|条款|违约|赔偿"), "legal",
        Pattern.compile("报销|发票|差旅"), "finance-hr",
        Pattern.compile("退款|退货|物流|订单"), "product",
        Pattern.compile("bug|报错|接口|超时"), "tech-doc"
    );

    public static String route(String query) {
        for (var e : RULES.entrySet()) {
            if (e.getKey().matcher(query).find()) return e.getValue();
        }
        return "default"; // 兜底默认库
    }

    public static void main(String[] args) {
        System.out.println(route("我和供应商的合同违约了")); // legal
        System.out.println(route("退款多久到账"));            // product
    }
}
```

### 2.3 LLM 路由（最灵活）

问题信号不明显时，用 LLM 判断该去哪个库，甚至决定"搜几个库"。

```java
// ch07-rag/src/main/java/demo/router/LlmRouter.java
package demo.router;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;

import java.util.List;

public class LlmRouter {
    record Kb(String id, String desc) {}

    interface Router {
        @UserMessage("""
            从下面的知识库列表中选择最相关的 1~3 个来回答用户问题。
            只返回知识库 id 的 JSON 数组，不要解释。
            知识库:
            {{kbs}}
            用户问题: {{query}}
            """)
        String route(String kbs, String query);
    }

    public static void main(String[] args) {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini").build();
        Router router = AiServices.create(Router.class, model);

        List<Kb> kbs = List.of(
            new Kb("product", "产品说明与售后政策"),
            new Kb("legal", "合同与法务条款"),
            new Kb("tech", "技术文档与API")
        );
        String json = router.route(kbs.toString(), "接口返回403怎么处理");
        System.out.println(json); // ["tech"]
    }
}
```

### 2.4 路由后分别检索再融合

```java
// ch07-rag/src/main/java/demo/router/RoutedRetrieval.java
package demo.router;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.search.Request;
import dev.langchain4j.data.embedding.Embedding;

import java.util.*;

public class RoutedRetrieval {
    // 每个库一个独立 EmbeddingStore
    private final Map<String, EmbeddingStore> stores;

    public RoutedRetrieval(Map<String, EmbeddingStore> stores) {
        this.stores = stores;
    }

    /** 先路由到目标库，再分别检索，最后合并 */
    public List<TextSegment> retrieve(String query,
                                      Embedding qe,
                                      List<String> targetKbs,
                                      int topK) {
        List<TextSegment> all = new ArrayList<>();
        for (String kb : targetKbs) {
            EmbeddingStore store = stores.get(kb);
            if (store == null) continue;
            store.search(Request.builder().queryEmbedding(qe).maxResults(topK).build())
                 .matches().forEach(m -> all.add(m.embedded()));
        }
        return all; // 交给 rerank 统一精排（lesson04）
    }
}
```

<callout emoji="⚠️"><p>路由策略选型建议：先用"规则路由"覆盖 80% 的明确场景（快、稳、零成本），只对规则匹配不上的长尾问题才上 LLM 路由。千万别一上来就全量 LLM 路由，延迟和成本都不可承受。这是"能用规则就别上模型"的工程哲学。</p></callout>

---

## 三、权限隔离与多租户

企业 RAG 的合规红线：**不是所有用户都能看所有文档**。路由之外，还要做行级权限过滤。

### 3.1 基于角色的库隔离

```
角色          可见知识库
─────────────────────────────
普通客服      产品库、工单库
法务          法务库、合同库
高管          全部 + 机密库
访客          公开FAQ库
```

### 3.2 检索时注入权限过滤（pgvector 元数据过滤）

```java
// ch07-rag/src/main/java/demo/auth/AuthorizedRetrieval.java
package demo.auth;

import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.search.Request;

import java.util.Set;

public class AuthorizedRetrieval {
    /** 根据用户角色，只对"有权限的库"检索，并附带元数据权限标签过滤 */
    public static void retrieve(EmbeddingStore store,
                                String queryEmbeddingJson,
                                Set<String> allowedLevels,
                                int topK) {
        // 伪代码：在向量检索的 metadata filter 里加 permission 条件
        // 例如只查 level IN ('public','internal') 且 exclude confidential
        Request req = Request.builder()
                .filter("permission IN " + allowedLevels) // 行级权限
                .maxResults(topK)
                .build();
        store.search(req);
        // 真实实现用 pgvector 的 metadata JSONB 过滤或 ES 的 bool filter
    }
}
```

```yaml
# ch07-rag/src/main/resources/auth.yml
rbac:
  roles:
    customer-service:
      visible-kbs: [product, ticket]
      max-clearance: internal
    legal:
      visible-kbs: [legal, contract]
      max-clearance: confidential
    guest:
      visible-kbs: [public-faq]
      max-clearance: public
```

<callout emoji="✅"><p>权限隔离的黄金法则：权限过滤必须发生在"检索阶段"（向量库元数据过滤），而不是"生成之后"才遮挡。如果在生成后才过滤，模型可能已经"看到"了机密内容并泄露在回答里。检索即最小权限，是数据安全的底线。</p></callout>

---

## 四、企业级 RAG 整体架构

把整章所有知识点收口成一个分层架构：

```
┌────────────────────────────────────────────────────────────────┐
│                        企业级 RAG 架构                           │
├────────────────────────────────────────────────────────────────┤
│  接入层   Web/App/IM/API 网关 (Spring Cloud Gateway)            │
│           │  鉴权 / 限流 / 审计                                   │
│           ▼                                                      │
│  路由层   多知识库路由器 (规则 + LLM 兜底)                        │
│           │  角色权限注入 (RBAC 过滤)                             │
│           ▼                                                      │
│  ┌─── 离线索引链路 (异步/定时) ───────────────────────────┐     │
│  │ 文档接入 → Tika/PDFBox/POI/EasyExcel 解析 (lesson06)   │     │
│  │   → 切分 (lesson02) → Embedding (lesson03)             │     │
│  │   → 写入各知识库向量存储 (pgvector/Milvus/ES)          │     │
│  └───────────────────────────────────────────────────────┘     │
│           ▼                                                      │
│  ┌─── 在线检索生成链路 (同步/低延迟) ─────────────────────┐      │
│  │ 查询改写 (lesson07) → 混合检索+rerank (lesson04)       │      │
│  │   → 上下文压缩 (lesson07) → 引用溯源 (lesson07)        │      │
│  │   → LLM 生成 (lesson01)                               │      │
│  └───────────────────────────────────────────────────────┘     │
│           ▼                                                      │
│  观测层   OpenTelemetry 链路追踪 / 评测闭环 (第16章)             │
│           日志/监控/成本统计                                      │
└────────────────────────────────────────────────────────────────┘
```

### 4.1 关键设计决策

| 决策点 | 推荐方案 | 理由 |
|--------|----------|------|
| 向量库 | 中小用 pgvector，超大用 Milvus | 运维成本 vs 规模 |
| 索引更新 | 增量 + 定时全量兜底 | 避免漏更 |
| 路由 | 规则为主 + LLM 兜底 | 成本可控 |
| 权限 | 检索期元数据过滤 | 最小权限 |
| 观测 | OTel + 评测闭环 | 可运维可优化 |

### 4.2 一条完整的 Spring Boot 配置

```yaml
# ch07-rag/src/main/resources/application.yml
spring:
  application:
    name: enterprise-rag
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        options: { model: gpt-4o }
      embedding:
        options: { model: text-embedding-3-small }

rag:
  knowledge-bases:
    - id: product
      vector-store: pgvector
      embedding-model: bge-large-zh
      clearance: internal
    - id: legal
      vector-store: milvus
      embedding-model: bge-large-zh
      clearance: confidential
    - id: public-faq
      vector-store: pgvector
      embedding-model: bge-large-zh
      clearance: public
  router:
    primary: rule
    fallback: llm
    llm-model: gpt-4o-mini
  retrieval:
    hybrid: true
    rerank: { model: bge-reranker-large, finalTopK: 3 }
  security:
    rbac-enabled: true
    filter-at: retrieval
```

---

## 五、企业落地：从 Demo 到生产的鸿沟

```
Demo 版 RAG                        企业级 RAG
─────────────────────────────────────────────────────
单文件、单库                      多库路由、权限隔离
手动跑一次索引                    增量+全量调度、监控
无引用                            强制引用溯源
无评测                            RAGAS 评测闭环(第16章)
无观测                            全链路 OTel 追踪
硬编码 API Key                    配置中心 + 密钥管理
单用户                            多租户、RBAC
```

<callout emoji="💡"><p>很多团队卡在"Demo 跑通了但上不了生产"，本质就是没跨过上面这七道鸿沟。本章七节（lesson01-07）解决的是"技术正确性"，这一节解决的是"工程可落地性"。面试时能把这七道鸿沟讲清楚，你就是"做过生产"的人，不是"跑过 notebook"的人。</p></callout>

---

## 六、踩坑清单

| 坑 | 后果 | 解法 |
|----|------|------|
| 单库混搜 | 跨域污染 + 越权 | 多库路由 |
| 全量 LLM 路由 | 延迟/成本爆炸 | 规则为主+LLM兜底 |
| 权限在生成后过滤 | 机密泄露 | 检索期元数据过滤 |
| 索引不更新 | 知识过期 | 增量+定时全量 |
| 无观测 | 出问题查不出 | OTel+评测闭环 |

---

## 七、多知识库路由的 Spring AI Alibaba 视角

Spring AI Alibaba 的 Graph 工作流可以编排"路由→多库检索→融合"：

```java
// ch07-rag/src/main/java/demo/router/GraphRouter.java
package demo.router;

public class GraphRouter {
    /** 示意：用 Spring AI Alibaba Graph 编排多库路由检索 */
    public static void build() {
        System.out.println("Graph 节点:");
        System.out.println("  [router] 规则+LLM 判断目标库");
        System.out.println("  [retrieve-product] → pgvector");
        System.out.println("  [retrieve-legal]   → milvus");
        System.out.println("  [merge] RRF 融合");
        System.out.println("  [rerank] 精排 Top-3");
    }
}
```

```yaml
# 多知识库配置
knowledge-bases:
  - id: product
    store: pgvector
    embedding: bge-large-zh
    clearance: internal
  - id: legal
    store: milvus
    embedding: bge-large-zh
    clearance: confidential
rbac:
  enforce-at: retrieval   # 最小权限，检索期过滤
```

---

## 八、多租户隔离的实现

企业 SaaS 场景下，不同租户数据必须隔离：

```
多租户隔离三方案：
┌──────────────────────────────────────────┐
│ 1. 物理隔离：每租户独立向量库（最安全最贵）│
│ 2. 逻辑隔离：同库 + tenant_id 元数据过滤  │
│ 3. 混合：大客户物理隔离+小客户逻辑隔离    │
└──────────────────────────────────────────┘
```

<callout emoji="✅"><p>多租户 RAG 几乎都选"逻辑隔离+tenant_id 元数据过滤"，配合检索期强制注入 tenant 条件，兼顾成本与安全。物理隔离留给对合规零容忍的金融/政企大客户。无论哪种，tenant 条件必须"代码层强制注入"，绝不能信任前端传参。</p></callout>

---

## 九、企业级 RAG 的发布与回滚

| 阶段 | 动作 |
|------|------|
| 灰度 | 新索引先 10% 流量，对比效果 |
| 监控 | 检索延迟/召回率/坏答案率 |
| 回滚 | 索引版本一键切回上一版(蓝绿) |
| 审计 | 全链路留痕(谁问了什么、召回了什么) |

## 十、企业级 RAG 的可观测性设计

没有观测的 RAG 是黑盒，出了问题查不出。给一套 OTel 埋点设计：

```java
// ch07-rag/src/main/java/demo/observability/RagTracer.java
package demo.observability;

public class RagTracer {
    /** 记录一次检索的全链路指标 */
    public static void trace(String queryId, long retrieveMs,
                            int candidateCount, int finalCount,
                            double rerankTopScore) {
        System.out.printf("[trace] qid=%s retrieve=%dms " +
            "candidates=%d final=%d topScore=%.3f%n",
            queryId, retrieveMs, candidateCount, finalCount, rerankTopScore);
        // 真实接入 OpenTelemetry + Prometheus（见第十七章部署）
    }
}
```

```
可观测指标清单：
  - 检索延迟 P50/P95/P99
  - 候选数 / 最终数（压缩比）
  - rerank 最高分（质量信号）
  - 答案 Faithfulness（LLM-as-Judge，第十六章）
  - 坏答案率（用户反馈/评测）
```

<callout emoji="💡"><p>RAG 上线只是开始，可观测才是能长期跑稳的关键。没有这些指标，你不知道"为什么今天答案变差了"是索引过期、embedding 漂移还是 rerank 模型更新。把观测当成 RAG 的一部分，不是事后补丁。</p></callout>

---

## 十一、成本治理（接第十章/第十六章）

企业 RAG 的成本大头在 LLM 调用。给你一张降本清单：

| 手段 | 节省点 |
|------|--------|
| 查询改写用 gpt-4o-mini | 省扩展调用成本 |
| rerank 用 base 模型 | 省精排成本 |
| embedding 缓存 | 省重复向量化 |
| 按需上 GraphRAG/Agentic | 省重计算场景 |
| 模型路由(大模型仅生成) | 省整体 token |

---

## 十二、最终检查清单

| 维度 | 是否具备 |
|------|----------|
| 多知识库路由 | ☐ |
| RBAC 权限隔离 | ☐ |
| 多租户隔离 | ☐ |
| 离线/在线解耦 | ☐ |
| 可观测性 | ☐ |
| 成本治理 | ☐ |

## 小结

这节课收口了整章的"工程落地"维度：

- **多知识库路由**：规则路由（快、稳、零成本）为主，LLM 路由兜底长尾，路由后再分别检索融合。
- **权限隔离**：RBAC + 检索期元数据过滤（最小权限），机密文档绝不能等到生成后才遮挡。
- **企业级架构**：接入/路由/离线索引/在线检索生成/观测五层分离，离线异步、在线低延迟。
- **Demo→生产七道鸿沟**：多库、调度、引用、评测、观测、密钥、多租户——跨过去才算"能上线"。
- 第七章（RAG 全链路）到此完整：从原理、处理、Embedding、检索优化，到 GraphRAG、文档解析、查询改写压缩、企业架构。

下一章进入 Agent 的认知架构（第八章），看 Agent "脑子"是怎么运作的——规划、工具、反思、学习。

---

## 延伸阅读 / 引用文档

1. LangChain 多检索器路由（MultiRetrievalQA）: https://python.langchain.com/docs/integrations/retrievers/multi_query/
2. pgvector 行级权限与 RLS: https://www.postgresql.org/docs/current/ddl-rowsecurity.html
3. Spring Cloud Gateway（接入层）: https://spring.io/projects/spring-cloud-gateway
4. OpenTelemetry 官方文档: https://opentelemetry.io/docs/
5. Milvus 多租户架构: https://milvus.io/docs/multi_tenancy.md
6. RAGAS 评测（评测闭环基础，第16章详讲）: https://docs.ragas.io/

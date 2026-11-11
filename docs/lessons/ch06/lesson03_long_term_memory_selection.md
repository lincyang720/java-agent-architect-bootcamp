# 第六章第3节：长期记忆技术选型——向量数据库原理（PgVector、ChromaDB、Milvus）

大家好，我是秋天。

上节课我们解决了"短期记忆怎么管窗口"。这节课进入长期记忆的"仓库"选型——**向量数据库（Vector Database）**。为什么长期记忆非它不可？因为长期记忆的核心是"语义检索"：用户说"上次退货那事"，你要从一堆记忆里找出"语义相关"的那条，而不是"精确等于"的那条。这事传统数据库干不了，向量库能干。

学完这节课你能：

1. 理解向量数据库的核心原理——向量化 + 近似最近邻（ANN）；
2. 在 PgVector、ChromaDB、Milvus 三者间做选型决策；
3. 用 Java（Spring AI VectorStore / LangChain4j）把这三种库接进 Agent；
4. 看清"该自建还是该用托管"的工程权衡。

这是招聘 JD 里"向量库（Milvus / pgvector / RedisSearch / ES kNN）"要求的正面战场，必须拿下。

---

## 一、向量数据库在记忆系统里的位置

先把它放到全局里。长期记忆的写入和检索流程：

```text
长期记忆写入：
  记忆文本 ──▶ Embedding 模型 ──▶ 向量(浮点数组) ──▶ 存入向量库
                                            + 原文/元数据

长期记忆检索：
  查询文本 ──▶ Embedding 模型 ──▶ 查询向量 ──▶ 向量库 ANN 检索
                                            ──▶ TopK 相似记忆(带原文)
```

<callout emoji="💡"><p>关键点：向量库存的不是文本，是"文本经 Embedding 后的向量"。检索靠"向量之间的距离"衡量语义相似度。所以向量库 = 存储 + 高效的相似检索（ANN）。lesson04 会讲距离算法（余弦/欧氏），这节课先讲"仓库本身"。</p></callout>

---

## 二、核心原理：从文本到向量，再到 ANN

### 2.1 向量化与高维空间

Embedding 把一段文本映射成一个高维向量（如 768 维、1536 维）。语义相近的文本，向量在空间里距离近；语义无关的距离远。这就是"语义检索"的数学基础。

```text
"我要退上个月买的鞋"   ──▶ [0.12, -0.33, ..., 0.87]   (1536维)
"退货流程怎么走"       ──▶ [0.11, -0.31, ..., 0.85]   (接近上面)
"今天天气真好"         ──▶ [0.92,  0.05, ..., -0.21]   (离得远)
```

### 2.2 为什么需要 ANN（近似最近邻）

 brute-force（暴力算每对距离）在百万级向量上太慢。向量库用 **ANN 索引**（HNSW、IVF 等）做近似检索——牺牲一点点精度，换几百倍的查询速度。

```text
暴力检索：O(N) 每查一次扫描全量  → 慢但准
ANN（HNSW/IVF）：建索引后 O(log N) → 快，召回率 95%+
```

<callout emoji="✅"><p>面试考点："为什么不用 SQL LIKE 做记忆检索？"答：LIKE 是字符匹配，不懂语义；"退货"和"退换"字符不同但语义近，LIKE 查不出。向量相似度才懂语义。这是向量库存在的根本理由。</p></callout>

---

## 三、三选一：PgVector / ChromaDB / Milvus

三个都是主流，但定位不同。我给你一张对比表：

```text
┌────────────┬─────────────────────┬──────────────────┬────────────────────┐
│ 维度       │ PgVector            │ ChromaDB         │ Milvus             │
├────────────┼─────────────────────┼──────────────────┼────────────────────┤
│ 形态       │ PostgreSQL 扩展     │ 轻量独立服务     │ 分布式向量专用     │
│ 上手成本   │ 低（已有 PG）       │ 最低（pip 起）   │ 高（组件多）       │
│ 规模       │ 千万级以内够用      │ 百万~千万        │ 十亿级分布式       │
│ 运维       │ 复用现有 PG 运维    │ 简单             │ 复杂（etcd/minio） │
│ 适合       │ 已有 PG 的团队      │ 原型/中小应用    │ 大模型平台/超大规模│
│ 混合查询   │ 强（SQL+向量一体）  │ 中               │ 中（需配合）       │
└────────────┴─────────────────────┴──────────────────┴────────────────────┘
```

### 3.1 选型决策树

```text
你已经有 PostgreSQL 且在用？
   ├─ 是 → PgVector（零新增基础设施，SQL+向量混合查询最顺）
   └─ 否 → 规模多大？
              ├─ 中小 / 快速验证 → ChromaDB（几分钟起来）
              └─ 超大规模 / 多租户平台 → Milvus（分布式扛量）
```

<callout emoji="⚠️"><p>现实提醒：别一上来就 Milvus。我见过创业团队为了"显得专业"上 Milvus，结果 etcd/minio 运维把自己搞死，而数据才 50 万条——PgVector 完全够。选型看"数据规模和团队现状"，不是看"哪个最牛"。</p></callout>

---

## 四、Java 集成：统一用 Spring AI VectorStore

Spring AI 提供了统一的 `VectorStore` 抽象，背后可以切换 PgVector / Milvus / Chroma 等实现。这是 Java 工程师最顺手的入口。

### 4.1 PgVector 集成（Java + Spring AI）

依赖：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-pgvector-store-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
</dependency>
```

配置（application.yml）：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/agent_mem
    username: ${DB_USER}
    password: ${DB_PASSWORD}
  ai:
    vectorstore:
      pgvector:
        index-type: HNSW        # 用 HNSW 索引，查询快
        distance-type: COSINE   # 余弦距离
        dimensions: 1536        # 与 Embedding 模型维度一致
        initialize-schema: true # 自动建表
```

写入与检索代码：

```java
package com.camp.memory;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 用 Spring AI VectorStore 操作 PgVector。
 * 长期记忆的"存"与"取"都走这里。
 */
@Component
public class PgVectorMemory {

    @Autowired
    private VectorStore vectorStore;

    /** 存一条长期记忆（自动 Embedding + 入库） */
    public void remember(String memoryText, String userId, String category) {
        Document doc = new Document(
                memoryText,
                Map.of("user_id", userId, "category", category, "ts", String.valueOf(System.currentTimeMillis()))
        );
        vectorStore.add(List.of(doc));
    }

    /** 语义检索：找出与 query 最相关的 TopK 记忆（带用户过滤） */
    public List<Document> recall(String query, String userId, int topK) {
        SearchRequest req = SearchRequest.builder()
                .query(query)
                .topK(topK)
                // 元数据过滤：只查该用户的记忆（多租户隔离）
                .filterExpression("user_id == '" + userId + "'")
                .build();
        return vectorStore.similaritySearch(req);
    }
}
```

<callout emoji="💡"><p>多租户隔离就藏在这行 filterExpression 里：每次检索都按 `user_id` 过滤，A 用户的记忆绝不会泄露给 B。这是长期记忆上生产的硬要求（对应第十二章多租户隔离）。</p></callout>

### 4.2 ChromaDB 集成（Java + Spring AI）

ChromaDB 是独立服务，Java 通过 Spring AI 的 Chroma 起步器对接。

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-chroma-store-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

```yaml
spring:
  ai:
    vectorstore:
      chroma:
        client:
          host: localhost
          port: 8000
        collection-name: agent_long_term_memory
        initialize-schema: true
```

```java
package com.camp.memory;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ChromaMemory {

    @Autowired
    private VectorStore vectorStore;

    public void remember(String text, String userId) {
        vectorStore.add(List.of(new Document(text,
                Map.of("user_id", userId))));
    }

    public List<Document> recall(String query, String userId, int topK) {
        return vectorStore.similaritySearch(
                org.springframework.ai.vectorstore.SearchRequest.builder()
                        .query(query).topK(topK)
                        .filterExpression("user_id == '" + userId + "'")
                        .build());
    }
}
```

<callout emoji="✅"><p>注意一个爽点：切 PgVector → Chroma，业务代码几乎不动，因为都面向 `VectorStore` 抽象。这就是 Spring AI "统一抽象层"的价值——换库不换代码。和第一章讲的"Spring AI 是抽象层"呼应上了。</p></callout>

### 4.3 Milvus 集成（Java + Spring AI）

Milvus 适合超大规模。Java 用 `spring-ai-milvus-store` 起步器。

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-milvus-store-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

```yaml
spring:
  ai:
    vectorstore:
      milvus:
        client:
          host: milvus.example.com
          port: 19530
        databaseName: agent_mem
        collectionName: long_term
        initialize-schema: true
        indexType: HNSW
        metricType: COSINE
        dimension: 1536
```

```java
package com.camp.memory;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class MilvusMemory {

    @Autowired
    private VectorStore vectorStore;

    public void remember(String text, String userId, String tenant) {
        vectorStore.add(List.of(new Document(text,
                Map.of("user_id", userId, "tenant", tenant))));
    }

    public List<Document> recall(String query, String userId, int topK) {
        return vectorStore.similaritySearch(
                org.springframework.ai.vectorstore.SearchRequest.builder()
                        .query(query).topK(topK)
                        .filterExpression("user_id == '" + userId + "'")
                        .build());
    }
}
```

<callout emoji="⚠️"><p>Milvus 的运维门槛是真的高：etcd、MinIO、多节点。如果你们没有专职基础设施团队，别轻易上。数据量没到亿级，PgVector 或 Chroma 更划算。技术选型要算"总拥有成本（TCO）"，不只看性能上限。</p></callout>

---

## 五、LangChain4j 路线（备选）

如果你项目用的是 LangChain4j 而非 Spring AI，同样能接这三个库。以 PgVector 为例：

```java
package com.camp.memory;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStore;

import java.util.List;

/**
 * LangChain4j 路线：PgVector 作为 EmbeddingStore。
 */
public class Lc4jPgVectorMemory {

    public EmbeddingStore<TextSegment> build() {
        return PgVectorEmbeddingStore.builder()
                .host("localhost")
                .port(5432)
                .database("agent_mem")
                .user(System.getenv("DB_USER"))
                .password(System.getenv("DB_PASSWORD"))
                .table("langchain_memory")
                .dimension(1536)
                .build();
    }

    // 存入：embeddingStore.add(embedding, segment)
    // 检索：embeddingStore.findRelevant(queryEmbedding, maxResults)
}
```

<callout emoji="💡"><p>两条路线怎么选？项目已是 Spring 体系 → Spring AI VectorStore（本课主推）；项目是 LangChain4j 体系 → 用它的 EmbeddingStore。能力等价，只是 API 风格不同。本课程第二章讲 LangChain4j、第三章讲 Spring AI，这里正好把两家的向量接口对照起来。</p></callout>

---

## 六、索引类型与参数：别用默认就完事

向量库性能很大程度取决于索引。常用两类：

```text
HNSW（分层可导航小世界）：
  查询快、内存占用高、构建稍慢
  适合：读多写少、查询延迟敏感（线上 Agent 检索）

IVF（倒排文件）：
  内存低、查询稍慢、可分布式
  适合：超大库、成本敏感

PQ（乘积量化）：压缩向量，省内存，略损精度
```

```yaml
# PgVector 选 HNSW + 余弦（线上 Agent 检索的典型配置）
spring:
  ai:
    vectorstore:
      pgvector:
        index-type: HNSW
        distance-type: COSINE
```

<callout emoji="✅"><p>经验值：线上 Agent 的长期记忆检索，延迟敏感，优先 HNSW。维度要和 Embedding 模型严格一致（OpenAI text-embedding-3-small=1536，bge-m3=1024 等），配错维度会直接建表失败或检索乱套。</p></callout>

---

## 七、企业落地：记忆仓库的部署形态

给你一个真实落地的形态对比：

```text
中小团队（数据<千万）：
  PostgreSQL + PgVector，复用现有库，SQL+向量混合查询
  运维成本 ≈ 0（已有 PG）

成长团队（数据千万~亿）：
  ChromaDB 独立服务 / 或 PgVector 上云托管
  简单容器化即可

平台级（数据>亿 / 多租户 SaaS）：
  Milvus 分布式集群（或全托管 Zilliz Cloud）
  需要专职 infra
```

<callout emoji="⚠️"><p>合规提醒：长期记忆里很可能存"用户隐私"（身份、偏好、对话）。上云向量库要考虑数据驻留区域、加密（传输+静态）、访问审计。金融/医疗尤其要严格。这又回到"数据能不能出这个域"的老问题。</p></callout>

---

## 八、招聘能力映射

这节课是 JD 里向量库要求的直接覆盖：

- "**熟悉向量数据库（Milvus / pgvector / RedisSearch / ES kNN）**" → 三选一全讲。
- "**RAG / Embedding 检索**" → 向量库是 RAG 的存储底座（第七章）。
- "**多租户数据隔离**" → filterExpression 按 user_id 过滤。

<callout emoji="💡"><p>面试金句："向量库选型看三件事——数据规模、团队现状、混合查询需求。已有 PG 用 PgVector，快速验证用 Chroma，超大规模用 Milvus。维度必须和 Embedding 模型对齐，线上优先 HNSW。换库靠 Spring AI 的 VectorStore 抽象不动业务代码。" 这段话基本把向量库面试题答穿了。</p></callout>

---

## 八、向量库性能调优：索引与量化

选型只是第一步，调优决定线上体验。两个关键旋钮：

```text
HNSW 调优参数：
  M（每层节点连接数）：越大召回越高、内存越贵（常用 8~64）
  efSearch（查询时遍历宽度）：越大越准、越慢
  → 线上调 efSearch 找"精度/延迟"甜区

量化（Quantization）：
  PQ（乘积量化）：把向量压缩存储，省 4~32× 内存，略损精度
  Scalar Quantization：更轻量，社区常用
  → 亿级向量必开量化，否则内存爆炸
```

```yaml
# PgVector HNSW 调优示意
spring:
  ai:
    vectorstore:
      pgvector:
        index-type: HNSW
        hnsw-m: 16            # 连接数
        hnsw-ef-search: 40    # 查询宽度
        distance-type: COSINE
```

<callout emoji="⚠️><p>调优纪律：召回率和延迟是跷跷板。先定"可接受的延迟上限"，再调 efSearch 把召回率拉到最高。别盲目追求 100% 召回——多花 200ms 只提升 1% 召回，不划算。</p></callout>

## 九、混合检索：向量 + 关键词（Hybrid Search）

纯向量检索有个弱点：对"精确词"（订单号、专有名词）不敏感。混合检索 = 向量语义 + 关键词（BM25）双路召回再融合，是生产标配。

```text
混合检索流程：
  查询 ─┬─▶ 向量检索（语义相关）──┐
        │                         ├─▶ 融合排序（RRF/加权）→ TopK
        └─▶ 关键词检索（精确匹配）─┘
```

```java
package com.camp.memory;

import java.util.*;
import java.util.stream.*;

/**
 * 混合检索融合：RRF（Reciprocal Rank Fusion）示意。
 * 两路召回各给排名，融合后取综合高分。
 */
public class HybridFusion {

    /** RRF 分数 = Σ 1/(k + rank_i)，k=60 常用 */
    public Map<String, Double> rrf(List<String> vectorRank,
                                    List<String> keywordRank) {
        Map<String, Double> score = new HashMap<>();
        int k = 60;
        add(score, vectorRank, k);
        add(score, keywordRank, k);
        return score.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .collect(LinkedHashMap::new,
                        (m, e) -> m.put(e.getKey(), e.getValue()),
                        Map::putAll);
    }

    private void add(Map<String, Double> score, List<String> rank, int k) {
        for (int i = 0; i < rank.size(); i++) {
            score.merge(rank.get(i), 1.0 / (k + i + 1), Double::sum);
        }
    }
}
```

<callout emoji="💡"><p>混合检索是 RAG（第七章）和记忆检索的共同最佳实践。纯向量在"语义相关"上强，但在"精确匹配专有名词"上弱。两路融合取长补短，召回质量明显提升。面试被问"检索不准怎么办"，先答混合检索。</p></callout>

## 十、多租户向量隔离架构

企业级记忆必须隔离租户。三种隔离粒度：

```text
多租户向量隔离：
  ① 物理隔离：每个租户独立 collection/表（最安全，成本高）
  ② 逻辑隔离：同一 collection + tenant_id 过滤（lesson04 的 filterExpression）
  ③ 行级隔离：数据库行级策略
  → 中小用②，强合规用①
```

<callout emoji="✅"><p>逻辑隔离（filterExpression by tenant_id）是性价比之选，但前提是"过滤绝不能被绕过"——所有检索路径都要强制带 tenant 过滤，否则就是越权。安全审计要覆盖这条路径。</p></callout>

## 十一、避坑清单

```text
向量库选型与调优避坑：
❌ 数据<千万却上 Milvus（运维反噬）
❌ 维度与 Embedding 模型不一致（检索乱套）
❌ 纯向量检索忽视精确词（混合检索补齐）
❌ 不设 efSearch/量化（亿级内存爆）
❌ 多租户靠"约定"不带过滤（越权事故）
✅ 先按规模选型，再调索引/量化
✅ 混合检索做生产标配
✅ 隔离靠硬性过滤，非软约定
```

<callout emoji="⚠️"><p>向量库是长期记忆的"地基"，地基选错/调错，上面全是坑。这节课的选型决策树 + 调优旋钮 + 混合检索 + 隔离架构，是 JD 里"向量库（Milvus/pgvector）"要求的完整覆盖。</p></callout>

## 十二、向量库运维与监控

上线后向量库要监控，否则悄悄劣化。

```text
向量库监控指标：
  ├─ 索引大小 / 向量条数（增长趋势）
  ├─ 查询 P99 延迟（超阈值告警）
  ├─ 召回率（定期用标注集测 Recall@K）
  ├─ 内存占用（量化是否生效）
  └─ 写入积压（批量导入是否卡住）
```

<callout emoji="✅"><p>运维提醒：向量库索引是"静态"的——新数据写入后，HNSW 索引可能不会立即重构，导致新记忆检索不到。生产要定期"重建索引"或选用"支持增量索引"的方案。这是上线后才暴露的坑。</p></callout>

## 十三、云托管 vs 自建的决策

```text
云托管（Zilliz Cloud / Pinecone / pgvector on Cloud SQL）：
  优点：零运维、弹性、SLA
  缺点：数据出境、按量计费、绑定
自建（Milvus 集群 / 自管 PG+PgVector）：
  优点：数据自控、成本可控
  缺点：运维重、需 infra 团队
```

<callout emoji="⚠️"><p>决策框架：没专职 infra → 云托管；强合规/数据敏感 → 自建或私有云；介于之间 → 用云上托管 PG+PgVector（运维最轻）。和第五章"云锁定"决策一脉相承。</p></callout>

## 十四、选型决策速查与常见故障

快速决策表 + 故障速查，方便你落地时对照。

```text
决策速查：
  有 PG 且数据<千万       → PgVector
  快速验证/数据中小        → ChromaDB
  超大规模/平台级          → Milvus
  强合规/数据不出网        → 本地向量库（PG+本地Embedding）

故障速查：
  检索全空       → 维度不匹配 / Embedding 模型不一致
  召回都是旧的    → 索引未重建（增量写入未生效）
  跨租户串数据    → filterExpression 未强制
  内存爆         → 未开量化 / M 参数过大
```

<callout emoji="✅"><p>把这张表存进你的工程笔记。向量库的坑就这几种，遇到对号入座，省下大量排查时间。选型纠结时，先用"有没有 PG"和"数据规模"两条做初筛，基本不会错。</p></callout>

## 十五、向量库趋势与选型前瞻

给你几个值得关注的方向，避免选型很快过时。

```text
向量库趋势：
  ├─ 云原生托管成熟（Zilliz/Pinecone 持续迭代）
  ├─ 数据库原生向量（PG/pgvector、MySQL、ES 都加向量）
  ├─ 稀疏+稠密混合索引（BM25 + 向量一体）
  ├─ 标量量化默认开启（降本）
  └─ 多模态向量（图/文/音统一检索）
```

<callout emoji="✅"><p>趋势判断：未来"向量能力"会下沉成数据库的标配，而非独立中间件。所以 lesson03 重点讲 PgVector（用现有 PG）是面向未来的——你不用为向量单独养一套 Milvus，除非真到了超大规模。</p></callout>

## 十六、生产 Checklist 与故障复盘

给你一份向量库上生产的 Checklist，照着过：

```text
向量库上生产线·终检：
[ ] 维度与 Embedding 模型严格对齐
[ ] 索引类型/参数按规模调好（HNSW + 合适 efSearch）
[ ] 海量数据已开量化（PQ/SQ）
[ ] 混合检索（向量+关键词）已接
[ ] 多租户强制 user_id/tenant 过滤
[ ] 增量写入后索引重建策略明确
[ ] 监控：延迟 P99 / 召回率 / 内存 / 写入积压
[ ] 备份与恢复演练过
[ ] 容量水位告警 + 扩容预案
```

<callout emoji="⚠️><p>故障复盘习惯：每次检索出问题，记录"现象→根因→修复"，沉淀成团队知识库。三个最常见根因：维度不匹配、增量索引未重建、租户过滤漏写。把这张表和新故障都存进团队 wiki。</p></callout>

## 小结

这节课把长期记忆的"仓库"选型讲透了：

1. **为什么向量库**：长期记忆靠"语义检索"，传统 SQL/LIKE 不懂语义；向量库 = 向量存储 + ANN 高效相似检索。
2. **核心原理**：Embedding 把文本映射高维向量，语义近则距离近；ANN（HNSW/IVF）用近似换速度。
3. **三选一**：PgVector（已有 PG、混合查询）、ChromaDB（轻量快速验证）、Milvus（超大规模分布式）；选型看规模和团队，别盲目上 Milvus。
4. **Java 集成**：Spring AI `VectorStore` 统一抽象，PgVector/Chroma/Milvus 切换几乎不动业务代码；元数据 filterExpression 实现多租户隔离。
5. **索引与维度**：线上优先 HNSW，维度必须和 Embedding 模型严格一致。
6. **落地形态**：按数据规模选部署形态，同时注意隐私合规（驻留/加密/审计）。

记住一句话：**向量库选型不是"选最强的"，而是"选和你的数据规模、团队现状、混合查询需求最匹配的"——维度对齐、HNSW 优先、隔离靠元数据过滤。**

---

## 延伸阅读 / 引用文档

1. PgVector 官方 GitHub：<https://github.com/pgvector/pgvector>
2. ChromaDB 文档：<https://docs.trychroma.com/>
3. Milvus 文档：<https://milvus.io/docs>
4. Spring AI VectorStore 抽象：<https://docs.spring.io/spring-ai/reference/1.0/api/vectordbs.html>
5. LangChain4j EmbeddingStore：<https://docs.langchain4j.dev/integrations/embedding-stores/
6. HNSW 论文（近似最近邻索引）：<https://arxiv.org/abs/1603.09320>

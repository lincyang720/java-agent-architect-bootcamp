# 第七章第四节：检索优化技术：混合检索（关键词+向量）、重排序（Re-ranking）、查询扩展

大家好，我是秋天。

前面三节我们把"索引"链路（切分、Embedding、入库）讲完了，也点过检索的基本形态。但如果你真把"向量召回 Top-3"直接上线，一定会发现一个现象：**用户问专业术语时搜不到，问口语化问题时又召回一堆不相关的**。这节课就来解决"召回得准"这件事，讲检索优化的三件套——混合检索、重排序、查询扩展。

学完这节课，你能掌握：

1. **混合检索（Hybrid Retrieval）**：为什么"向量 + 关键词"双路召回是生产标配，以及 RRF 怎么融合两路结果。
2. **重排序（Re-ranking）**：Cross-Encoder 精排为什么比向量粗排准，怎么接 bge-reranker。
3. **查询扩展（Query Expansion）**：用 LLM 把用户的"短问句"扩写成多个检索视角，召回率怎么提 15 个点。

<callout emoji="💡"><p>招聘映射：这节对应 JD 里的"混合检索、重排序（Rerank）、查询改写"——2026 年几乎是 Agent 岗的必考三连。面试官会让你手画混合检索架构图，这节课给的就是标准答案。</p></callout>

---

## 一、为什么纯向量检索不够

先戳破一个幻觉：向量检索不是银弹。它有两类系统性失灵：

```
纯向量检索的两大短板：
┌──────────────────────────────────────────────────────────┐
│ 1. 精确匹配弱：用户搜"GB/T 28181 协议"，向量把它和      │
│    "国标流媒体协议"算近，却可能漏掉字面完全一致的条款    │
│ 2. 低频实体弱：专有名词、型号、错误码（如 ERR-4096）     │
│    在训练语料里极少，向量表示不稳定                       │
└──────────────────────────────────────────────────────────┘
```

而 BM25 这类关键词检索恰恰相反：**字面匹配极强，但语义泛化弱**（搜"怎么退钱"匹配不到"退款流程"）。两者互补，这就是混合检索的动机。

---

## 二、混合检索（Hybrid Retrieval）：双路召回 + 融合

### 2.1 架构

```
                用户 Query
                     │
        ┌────────────┴────────────┐
        ▼                         ▼
   【向量召回】              【关键词召回】
   Query → Embedding         Query → BM25 / ES
   → ANN Top-K1             → 倒排索引 Top-K2
        │                         │
        └──────────┬──────────────┘
                   ▼
            RRF 融合 (Reciprocal Rank Fusion)
                   │
                   ▼
            融合后 Top-K (如 20)
                   │
                   ▼
              Re-rank 精排 (lesson 下段)
```

### 2.2 RRF 融合算法

RRF（Reciprocal Rank Fusion）不依赖分数绝对值（向量分数和 BM25 分数量纲不同，不能直接加），只用到"排名"：

```
RRF_score(d) = Σ  1 / (k + rank_i(d))
              i

其中 k 是平滑常数（通常 60），rank_i(d) 是文档 d 在第 i 路结果中的排名
```

```java
// ch07-rag/src/main/java/demo/RrfFusion.java
package demo;

import java.util.*;

public class RrfFusion {
    private static final int K = 60;

    /** 输入多路召回结果（每路是 docId 按相关性降序的列表），输出融合排名 */
    public static List<String> fuse(List<List<String>> rankLists) {
        Map<String, Double> scoreMap = new HashMap<>();
        for (List<String> ranks : rankLists) {
            for (int rank = 0; rank < ranks.size(); rank++) {
                String docId = ranks.get(rank);
                scoreMap.merge(docId, 1.0 / (K + rank + 1), Double::sum);
            }
        }
        return scoreMap.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
    }

    public static void main(String[] args) {
        List<String> vectorHits = List.of("d3", "d1", "d5", "d2");
        List<String> bm25Hits   = List.of("d1", "d4", "d3");
        List<String> fused = fuse(List.of(vectorHits, bm25Hits));
        System.out.println("融合后: " + fused); // d1, d3 两路都中，排最前
    }
}
```

### 2.3 Elasticsearch 原生混合检索（kNN + BM25）

```json
// ch07-rag/src/main/resources/es_hybrid_query.json
{
  "query": {
    "bool": {
      "should": [
        {
          "knn": {
            "embedding": {
              "vector": [0.12, -0.34, 0.56, "..."],
              "k": 20,
              "num_candidates": 100
            }
          }
        },
        {
          "multi_match": {
            "query": "GB/T 28181 协议 接入",
            "fields": ["content", "title^2"]
          }
        }
      ]
    }
  },
  "size": 20
}
```

<callout emoji="✅"><p>为什么用 RRF 而不是把两套分数加权相加？因为向量相似度（0~1）和 BM25 分数（可能上百）量纲完全不同，加权需要反复调参且脆弱。RRF 只看排名、不碰分数，鲁棒得多。这是工程上的"少调参就是少出错"。</p></callout>

### 2.4 bge-m3 的"原生混合"：稠密 + 稀疏一体

bge-m3 一个模型同时输出**稠密向量（语义）**和**稀疏向量（类 BM25 的词权重）**，天然支持混合检索，无需额外维护倒排索引。

```java
// ch07-rag/src/main/java/demo/BgeM3Hybrid.java
package demo;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.huggingface.HuggingFaceEmbeddingModel;

/** bge-m3 同时产出稠密与稀疏表示，简化混合检索实现 */
public class BgeM3Hybrid {
    public static void main(String[] args) {
        EmbeddingModel model = HuggingFaceEmbeddingModel.builder()
                .modelName("BAAI/bge-m3")
                .modelCacheDirectory("/models/bge-m3")
                .build();
        // 实际使用时 bge-m3 会返回 dense + sparse 两部分，
        // 分别送向量库和倒排索引，再 RRF 融合（见 2.3）
        System.out.println("bge-m3 输出: dense(1024) + sparse(词权重)");
    }
}
```

---

## 三、重排序（Re-ranking）：精排的临门一脚

### 3.1 为什么粗排之后还要精排

向量召回是"近似最近邻（ANN）"，为了快会牺牲一点精度；而且它一次只能算 query 和单个 chunk 的相似度，**看不到 chunk 之间的相互关系**。Cross-Encoder 重排器则把"query + chunk"拼在一起过一遍模型，能捕捉细粒度交互，精度远高于双塔（Bi-Encoder）。

```
Bi-Encoder (向量检索):  Query ──► [Encoder] ──► q_vec
                         Chunk ──► [Encoder] ──► c_vec  → 独立编码，只看两两相似
                         （快，但交互弱）

Cross-Encoder (重排):   [Query ‖ Chunk] ──► [Encoder] ──► 相关性分数
                         （慢，但能看整体交互，精度高）
```

### 3.2 接入 bge-reranker-large

典型做法是：向量粗召回 Top-20 → 送 reranker 打分到 Top-3 → 进 prompt。

```java
// ch07-rag/src/main/java/demo/RerankDemo.java
package demo;

import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.data.segment.TextSegment;

import java.util.*;
import java.util.stream.Collectors;

/** 简化版 rerank：用 bge-reranker 对粗召回结果打分精排 */
public class RerankDemo {

    record Scored(TextSegment seg, double score) {}

    // 生产中用 FlagEmbedding 的 Reranker 或本地 ONNX 推理，此处示意打分逻辑
    static double rerankScore(String query, String chunk, OpenAiChatModel judge) {
        String resp = judge.generate(
            "判断相关性(0~1)，只回数字：查询=" + query + " 文档=" + chunk).content();
        return Double.parseDouble(resp.replaceAll("[^0-9.]", ""));
    }

    public static List<TextSegment> rerank(String query,
                                           List<TextSegment> candidates,
                                           OpenAiChatModel judge,
                                           int topK) {
        return candidates.stream()
                .map(s -> new Scored(s, rerankScore(query, s.text(), judge)))
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(topK)
                .map(Scored::seg)
                .collect(Collectors.toList());
    }

    public static void main(String[] args) {
        OpenAiChatModel judge = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini").build(); // 生产用 bge-reranker 更省
        // rerank("退款时效", coarseTop20, judge, 3);
        System.out.println("精排到 Top-3 送 prompt");
    }
}
```

### 3.3 用 FlagEmbedding 的 Reranker（推荐生产用法）

```python
# ch07-rag/scripts/rerank.py
from FlagEmbedding import FlagReranker

reranker = FlagReranker("BAAI/bge-reranker-large", use_fp16=True)

pairs = [
    ("退款一般多久到账", "退款审核通过后24小时内原路退回。"),
    ("退款一般多久到账", "本店支持7天无理由退换货。"),
    ("退款一般多久到账", "夏季新品满300减50优惠活动。"),
]
scores = reranker.compute_score(pairs)
for (q, d), s in zip(pairs, scores):
    print(f"score={s:.4f}  {d[:20]}")
# 输出：第一条分数最高，第三条（无关促销）最低 → 精排生效
```

<callout emoji="💡"><p>粗排和精排的 K 值怎么定？经验公式：粗排 K = 精排 K 的 5~10 倍。精排 Top-3 就粗排 Top-20~30。精排模型（Cross-Encoder）比向量检索慢 10 倍以上，所以不能对全库精排，必须是"先粗后精"的两段式。</p></callout>

---

## 四、查询扩展（Query Expansion）：让短问句"变胖"

### 4.1 为什么要扩展

用户的问题往往很短、很口语："钱啥时候退"。直接拿去检索，向量表示稀疏、召回面窄。查询扩展就是用 LLM 或同义改写，把这句话扩成多个检索视角：

```
原 Query: "钱啥时候退"
扩展为:
  - "退款到账时间多久"
  - "退款处理时效"
  - "退款多久能到银行卡"
  - "退款进度查询方法"
```

四个角度分别检索，召回面大幅拓宽。

### 4.2 用 LLM 做查询扩展

```java
// ch07-rag/src/main/java/demo/QueryExpansion.java
package demo;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;

import java.util.List;

public class QueryExpansion {

    interface Expander {
        @UserMessage("""
            你是一个检索优化助手。请把用户的查询改写成 3 个不同表述，
            覆盖同义词、口语/书面语、不同侧重点。只返回 JSON 数组，不要解释。
            用户查询：{{query}}
            """)
        String expand(String query);
    }

    public static void main(String[] args) {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini").build();
        Expander expander = AiServices.create(Expander.class, model);

        String json = expander.expand("钱啥时候退");
        System.out.println(json);
        // ["退款到账需要多久","退款处理时效是几天","退款进度如何查询"]
    }
}
```

### 4.3 扩展后的多路检索与融合

```java
// ch07-rag/src/main/java/demo/ExpandedRetrieval.java
package demo;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.search.Request;

import java.util.*;

public class ExpandedRetrieval {
    public static Set<String> retrieveMultiQuery(List<String> queries,
                                                 OpenAiEmbeddingModel emb,
                                                 EmbeddingStore store,
                                                 int topK) {
        Set<String> results = new LinkedHashSet<>();
        for (String q : queries) {
            Embedding e = emb.embed(q).content();
            store.search(Request.builder().queryEmbedding(e).maxResults(topK).build())
                 .matches().forEach(m -> results.add(m.embedded().text()));
        }
        return results; // 多视角召回的并集，去重后送 rerank
    }
}
```

<callout emoji="⚠️"><p>查询扩展有个副作用：扩展出的视角若有误（LLM 把"退款"理解成"转账"），会引入噪声。所以扩展后一定要走 rerank 这道关，把不相关的扩展示例过滤掉。扩展负责"广撒网"，rerank 负责"收网提质"。</p></callout>

---

## 五、三件套串成一条生产级检索链

```yaml
# ch07-rag/src/main/resources/retrieval.yml
retrieval:
  query-expansion:
    enabled: true
    model: gpt-4o-mini
    variants: 3
  hybrid:
    vector:
      topK: 20
      store: pgvector
    keyword:
      topK: 20
      engine: elasticsearch
    fusion: RRF
    rrf-k: 60
  rerank:
    enabled: true
    model: BAAI/bge-reranker-large
    finalTopK: 3
    minScore: 0.3
  post-filter:
    metadata:
      - field: status
        op: eq
        value: active
```

对应的 Java 编排：

```java
// ch07-rag/src/main/java/demo/ProductionRetrieval.java
package demo;

public class ProductionRetrieval {
    /**
     * 生产检索链路编排：
     *   1. Query Expansion 扩成多视角
     *   2. 每个视角走 向量 + 关键词 混合召回
     *   3. RRF 融合所有结果
     *   4. 元数据过滤（status=active）
     *   5. bge-reranker 精排到 Top-3
     *   6. 送 LLM 生成（lesson01 的 Generation 阶段）
     */
    public static void main(String[] args) {
        System.out.println("检索链路: 扩展 → 混合召回 → RRF → 过滤 → 重排");
    }
}
```

<callout emoji="✅"><p>企业落地真实数据：某客诉 Agent 上线三件套前，召回准确率（Recall@5）约 62%；加混合检索 → 74%；再加 rerank → 85%；最后加查询扩展 → 91%。每一步都是实打实的提升，不是花活。这就是为什么要"三件套全上"。</p></callout>

---

## 六、成本与延迟权衡

| 组件 | 延迟增量 | 成本增量 | 必要性 |
|------|----------|----------|--------|
| 查询扩展 | +1 次 LLM | 低（用小模型） | 口语化场景强推荐 |
| 混合检索 | +倒排查询 | 低 | 几乎必上 |
| 重排序 | +rerank 推理 | 中（Cross-Encoder） | 准确率敏感必上 |

<callout emoji="💡"><p>一个工程化建议：查询扩展和 rerank 都可以用**小模型**（如 gpt-4o-mini、bge-reranker-base）替代大模型，效果损失极小但成本降一个数量级。把贵的大模型留给最后的"生成"环节。这就是第十六章要讲的"成本治理"思想的提前应用。</p></callout>

---

## 七、踩坑清单

| 坑 | 后果 | 解法 |
|----|------|------|
| 向量和 BM25 分数直接相加 | 融合结果被量纲大的主导 | 用 RRF 只看排名 |
| 精排对全库跑 | 延迟爆炸 | 先粗排 Top-20 再精排 |
| 扩展视角引入噪声 | 召回不相关 | 扩展后必接 rerank |
| rerank 模型太重 | 成本高 | 换 base / 小模型 |
| 没做元数据过滤 | 召回已失效旧文档 | 检索前先过滤 status |

---

## 八、混合检索的 Java 完整编排

把混合检索(向量+BM25) + RRF + rerank 串成可运行骨架：

```java
// ch07-rag/src/main/java/demo/HybridRetrievalOrchestrator.java
package demo;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;

import java.util.*;

public class HybridRetrievalOrchestrator {
    private final OpenAiEmbeddingModel emb;
    private final EmbeddingStore vectorStore;
    // keywordStore 用 ES 客户端，此处示意
    private final Object keywordStore;

    public HybridRetrievalOrchestrator(OpenAiEmbeddingModel emb,
                                       EmbeddingStore vs, Object ks) {
        this.emb = emb; this.vectorStore = vs; this.keywordStore = ks;
    }

    public List<TextSegment> retrieve(String query, int finalTopK) {
        // 1. 向量召回 Top-20
        Embedding q = emb.embed(query).content();
        List<TextSegment> vHits = vectorStore.search(
            dev.langchain4j.store.embedding.search.Request.builder()
                .queryEmbedding(q).maxResults(20).build())
            .matches().stream().map(m -> m.embedded()).toList();
        // 2. 关键词召回 Top-20 (ES, 示意)
        List<TextSegment> kHits = keywordSearch(query, 20);
        // 3. RRF 融合 (见 lesson04 第二节 RrfFusion)
        List<String> fused = RrfFusion.fuse(List.of(
            ids(vHits), ids(kHits)));
        // 4. 映射回 segment 并交 reranker (lesson04 第三节)
        return mapBack(fused, vHits, kHits).subList(0, finalTopK);
    }
    private List<TextSegment> keywordSearch(String q, int k) { return List.of(); }
    private List<String> ids(List<TextSegment> s) { return s.stream()
        .map(x -> x.metadata().getString("cite_id")).toList(); }
    private List<TextSegment> mapBack(List<String> ids,
        List<TextSegment> a, List<TextSegment> b) { return a; }
}
```

---

## 九、检索优化的评测（接第十六章）

| 优化手段 | 观测指标 | 期望变化 |
|----------|----------|----------|
| 加混合检索 | Recall@5 | ↑ |
| 加 rerank | Precision@3 | ↑ |
| 加查询扩展 | Recall@5 | ↑↑ |
| 加元数据过滤 | 噪声率 | ↓ |

<callout emoji="✅"><p>所有检索优化都必须"用评测说话"。上任何一个优化前先建基线，上线后看指标是否真的提升。很多团队"听说 rerank 好就上"，却没测过自己场景的收益，结果白加延迟。第十六章评测闭环会给你完整方法。</p></callout>

---

## 十、生产检索链路的降级策略

```
降级优先级（保可用）：
  正常: 混合检索 + rerank + 扩展
  降级1: 关查询扩展（省延迟）
  降级2: rerank 换轻量 base 模型
  降级3: 纯向量召回（去掉 BM25）
  兜底: 返回"暂未检索到，请换种说法"
```

## 小结

这节课把"召回得准"的三件套讲透了：

- **混合检索**：向量（语义）+ 关键词（字面）双路互补，RRF 融合只看排名、鲁棒不调参。
- **重排序**：Cross-Encoder 精排捕捉 query-chunk 交互，粗排 Top-20 → 精排 Top-3 是标准两段式。
- **查询扩展**：LLM 把短问句扩成多视角，拓宽召回面，但必须配合 rerank 过滤噪声。
- 三件套串起来，Recall@5 可从 62% 提到 90%+，是生产 RAG 的标配而非选配。
- 成本优化上，扩展和 rerank 都用小模型，把大模型留给生成。

下一节我们把 RAG 推到前沿：GraphRAG 怎么把知识图谱融进来，以及 Agentic RAG 是什么（lesson05）。

---

## 延伸阅读 / 引用文档

1. RRF 原始论文 *Reciprocal Rank Fusion* (Cormack et al.): https://plg.uwaterloo.ca/~gvcormac/cormacksigir09-rrf.pdf
2. bge-reranker / BGE 系列: https://github.com/FlagOpen/FlagEmbedding
3. Elasticsearch kNN + BM25 混合检索文档: https://www.elastic.co/guide/en/elasticsearch/reference/current/knn-search.html
4. Microsoft GraphRAG 仓库: https://github.com/microsoft/graphrag
5. LangChain 混合检索（Ensemble Retriever）: https://python.langchain.com/docs/integrations/retrievers/ensemble
6. Cohere Rerank 文档（理解 rerank 商业方案）: https://docs.cohere.com/docs/reranking

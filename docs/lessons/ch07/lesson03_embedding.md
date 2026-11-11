# 第七章第三节：Embedding 模型详解：文本向量化技术、主流 Embedding 模型选型

大家好，我是秋天。

前两节课我们把文档切好、元数据标好，接下来要干一件决定 RAG 生死的事：**把文字变成向量（Embedding）**。模型选错了，后面检索优化全白费——就像地基歪了，楼盖得再漂亮也塌。

学完这节课，你能掌握：

1. **Embedding 的本质**：为什么一段文本能变成一个 768 维的浮点数组，且"意思相近的向量在空间里也相近"。
2. **向量相似度计算**：余弦相似度、点积、欧氏距离分别在什么场景用。
3. **主流模型怎么选**：OpenAI text-embedding-3 系列、BGE、M3E、GTE，中英场景如何取舍，维度/成本/效果的三角权衡。

<callout emoji="💡"><p>招聘映射：JD 高频词"熟悉 Embedding 模型选型与向量检索"。面试官常问"中文场景你为什么选 bge 而不是 openai embedding"——这节课给你一套能脱口而出的选型框架。</p></callout>

---

## 一、Embedding 到底在干什么

### 1.1 一句话定义

Embedding 是把"离散符号（文字）"映射到"连续向量空间"的函数：`f(text) -> R^d`。它把语义压缩成一串数字，使得**语义相近的文本，向量在空间中距离也近**。

```
"如何申请退款"  ──┐
                 ├──►  语义相近 ──►  向量空间里距离近
"退款流程是什么"──┘

"今天天气真好"  ──►  语义无关 ──►  向量空间里距离远
```

### 1.2 它是怎么学会"语义相近"的

现代 Embedding 模型基本都是基于 Transformer 的**对比学习（Contrastive Learning）**预训练：给模型看海量"正例对"（语义相同的两句话）和"负例对"（不相关的两句话），训练它让正例对的向量靠近、负例对的向量远离。损失函数常用 InfoNCE：

```
L = -log( exp(s(q, p+)/τ) / Σ exp(s(q, p_i)/τ) )

其中 s 是相似度，τ 是温度系数，p+ 是正例，p_i 含负例
```

<callout emoji="⚠️"><p>理解这一点很重要：Embedding 模型"懂不懂中文语义"，完全取决于它训练时见过多少中文语料、什么领域的中文。一个主要在英文 Wikipedia 上训的模型，对中文电商术语的区分能力一定不如专为中文场景训的 bge/m3e。这就是选型的第一性原则。</p></callout>

### 1.3 直观看一个向量长什么样

```java
// ch07-rag/src/main/java/demo/EmbeddingInspect.java
package demo;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

public class EmbeddingInspect {
    public static void main(String[] args) {
        OpenAiEmbeddingModel model = OpenAiEmbeddingModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("text-embedding-3-small").build();

        Embedding e = model.embed("如何申请退款").content();
        float[] vector = e.vector();   // 1536 维浮点数组

        System.out.println("维度: " + vector.length);
        System.out.print("前 8 维: ");
        for (int i = 0; i < 8; i++) System.out.printf("%.4f ", vector[i]);
        System.out.println();

        // 计算两个句子的相似度
        Embedding e2 = model.embed("退款流程是什么").content();
        System.out.printf("相似度(余弦): %.4f%n", e.cosineSimilarity(e2));
    }
}
```

---

## 二、向量相似度：余弦、点积、欧氏

### 2.1 三种度量怎么选

```
┌─────────────────────────────────────────────────────────┐
│ 度量          公式要点              适用场景              │
├─────────────────────────────────────────────────────────┤
│ 余弦相似度     cos = a·b / |a||b|   最常用，忽略向量长度  │
│ 点积           a·b                  归一化后等价于余弦    │
│ 欧氏距离       √Σ(a-b)²            对绝对位置敏感        │
└─────────────────────────────────────────────────────────┘
```

绝大多数 RAG 场景用**余弦相似度**，因为它只关心"方向（语义）"不在乎"长度（文本字数）"——"退款"和"我要退款怎么办"字数差很多但语义一致，余弦相似度能正确捕捉。

```java
// ch07-rag/src/main/java/demo/SimilarityMetrics.java
package demo;

import dev.langchain4j.data.embedding.Embedding;

public class SimilarityMetrics {
    public static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    public static double euclidean(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            sum += d * d;
        }
        return Math.sqrt(sum);
    }

    public static void main(String[] args) {
        Embedding x = Embedding.from(new float[]{0.1f, 0.2f, 0.3f});
        Embedding y = Embedding.from(new float[]{0.2f, 0.4f, 0.6f}); // 与 x 同向
        System.out.printf("余弦: %.4f (应接近1)%n", x.cosineSimilarity(y));
    }
}
```

### 2.2 维度灾难与降维

高维向量（如 3072 维）存储和检索成本高。OpenAI text-embedding-3 支持**矩阵降维（Matryoshka Representation Learning）**：你可以只取前 256 维，效果损失很小但存储省 12 倍。

```yaml
# ch07-rag/src/main/resources/application.yml 片段
rag:
  embedding:
    model: text-embedding-3-large
    dimensions: 256        # Matryoshka 降维，省存储保效果
    normalize: true        # 归一化后点积=余弦，加速 ANN 检索
```

<callout emoji="💡"><p>工程建议：生产环境务必对 embedding 做 L2 归一化。归一化后"余弦相似度"就等于"点积"，而点积在向量库里能用更高效的量化索引（如 IVF + PQ）加速。这是把检索延迟从 200ms 压到 20ms 的关键一招。</p></callout>

---

## 三、主流 Embedding 模型横向对比

### 3.1 选型决策表（2026 视角）

```
模型                 维度      语言      是否需联网   许可证       适用
─────────────────────────────────────────────────────────────────────
text-embedding-3-s   1536      多语      需(OpenAI)  商业 API     通用、省心
text-embedding-3-l   3072      多语      需(OpenAI)  商业 API     效果上限高
bge-large-zh-v1.5    1024      中英优    本地可跑     MIT         中文首选
bge-m3               1024      多语      本地可跑     MIT         多语言+稀疏向量
m3e-base             768       中文      本地可跑     Apache2.0   中文轻量
gte-large-zh         1024      中英      本地可跑     Microsoft   中文强
e5-mistral-7b        )))       多语      本地(大模型) 非商用       效果顶级但重
```

### 3.2 中文场景为什么首推 BGE

BGE（BAAI General Embedding）由智源研究院训练，在中文 C-MTEB 榜单长期霸榜。关键优势：

- **中文语料充足**，对成语、领域术语区分度好。
- **可本地部署**，数据不出内网——金融、政企的合规硬要求。
- **bge-m3 同时输出稠密 + 稀疏向量**，天然支持混合检索（lesson04 详讲）。

```java
// ch07-rag/src/main/java/demo/BgeLocalEmbedding.java
package demo;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.huggingface.HuggingFaceEmbeddingModel;

/**
 * 本地部署 BGE，数据不出内网。
 * 需先下载模型权重到本地（如通过 HuggingFace 缓存）。
 */
public class BgeLocalEmbedding {
    public static void main(String[] args) {
        EmbeddingModel model = HuggingFaceEmbeddingModel.builder()
                .modelName("BAAI/bge-large-zh-v1.5")
                // 本地模型路径，避免每次联网拉取
                .modelCacheDirectory("/models/bge-large-zh-v1.5")
                .build();

        float[] v = model.embed("吊牌剪了能退货吗").content().vector();
        System.out.println("本地 BGE 向量维度: " + v.length);
    }
}
```

### 3.3 OpenAI Embedding 的取舍

```java
// ch07-rag/src/main/java/demo/OpenAiEmbedding.java
package demo;

import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModelName;

public class OpenAiEmbedding {
    public static void main(String[] args) {
        OpenAiEmbeddingModel model = OpenAiEmbeddingModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName(OpenAiEmbeddingModelName.TEXT_EMBEDDING_3_SMALL)
                .dimensions(512)   // Matryoshka 降维
                .build();
        System.out.println(model.embed("退款时效").content().vector().length); // 512
    }
}
```

<callout emoji="⚠️"><p>选型红线：如果你的业务数据**不允许出内网**（银行、政务、医疗几乎都是），OpenAI Embedding 直接 pass，老老实实上 BGE / GTE 本地部署。合规性优先于那一点点效果差距。</p></callout>

---

## 四、维度 / 成本 / 效果三角权衡

```
                    效果上限
                      /\
                     /  \
                    /    \  e5-mistral(重,慢,贵)
                   /      \
                  /  bge-l  \
                 /   openai-l \
                /______________\ 成本/延迟
              m3e(轻,快,便宜)
```

给你一套落地决策框架：

1. **先定合规**：数据能否出内网？不能 → 本地模型（BGE/GTE/M3E）。
2. **再定语言**：纯中文 → BGE-large-zh；中英混合 → bge-m3 / gte-large-zh。
3. **后定预算**：QPS 高、成本敏感 → 小维度（512/768）+ Matryoshka 降维。
4. **最后定效果**：头部场景（如法务检索）可接受大模型 → e5-mistral-7b。

```yaml
# 不同阶段选型示例
profiles:
  poc:                       # 快速验证
    model: text-embedding-3-small
    dimensions: 1536
  production-zh-finance:     # 金融内网
    model: BAAI/bge-large-zh-v1.5
    local: true
    dimensions: 1024
    normalize: true
  production-multilingual:   # 跨境电商多语
    model: BAAI/bge-m3
    local: true
    use-sparse: true         # 开启稀疏向量做混合检索
```

---

## 五、企业落地：维度与向量库的协同设计

向量库选型和 embedding 维度强相关。给你一张对照：

| 向量库 | 索引类型 | 适合维度 | 备注 |
|--------|----------|----------|------|
| pgvector | IVFFlat / HNSW | ≤1536 | 运维友好，中小规模 |
| Milvus | IVF_PQ / HNSW | 任意 | 十亿级，分布式 |
| Elasticsearch kNN | HNSW | ≤1024 较优 | 已有 ES 栈复用 |
| Redis Search | HNSW | 中小 | 低延迟缓存型 |
| Chroma | HNSW | 中小 | 轻量原型 |

<callout emoji="✅"><p>一个真实案例：某政企知识库一开始用 OpenAI embedding + 云端，合规审查被卡；切到本地 BGE-large-zh + Milvus 后，效果持平、数据不出网、长期成本反而更低。选型不是"选最强"，是"选最合适 + 合规 + 可持续"。</p></callout>

---

## 六、本地部署 BGE 的完整可运行示例（Spring Boot 风格）

```java
// ch07-rag/src/main/java/demo/config/EmbeddingConfig.java
package demo.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.huggingface.HuggingFaceEmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmbeddingConfig {

    @Bean
    public EmbeddingModel embeddingModel() {
        // 本地 BGE，数据不出内网
        return HuggingFaceEmbeddingModel.builder()
                .modelName("BAAI/bge-large-zh-v1.5")
                .modelCacheDirectory("/models/bge-large-zh-v1.5")
                .build();
    }
}
```

对应 `application.yml`：

```yaml
# ch07-rag/src/main/resources/application.yml
spring:
  ai:
    ollama:
      base-url: http://localhost:11434   # 也可用 Ollama 托管本地 embedding
    # 若用 Ollama 跑 nomic-embed-text：
    # embedding:
    #   ollama:
    #     model: nomic-embed-text
```

<callout emoji="💡"><p>如果你不想自己折腾 HuggingFace 权重，可以用 Ollama 一条命令拉起本地 embedding 模型（如 `ollama pull bge-m3`），Java 侧通过 OpenAI 兼容接口调用，部署成本极低。原型验证阶段强烈推荐。</p></callout>

---

## 七、踩坑清单

| 坑 | 后果 | 解法 |
|----|------|------|
| 检索和入库用不同 embedding 模型 | 向量空间不一致，召回全错 | 统一模型 + 版本锁定 |
| 中文用纯英文训练的模型 | 区分度差 | 换 BGE/M3E/GTE |
| 没归一化 | 检索慢、相似度计算错 | 入库前 L2 normalize |
| 维度过高不降维 | 存储/延迟爆炸 | Matryoshka 降维 |
| 模型版本偷偷升级 | 老向量失效 | 锁定模型版本，重建索引有预案 |

<callout emoji="⚠️"><p>最隐蔽的坑：**索引用的 embedding 模型和检索用的不是同一个**。比如离线用 bge-large-zh 建库，在线服务升级成了 bge-m3，向量维度都对不上，召回直接归零。务必在配置中心把 embedding 模型版本当作"基础设施版本"来管理，变更要触发全量重建索引。</p></callout>

---

## 八、Embedding 的批量与异步处理

生产索引海量文档时，逐条 embedding 太慢。要批量 + 异步：

```java
// ch07-rag/src/main/java/demo/EmbeddingBatch.java
package demo;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

import java.util.*;
import java.util.concurrent.*;

public class EmbeddingBatch {
    /** 分批并发向量化，控制并发数防止限流 */
    public static List<Embedding> batchEmbed(OpenAiEmbeddingModel model,
                                             List<TextSegment> segments) {
        int batch = 32;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<CompletableFuture<List<Embedding>>> futures = new ArrayList<>();
        for (int i = 0; i < segments.size(); i += batch) {
            List<TextSegment> sub = segments.subList(i,
                Math.min(i + batch, segments.size()));
            futures.add(CompletableFuture.supplyAsync(
                () -> model.embedAll(sub).content(), pool));
        }
        List<Embedding> result = new ArrayList<>();
        futures.forEach(f -> { try { result.addAll(f.get()); }
            catch (Exception e) { /* 单批失败重试 */ } });
        pool.shutdown();
        return result;
    }
}
```

<callout emoji="⚠️"><p>调用 OpenAI Embedding API 有 RPM/TPM 限流。批量时务必控制并发与速率，并做好单批失败重试。自部署 BGE 则受限于本机 GPU 显存，批次也不能无限大。限流处理是 Embedding 工程化的必修课。</p></callout>

---

## 九、Embedding 的可视化与验证

上线前验证 embedding 质量，最简单的是"语义相似度抽查"：

```java
// ch07-rag/src/main/java/demo/EmbeddingSanity.java
package demo;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

public class EmbeddingSanity {
    public static void main(String[] args) {
        OpenAiEmbeddingModel m = OpenAiEmbeddingModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("text-embedding-3-small").build();
        Embedding a = m.embed("退款怎么操作").content();
        Embedding b = m.embed("退货流程是什么").content();
        Embedding c = m.embed("今天天气真好").content();
        System.out.printf("近义相似度: %.3f (应高)%n", a.cosineSimilarity(b));
        System.out.printf("无关相似度: %.3f (应低)%n", a.cosineSimilarity(c));
    }
}
```

---

## 十、Embedding 选型决策清单

| 检查项 | 通过标准 |
|--------|----------|
| 合规允许出网？ | 不允许→本地模型(BGE/GTE) |
| 主要语言 | 中文→BGE/GTE/M3E |
| 维度与存储 | ≤1536，必要时 Matryoshka 降维 |
| 版本锁定 | 模型版本纳入配置中心管理 |
| 限流处理 | 批量+并发+重试已就绪 |

## 十一、Embedding 服务的限流与缓存

在线检索时，每次用户 query 都要算一次 embedding。高频场景下这是隐性成本与延迟来源。两招优化：

```java
// ch07-rag/src/main/java/demo/EmbeddingCache.java
package demo;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class EmbeddingCache {
    // 相同 query 的 embedding 命中缓存，省一次 API 调用
    private final Map<String, Embedding> cache = new ConcurrentHashMap<>();
    private final OpenAiEmbeddingModel model;

    public EmbeddingCache(OpenAiEmbeddingModel m) { this.model = m; }

    public Embedding embed(String text) {
        return cache.computeIfAbsent(text, t -> model.embed(t).content());
    }
}
```

<callout emoji="💡"><p>Query 的 embedding 天然可缓存（相同问题向量相同）。加一层本地/LRU 缓存，热门 query 命中后零 API 成本、零延迟。这是把检索延迟从 50ms 压到 1ms 的便宜招，也是第十六章成本治理的题中之义。</p></callout>

---

## 十二、ANN 索引参数调优（以 pgvector 为例）

向量库用近似最近邻(ANN)索引加速检索，索引参数直接影响"速度 vs 召回"：

```
pgvector HNSW 参数：
  m        : 每层连接数，越大越准越慢（默认 16，建议 16~32）
  ef_search: 检索时遍历范围，越大越准越慢（运行时设）
  ef_construction: 建索引时质量，越大越准越慢
```

```sql
-- 建 HNSW 索引并调参
CREATE INDEX ON kb_segments
USING hnsw (embedding vector_cosine_ops)
WITH (m = 24, ef_construction = 64);

-- 检索时动态调 ef_search
SET hnsw.ef_search = 40;   -- 质量优先
-- 或
SET hnsw.ef_search = 10;   -- 速度优先
```

| ef_search | 召回率 | 延迟 |
|-----------|--------|------|
| 10 | 中 | 低 |
| 40 | 高 | 中 |
| 80 | 很高 | 高 |

<callout emoji="⚠️"><p>没有"最佳参数"，只有"业务取舍"。客服场景延迟敏感用低 ef_search；法务检索准确率优先用高 ef_search。用评测集（lesson04/第十六章）扫一遍参数选甜区。</p></callout>

---

## 十三、Embedding 常见误用案例

| 误用 | 现象 | 修正 |
|------|------|------|
| query 与 doc 用不同模型 | 召回全错 | 统一模型+版本 |
| 未归一化直接点积 | 相似度量程错 | 入库前 L2 normalize |
| 维度 mismatach | 维度报错 | 模型维度对齐 |
| 中文用英训模型 | 区分度差 | 换 BGE/M3E |
| 把 embedding 当存储主键 | 不可精确匹配 | 主键另存 |

## 小结

这节课我们把 Embedding 这件事讲透了：

- Embedding 是"语义→向量"的映射，靠对比学习训出"近义相近"。
- 相似度首选余弦（方向敏感、长度无关），归一化后等价点积可加速。
- 中文/内网场景首推 BGE / GTE / M3E 本地部署；通用省心可选 OpenAI text-embedding-3。
- 选型遵循"合规 → 语言 → 预算 → 效果"的决策顺序，没有最强只有最合适。
- 维度、降维、向量库索引类型要协同设计；模型版本必须锁定。

下一节进入检索优化三件套：混合检索、重排序、查询扩展（lesson04），把"召回得准"这件事做到极致。

---

## 延伸阅读 / 引用文档

1. OpenAI Embeddings 指南（含 Matryoshka 降维）: https://platform.openai.com/docs/guides/embeddings
2. BGE 官方仓库（C-MTEB 榜单与用法）: https://github.com/FlagOpen/FlagEmbedding
3. M3E Embedding: https://github.com/moka-ai/m3e
4. GTE 模型（Microsoft）: https://github.com/microsoft/LMOps
5. Sentence Transformers 文档（对比学习原理）: https://www.sbert.net/
6. C-MTEB 中文嵌入评测榜单: https://github.com/FlagOpen/FlagEmbedding/tree/master/FlagEmbedding/BGE_M3

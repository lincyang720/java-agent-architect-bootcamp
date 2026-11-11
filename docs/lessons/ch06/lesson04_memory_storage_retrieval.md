# 第六章第4节：记忆存储与检索——Embedding 生成、相似度检索算法（余弦、欧氏）

大家好，我是秋天。

上节课我们选好了"仓库"——向量数据库。但仓库里装的是什么、怎么把记忆存进去、又怎么把"相关的"找出来？这节课讲长期记忆的"发动机"：**Embedding 生成** 和 **相似度检索算法（余弦、欧氏）**。

学完这节课你能：

1. 理解 Embedding 模型怎么把文本变成向量，以及选型要点；
2. 手写余弦相似度和欧氏距离，看清它们的数学本质与差异；
3. 用 Java 调 Embedding 模型并做相似度检索；
4. 理解"归一化"为什么让余弦和欧氏在特定条件下等价。

这是记忆检索的底层数学，也是 RAG 检索（第七章）的直接前序知识。很多同学只会调 API，一问"余弦和欧氏区别"就卡壳——这节课补上。

---

## 一、Embedding：把语义编码进数字

Embedding 模型（如 OpenAI text-embedding、bge-m3、gte）接受一个文本，输出一个固定维度的浮点向量。它的训练目标是：**语义相近的文本，向量在空间里靠近**。

### 1.1 为什么是"语义"而不是"词频"

传统 TF-IDF 看词出现频率，无法理解"退货"≈"退换"。Embedding 在海量语料上训练，捕捉的是语义和上下文关系，所以能跨表述找到相关。

```text
文本 A："我要退上个月买的鞋"
文本 B："退货流程怎么走"
文本 C："今天天气真好"

Embedding 后（示意 3 维）：
  A = [0.8, 0.2, 0.1]
  B = [0.78, 0.22, 0.12]   ← 和 A 接近（语义相关）
  C = [0.1, 0.9, 0.3]      ← 和 A 远（语义无关）
```

<callout emoji="💡"><p>记忆的本质洞察：长期记忆检索不是在比"字"，是在比"意"。Embedding 就是把"意"翻译成可计算的数字距离。这一步做不好，后面算法再牛也白搭——垃圾向量进，垃圾结果出。</p></callout>

### 1.2 Embedding 模型选型

```text
Embedding 模型选型三要素：
  1) 维度：高维表达能力好但占存储/算力（768 / 1024 / 1536 / 3072）
  2) 语言：中文场景优先中英双语模型（bge-m3 / gte-large-zh）
  3) 部署：云 API（OpenAI/ DashScope）vs 本地（FastEmbed / Ollama）
```

```text
常见模型：
  OpenAI text-embedding-3-small   1536维  英文强、云API
  bge-m3                          1024维  中英双语、可本地
  gte-large-zh                    1024维  中文强、可本地
  DashScope text-embedding-v2     1536维  阿里云、中文
```

<callout emoji="⚠️"><p>坑点：同一个记忆系统里，存和取必须用**同一个 Embedding 模型**。用 A 模型存、用 B 模型查，向量空间不同，检索全乱。这是新手最常见的低级错误。</p></callout>

---

## 二、相似度算法：余弦 vs 欧氏

向量检索的核心是"算两个向量的距离/相似度"。两个最常用：**余弦相似度** 和 **欧氏距离**。

### 2.1 数学定义（先看清本质）

```text
给定两个向量 A, B（维度 n）：

欧氏距离（Euclidean）：
  d(A,B) = √(Σ (Aᵢ - Bᵢ)²)
  衡量：空间里的"直线距离"，受向量长度影响

余弦相似度（Cosine）：
  cos(A,B) = (A·B) / (|A| × |B|) = Σ(AᵢBᵢ) / (√ΣAᵢ² × √ΣBᵢ²)
  衡量：两个向量的"方向夹角"，与长度无关，范围 [-1, 1]
```

关键区别一句话：**欧氏看"绝对距离"，余弦看"方向是否一致"**。

<callout emoji="✅"><p>记忆点：文本 Embedding 语义相似看"方向"而非"长度"。一篇长文档和一句短问题，向量长度差很多，但语义方向可能很接近。所以文本检索几乎都用余弦——它不被长度干扰。</p></callout>

### 2.2 手写实现（Java）

别只会调库，手写一遍才真懂。下面是纯 Java 实现，无依赖、可直接跑：

```java
package com.camp.memory;

/**
 * 相似度算法的纯 Java 实现，无依赖，便于理解本质。
 */
public class SimilarityMath {

    /** 余弦相似度：方向一致性，范围 [-1,1]，1 表示完全相同方向 */
    public static double cosine(double[] a, double[] b) {
        if (a.length != b.length) throw new IllegalArgumentException("维度必须一致");
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /** 欧氏距离：直线距离，越小越近 */
    public static double euclidean(double[] a, double[] b) {
        if (a.length != b.length) throw new IllegalArgumentException("维度必须一致");
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            double diff = a[i] - b[i];
            sum += diff * diff;
        }
        return Math.sqrt(sum);
    }

    /** 将欧氏距离转成 0~1 的相似度（可选，便于统一比较） */
    public static double euclideanSimilarity(double[] a, double[] b) {
        return 1.0 / (1.0 + euclidean(a, b));
    }

    public static void main(String[] args) {
        double[] A = {0.8, 0.2, 0.1};
        double[] B = {0.78, 0.22, 0.12};   // 语义近
        double[] C = {0.1, 0.9, 0.3};      // 语义远

        System.out.println("cos(A,B)=" + cosine(A, B));   // ≈ 0.999 很近
        System.out.println("cos(A,C)=" + cosine(A, C));   // 较小 远
        System.out.println("euc(A,B)=" + euclidean(A, B)); // 小
        System.out.println("euc(A,C)=" + euclidean(A, C)); // 大
    }
}
```

<callout emoji="💡"><p>手写的价值：你一眼能看出余弦分母是"模长乘积"——这意味着**归一化（单位向量）后，余弦 = 点积**。这正是很多向量库内部优化：把向量归一化，检索时用点积代替余弦，速度更快。理解这点，你看库文档就不懵了。</p></callout>

### 2.3 归一化：让余弦和欧氏"殊途同归"

一个关键结论：**当向量都归一化（长度=1）时，余弦相似度和欧氏距离单调相关**。

```text
若 |A| = |B| = 1：
  cos(A,B) = A·B
  欧氏距离² d² = |A-B|² = |A|² + |B|² - 2A·B = 2 - 2cos(A,B)
  ⇒ d² = 2(1 - cos)  →  cos 越大，d 越小

结论：归一化后，余弦近 ⇔ 欧氏近。两算法等价！
```

这就是为什么向量库常要求/自动归一化——检索时可以用最快的点积（或欧氏）实现余弦语义。

<callout emoji="⚠️"><p>工程提醒：如果你用欧氏距离做文本检索，务必先归一化，否则长文本（向量长）会被系统性判"远"，导致检索偏斜。绝大多数文本场景，直接用余弦最稳妥，别给自己挖坑。</p></callout>

---

## 三、Embedding + 检索的 Java 全链路

把"生成向量"和"相似检索"串成一条可运行链路。用 Spring AI 的 `EmbeddingModel` + `VectorStore`：

```java
package com.camp.memory;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 长期记忆的"存 + 取"全链路。
 * 存：文本 → Embedding 模型 → 向量 → 向量库
 * 取：查询 → Embedding 模型 → 向量 → 相似检索 → 原文
 */
@Component
public class MemoryStore {

    @Autowired
    private EmbeddingModel embeddingModel;   // 注入 Embedding 模型（如 Ollama/DashScope）

    @Autowired
    private VectorStore vectorStore;          // 注入向量库（PgVector 等）

    /** 存入一条记忆：自动调 Embedding 模型生成向量 */
    public void save(String text, String userId, String category) {
        Document doc = new Document(text,
                Map.of("user_id", userId, "category", category));
        vectorStore.add(List.of(doc));   // Spring AI 内部自动 Embedding
    }

    /** 检索：查询文本同样经同一 Embedding 模型向量化 */
    public List<Document> search(String query, String userId, int topK) {
        return vectorStore.similaritySearch(
                org.springframework.ai.vectorstore.SearchRequest.builder()
                        .query(query)                      // 自动 Embedding
                        .topK(topK)
                        .filterExpression("user_id == '" + userId + "'")
                        .similarityThreshold(0.75)         // 相似度阈值过滤
                        .build());
    }
}
```

<callout emoji="✅"><p>注意 `similarityThreshold(0.75)`：只返回相似度高于阈值的记忆。这能挡掉"八竿子打不着"的误召回。阈值怎么定？靠评测（第十六章）——太低召回噪音，太高漏掉相关。0.7~0.8 是常见起点。</p></callout>

---

## 四、本地 Embedding：不依赖云 API

企业怕数据出境，可以用本地 Embedding 模型。Spring AI 支持 Ollama 本地模型：

```yaml
# application.yml：用本地 Ollama 跑 Embedding，数据不出内网
spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      embedding:
        options:
          model: bge-m3     # 中英双语，本地跑
    vectorstore:
      pgvector:
        index-type: HNSW
        distance-type: COSINE
        dimensions: 1024    # bge-m3 维度
```

```java
package com.camp.memory;

import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LocalEmbeddingConfig {
    @Bean
    public OllamaEmbeddingModel embeddingModel() {
        return new OllamaEmbeddingModel(
                new OllamaApi("http://localhost:11434"));
    }
}
```

<callout emoji="💡"><p>合规价值：本地 Embedding + 本地向量库，整条记忆链路数据不出企业内网，满足金融/政务的强合规。代价是得自己扛模型推理资源（GPU/CPU）。这是"便利 vs 合规"的经典权衡。</p></callout>

---

## 五、检索结果怎么用：回填上下文

检索到的记忆不是直接给用户看的，而是**回填进短期记忆（上下文窗口）**，让模型基于它推理。

```text
检索回填流程：
  用户提问
     │
     ▼
  长期记忆检索 TopK 相关记忆
     │
     ▼
  把记忆拼进 prompt（[相关记忆] 区块）
     │
     ▼
  模型基于"当前问题 + 相关记忆"生成回答
```

```java
package com.camp.memory;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class MemoryAugmentedAgent {

    @Autowired private ChatModel chatModel;
    @Autowired private MemoryStore memoryStore;

    public String answer(String userId, String question) {
        // 1) 从长期记忆召回相关片段
        List<String> memories = memoryStore.search(question, userId, 5).stream()
                .map(Document::getText)
                .collect(Collectors.toList());

        // 2) 拼装 prompt：系统指令 + 相关记忆 + 问题
        String system = "你是客服助手。下面的[相关记忆]是用户的历史信息，请参考回答。";
        String memoryBlock = "[相关记忆]\n" + String.join("\n---\n", memories);
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(system + "\n" + memoryBlock),
                new UserMessage(question)
        ));

        // 3) 调用模型
        return chatModel.call(prompt).getResult().getOutput().getText();
    }
}
```

<callout emoji="⚠️"><p>这是"记忆增强生成"的最小闭环，也是 RAG 的雏形（第七章全链路讲 RAG）。注意：检索到的记忆要标注来源，便于用户核实和审计——特别是医疗/金融，不能让模型"假装记得"。</p></callout>

---

## 六、相似度算法的工程选择指南

给你一个速查表，别再纠结：

```text
┌──────────────────┬────────────────────────────────────────┐
│ 场景             │ 推荐算法                               │
├──────────────────┼────────────────────────────────────────┤
│ 文本语义检索     │ 余弦（首选，不受长度干扰）             │
│ 已归一化向量     │ 点积（=余弦，最快）                   │
│ 图像/特征向量    │ 欧氏（有时更合适，看分布）            │
│ 需要"绝对远近"   │ 欧氏（保留距离量纲）                 │
│ 大规模ANN检索    │ 库内部用 cosine/HNSW，对外给 topK     │
└──────────────────┴────────────────────────────────────────┘
```

<callout emoji="✅"><p>面试考点："余弦和欧氏怎么选？"标准答：文本语义检索用余弦（只看方向、不受长度影响）；向量归一化后两者等价，库内部常转点积提速；图像等特征向量有时用欧氏。核心是"语义相似看方向"。</p></callout>

---

## 七、招聘能力映射

这节课是 JD 里"Embedding / 向量检索 / RAG"要求的直接前序：

- "**熟悉 Embedding 模型与选型**" → 第一节。
- "**理解相似度算法（余弦/欧氏）**" → 第二节，手写实现。
- "**向量检索 / kNN**" → 第三、五节。
- "**RAG 基础**" → 第五节是 RAG 雏形，第七章展开。

<callout emoji="💡"><p>面试金句："Embedding 把语义编码成向量，检索靠相似度——文本用余弦（看方向），归一化后余弦=点积可提速，欧氏看绝对距离。存和取必须同模型，阈值靠评测定。" 这段话把检索底层讲明白，面试官知道你是真懂不是调包。</p></callout>

---

## 七、批量 Embedding 与异步管道

生产里记忆是"批量写入"的（一次导入万条文档），逐条 Embedding 太慢。要批量 + 异步。

```java
package com.camp.memory;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 批量 Embedding 写入：分批 + 异步，避免阻塞、控制速率。
 */
@Component
public class BatchMemoryWriter {

    @Autowired private EmbeddingModel embeddingModel;
    @Autowired private VectorStore vectorStore;

    public CompletableFuture<Void> ingest(List<String> texts, String userId) {
        // 分批，每批 100 条，避免单次请求过大
        int batch = 100;
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += batch) {
            List<String> chunk = texts.subList(i, Math.min(i + batch, texts.size()));
            futures.add(CompletableFuture.runAsync(() -> {
                List<Document> docs = chunk.stream()
                        .map(t -> new Document(t, Map.of("user_id", userId)))
                        .toList();
                vectorStore.add(docs);   // Spring AI 内部批量 Embedding
            }));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }
}
```

<callout emoji="💡"><p>批量写入的讲究：① 分批避免单次请求超长；② 异步提速但控制并发防限流；③ 失败要有重试/断点续传。这是"记忆初始化/知识库导入"的生产姿势，第七章 RAG 建索引同理。</p></callout>

## 八、混合检索中的重排（Rerank）

向量召回 TopK 后，常再用一个"重排模型"精排——这是检索质量的关键一步（第七章深化）。

```text
两阶段检索：
  阶段1 召回（Retrieval）：向量库取 TopK=20（宁可多取）
  阶段2 重排（Rerank）：重排模型对 20 条精打，取 Top5 进上下文
  价值：向量召回"粗但快"，重排"准但慢"，组合最优
```

```java
package com.camp.memory;

import java.util.*;

/**
 * 重排示意：用交叉编码器思想（query 与 doc 联合打分）。
 * 真实用 BGE-Reranker 等模型，此处用长度/重叠度模拟。
 */
public class SimpleReranker {
    public List<String> rerank(String query, List<String> candidates, int topN) {
        return candidates.stream()
                .map(c -> Map.entry(c, score(query, c)))
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topN)
                .map(Map.Entry::getKey)
                .toList();
    }
    private double score(String q, String d) {
        // 简化：重叠词数（真实用交叉编码器）
        long overlap = Arrays.stream(q.split(""))
                .filter(w -> d.contains(w)).count();
        return overlap;
    }
}
```

<callout emoji="⚠️"><p>重排是"检索质量"的最大杠杆之一。很多团队只做向量召回，召回的 TopK 里相关项可能排第 8，模型只看前几条就漏掉了。加一层 Rerank，准确率能涨 10~20 个点。这是 RAG/记忆检索的共性优化。</p></callout>

## 九、Embedding 维度与量化的权衡

Embedding 维度不是越大越好，要权衡存储/算力/效果。

```text
维度权衡：
  768 维：快、省存储，通用场景够用
  1024 维：中英双语模型常用（bge-m3/gte）
  1536 维：OpenAI 默认，效果强但贵
  3072 维：顶级效果，存储/算力成本高
  → 中文业务：1024 维双语模型是甜区
```

<callout emoji="✅"><p>选型建议：先用开源双语 1024 维模型（bge-m3）跑通，效果不够再上更大维度。不要一上来就用最贵最大的——多数中文业务 1024 维已经很好。</p></callout>

## 十、本地 vs 云 Embedding 成本对比

```text
Embedding 调用成本（示意，百万条文档）：
  云 API（OpenAI/DashScope）：按 token 计费，量大可观，但零运维
  本地（Ollama + bge-m3）：一次性 GPU 成本，量大边际≈0，但需运维
  → 文档海量 → 本地更省；文档少量/偶发 → 云 API 更省心
```

<callout emoji="💡"><p>决策点和"数据合规"叠加：既要合规（数据不出网）又要省 → 本地 Embedding 几乎是唯一解。这也是为什么我们 lesson04 给了 Ollama 本地方案。</p></callout>

## 十一、如何评测 Embedding / 检索质量

记忆检索好不好，不能靠感觉，要量化。

```text
检索质量指标：
  Recall@K：相关记忆是否在前 K 个里被召回
  MRR（平均倒数排名）：第一个相关项的平均排名
  NDCG：考虑排序质量的综合指标
  → 用标注好的"问题-相关记忆"数据集跑，看指标
```

<callout emoji="⚠️"><p>没有评测的检索优化是盲调。建一小批"问题→应该召回哪条记忆"的标注数据，定期跑 Recall@K，优化才有方向。这又是第十六章"评测闭环"在记忆域的体现。</p></callout>

## 十二、避坑清单

```text
Embedding/检索避坑：
❌ 存和取用不同模型（向量空间不一致）
❌ 维度与库表不一致（建表失败/检索乱）
❌ 只用向量召回不做重排（漏相关项）
❌ 不做评测（不知好坏）
❌ 海量文档还用云 API 不计成本
✅ 同模型、对齐维度、加 Rerank、做评测、按量选本地/云
```

<callout emoji="💡"><p>这节课是"记忆检索的数学与工程底座"。 cosine/欧氏、归一化、批量、Rerank、评测——每一块都是 RAG（第七章）的直接前序。学透这节，第七章 RAG 你几乎不用重新学检索。</p></callout>

## 十三、Embedding 缓存与去重

反复 Embedding 相同文本是浪费。加一层缓存 + 去重。

```java
package com.camp.memory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Embedding 缓存：相同文本不重复调用模型。
 */
public class EmbeddingCache {
    private final Map<String, double[]> cache = new ConcurrentHashMap<>();

    public double[] embed(String text, EmbedFunc embedder) {
        return cache.computeIfAbsent(text, t -> embedder.apply(t));
    }

    interface EmbedFunc { double[] apply(String t); }
}
```

<callout emoji="💡"><p>记忆初始化时，大量文本可能重复（如相同 FAQ 出现在多个文档）。Embedding 前先去重 + 缓存，能省下可观的模型调用。这是"检索优化"里常被忽略、但很实惠的一环。</p></callout>

## 十四、实战：端到端记忆检索 Pipeline

把 lesson03-04 串成一条完整 pipeline：

```java
package com.camp.memory;

import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

/**
 * 端到端记忆检索：查询 → Embedding → 向量召回 → 重排 → 返回
 */
@Component
public class MemoryRetrievalPipeline {

    @Autowired private VectorStore vectorStore;
    @Autowired private SimpleReranker reranker;

    public List<String> query(String q, String userId, int topK) {
        // 1) 向量召回 TopK*4（粗召回，宁可多）
        List<Document> recalled = vectorStore.similaritySearch(
                org.springframework.ai.vectorstore.SearchRequest.builder()
                        .query(q).topK(topK * 4)
                        .filterExpression("user_id == '" + userId + "'")
                        .build());
        // 2) 重排精筛 TopK
        List<String> candidates = recalled.stream().map(Document::getText).toList();
        return reranker.rerank(q, candidates, topK);
    }
}
```

<callout emoji="⚠️><p>这条 pipeline 是 lesson03（向量库）+ lesson04（Embedding/相似度）+ 本节重排的合体，也是第七章 RAG 检索链的直接前身。学会它，RAG 检索你也会了 80%。</p></callout>

## 十五、Embedding 蒸馏与小型化

生产里大 Embedding 模型（1536 维）贵且慢。蒸馏小模型是趋势。

```text
Embedding 小型化路线：
  ├─ 用大模型（teacher）蒸馏小模型（student），效果保留 95%+
  ├─ 降维：1536 → 768，存储/算力减半，效果略降
  ├─ 量化：float32 → int8，内存砍 4 倍
  └─ 选用专为效率设计的小模型（如 bge-small, gte-small）
```

<callout emoji="💡"><p>成本视角：Embedding 是"每次检索都发生"的高频操作，模型效率直接影响延迟和账单。能用小模型蒸馏版就别用最大的——多数业务感知不到 5% 的效果差异，却能省一半成本。</p></callout>

## 十六、Embedding 服务的弹性与降级

Embedding 模型服务也可能挂。要有降级。

```java
package com.camp.memory;

import java.util.function.Function;

/**
 * Embedding 降级：主模型挂了，用本地轻量模型兜底。
 */
public class EmbeddingResilience {
    private final Function<String, double[]> primary;
    private final Function<String, double[]> fallback;

    public EmbeddingResilience(Function<String, double[]> primary,
                               Function<String, double[]> fallback) {
        this.primary = primary; this.fallback = fallback;
    }

    public double[] embed(String text) {
        try {
            return primary.apply(text);
        } catch (Exception e) {
            // 降级到本地小模型，保可用（精度略降可接受）
            return fallback.apply(text);
        }
    }
}
```

<callout emoji="✅"><p>降级原则：Embedding 挂了整个检索链就断，所以必须有兜底。本地轻量模型虽精度略低，但能保证"至少能检索"。高可用是生产系统的基本要求，不是奢侈选项。</p></callout>

## 小结

这节课把长期记忆的"发动机"讲透了：

1. **Embedding 本质**：把语义编码成高维向量，语义近则向量近；存取必须同一模型，维度要和库对齐。
2. **余弦 vs 欧氏**：余弦看方向（文本首选，不受长度干扰），欧氏看绝对距离；归一化后两者等价（cos 大 ⇔ 欧氏小）。
3. **手写实现**：纯 Java 实现余弦/欧氏，看清"余弦分母=模长乘积→归一化后=点积"的优化本质。
4. **全链路**：文本→Embedding 模型→向量→向量库存；查询同理，配 `similarityThreshold` 挡误召回。
5. **本地化**：Ollama 本地 Embedding + 本地向量库，数据不出网，满足强合规。
6. **回填闭环**：检索到的记忆拼进 prompt 增强生成，是 RAG 雏形。

记住一句话：**长期记忆检索的核心，是用"方向一致"（余弦）而非"字面相同"来衡量相关性——而归一化让余弦退化成最快的点积。**

---

## 延伸阅读 / 引用文档

1. OpenAI Embeddings 文档：<https://platform.openai.com/docs/guides/embeddings>
2. BGE-M3 模型（中英双语 Embedding）：<https://github.com/FlagOpen/FlagEmbedding>
3. Spring AI EmbeddingModel：<https://docs.spring.io/spring-ai/reference/1.0/api/embeddings.html>
4. 余弦相似度 vs 欧氏距离（可视化讲解）：<https://www.geeksforgeeks.org/cosine-similarity/>
5. Faiss（向量检索库，ANN 原理）：<https://github.com/facebookresearch/faiss
6. HuggingFace MTEB 嵌入模型排行榜：<https://huggingface.co/spaces/mteb/leaderboard>

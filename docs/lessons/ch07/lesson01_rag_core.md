# 第七章第一节：检索增强生成（RAG）核心原理：索引-检索-生成三阶段

大家好，我是秋天。

这节课我们要把 RAG（Retrieval-Augmented Generation，检索增强生成）这件事彻底讲透。很多同学一听 RAG 就觉得"不就是向量库查一下再拼进 prompt 吗"，结果一上生产就翻车：召回的文档对不上、答案一本正经胡说、延迟高到用户跑路。RAG 看着简单，真正能扛住企业级流量、扛住 badcase 的 RAG 没几个。

学完这节课，你能掌握三件事：

1. **RAG 的三大阶段（索引 Indexing / 检索 Retrieval / 生成 Generation）各自在解决什么问题**，以及每一阶段最容易踩的坑。
2. **用 Java 17 + LangChain4j / Spring AI 把一条完整 RAG 链路跑起来**，而不是只会调一个 `vectorSearch()` 就完事。
3. **为什么企业里 RAG 要拆成"离线索引"和"在线检索"两条链路**，以及这背后的工程取舍。

<callout emoji="💡"><p>这一能力在招聘 JD 里的对应要求：阿里/腾讯/字节的 Java Agent 岗位几乎都把"RAG 全链路（文档解析→Embedding→向量库→混合检索→重排序）"列为硬性技能。面试官最爱问的就是"你们线上 RAG 的召回率怎么保证"——这节课帮你把这个问题答明白。</p></callout>

---

## 一、先建立一张全局图：RAG 到底在补 LLM 的哪块短板

大模型有三个天生缺陷，RAG 就是来补的：

- **知识截止（Knowledge Cutoff）**：模型训练完就"封版"了，昨天的政策它不知道。
- **幻觉（Hallucination）**：问一个它没把握的，它也会编得有鼻子有眼。
- **缺乏私有数据**：你公司的客服 SOP、合同模板、内部 Wiki，它训练时根本没见过。

RAG 的核心思想一句话：**别让模型"凭记忆答题"，先去"翻资料"再答题**。

```
                 ┌──────────────────────────────────────────┐
                 │             RAG 全局架构                    │
                 └──────────────────────────────────────────┘

  【离线索引链路 / Indexing】               【在线检索生成链路 / Retrieval + Generation】
  ┌─────────────────────────┐              ┌──────────────────────────────────────┐
  │  原始文档 (PDF/Word/HTML) │             │  用户 Query                          │
  │         │                 │             │      │                               │
  │         ▼                 │             │      ▼                               │
  │  文档解析 & 清洗           │             │  Query 改写 / 扩展 (可选)             │
  │         │                 │             │      │                               │
  │         ▼                 │             │      ▼                               │
  │  文本切分 (Chunking)      │             │  Embedding 向量化                    │
  │         │                 │             │      │                               │
  │         ▼                 │             │      ▼                               │
  │  Embedding 模型向量化      │◄───索引────┤  向量库 ANN 检索 (Top-K)              │
  │         │                 │   存储      │      │                               │
  │         ▼                 │             │      ▼                               │
  │  向量库 (Milvus/pgvector) │             │  重排序 Re-rank (可选)               │
  └─────────────────────────┘              │      │                               │
                                           │      ▼                               │
                                           │  拼装 Prompt (Context + Query)        │
                                           │      │                               │
                                           │      ▼                               │
                                           │  LLM 生成答案 + 引用溯源              │
                                           └──────────────────────────────────────┘
```

这一段图你一定要记牢：**索引链路是离线的、可以慢慢跑的；检索生成链路是在线的、对延迟敏感的**。很多初学者把"建索引"也放到请求里实时做，结果一个 100MB 的 PDF 直接把接口卡死——这就是没理解两条链路要解耦。

---

## 二、第一阶段：索引（Indexing）——决定 RAG 上限的"地基"

索引阶段的目标是把非结构化的文档，变成"能被高效检索的向量 + 元数据"。它又细分为四个子步骤：加载、清洗、切分、向量化入库。

### 2.1 文档加载与清洗

原始文档千奇百怪：扫描版 PDF 里是图片、Word 里有表格、HTML 里混着导航栏和广告。加载阶段第一件事是**把噪声去掉**，否则切出来的 chunk 全是导航文字，召回质量直接崩。

```java
// ch07-rag/src/main/java/demo/IndexingPipeline.java
package demo;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;

import java.nio.file.Path;
import java.util.List;

/**
 * 最朴素的索引阶段：加载 -> 切分 -> 向量化 -> 入库
 * 真实工程里"清洗"会单独抽一个 DocumentTransformer 链。
 */
public class IndexingPipeline {

    public static void main(String[] args) {
        // 1) 加载：LangChain4j 内置多种 Loader（FileSystem / ClassPath / S3 / URL）
        Path docPath = Path.of("/data/kb/客服SOP.pdf");
        Document document = FileSystemDocumentLoader.loadDocument(docPath);

        System.out.println("文档字符数: " + document.text().length());
        System.out.println("元数据: " + document.metadata());

        // 2) 清洗：去掉页眉页脚、冗余空白（示意，真实用正则或规则引擎）
        String cleaned = document.text()
                .replaceAll("第\\s*\\d+\\s*页", "")      // 去页码
                .replaceAll("\\n{3,}", "\n\n")          // 压缩空行
                .trim();
        System.out.println("清洗后字符数: " + cleaned.length());
    }
}
```

<callout emoji="⚠️"><p>清洗不是可选项。我曾见过一个项目，PDF 每页底部都有"公司机密 第X页"，没清洗直接切分，结果用户问"怎么退款"，模型召回的全是带"公司机密"的 footer chunk。召回准确率直接掉了 20 个点。清洗这一步省下的功夫，后面全得在 badcase 里还。</p></callout>

### 2.2 切分（Chunking）为什么是门学问

为什么不能直接把整本文档当成一个向量？因为：

1. **向量模型有长度上限**（如 8K token），塞不下整本书。
2. **粒度太粗，检索不精准**：用户问"退款时效"，你召回一整章 5000 字，真正有用的就两句话，浪费 token 还引入噪声。

但切太细也有问题：上下文被割裂，模型拼不出完整答案。所以切分策略是 RAG 调参的第一战场——这一点我们在 lesson02 会专门展开。这里先给一个递归字符切分的骨架。

### 2.3 向量化与入库

切好的文本片段（TextSegment）送进 Embedding 模型变成向量，连同元数据写入向量库。

```java
// ch07-rag/src/main/java/demo/IndexingPipeline.java (续)
package demo;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;

import java.util.List;

public class IndexingPipeline {

    public static void main(String[] args) {
        String apiKey = System.getenv("OPENAI_API_KEY");

        // Embedding 模型：生产推荐 bge-large-zh / text-embedding-3-small
        OpenAiEmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName("text-embedding-3-small")
                .build();

        // 向量库：这里用 pgvector（PostgreSQL 插件，运维友好）
        EmbeddingStore store = PgVectorEmbeddingStore.builder()
                .host("localhost")
                .port(5432)
                .database("ragdb")
                .user("postgres")
                .password(System.getenv("PG_PASSWORD"))
                .table("kb_segments")
                .dimension(1536)
                .build();

        Document document = FileSystemDocumentLoader.loadDocument(
                Path.of("/data/kb/客服SOP.pdf"));

        // 递归字符切分：每块 500 字符，重叠 100 字符（保留上下文连续性）
        DocumentSplitter splitter = DocumentSplitters.recursive(500, 100);
        List<TextSegment> segments = splitter.split(document);

        // 批量向量化并入库（LangChain4j 自动建立 segment 与向量的映射）
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        store.addAll(embeddings, segments);

        System.out.println("入库完成，共 " + segments.size() + " 个 chunk");
    }
}
```

<callout emoji="✅"><p>为什么用 pgvector 而不是一上来就上 Milvus？企业落地有个现实约束：你们 DBA 大概率只会运维 MySQL/PostgreSQL。pgvector 能让你们复用现有 PG 集群，少一个独立中间件就少一份运维负担。等到数据量过亿、召回延迟扛不住时，再平滑迁移到 Milvus/Elasticsearch。技术选型永远是先够用、再升级。</p></callout>

---

## 三、第二阶段：检索（Retrieval）——决定 RAG 下限的"关键一跃"

索引做得再好，检索这一脚踢歪了，前面全白费。检索的本质是：**把用户的自然语言问题，变成向量库里最相似的 Top-K 个 chunk**。

### 3.1 最基础的向量检索

```java
// ch07-rag/src/main/java/demo/RetrievalDemo.java
package demo;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import dev.langchain4j.store.embedding.search.Request;

import java.util.List;

public class RetrievalDemo {
    public static void main(String[] args) {
        String apiKey = System.getenv("OPENAI_API_KEY");

        OpenAiEmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
                .apiKey(apiKey).modelName("text-embedding-3-small").build();

        EmbeddingStore store = PgVectorEmbeddingStore.builder()
                .host("localhost").port(5432).database("ragdb")
                .user("postgres").password(System.getenv("PG_PASSWORD"))
                .table("kb_segments").dimension(1536).build();

        // 把 Query 也向量化
        Embedding queryEmbedding = embeddingModel.embed("退款一般多久到账？").content();

        // 余弦相似度检索 Top-3
        List<TextSegment> hits = store.search(
                Request.builder().queryEmbedding(queryEmbedding).maxResults(3).build()
        ).matches().stream().map(m -> m.embedded()).toList();

        hits.forEach(s -> System.out.println("命中: " + s.text().substring(0, 40) + "..."));
    }
}
```

### 3.2 检索不是"查一次"这么简单

很多同学以为检索就是上面这一坨。但在企业级 RAG 里，检索往往要叠加：

- **混合检索（Hybrid）**：向量召回 + 关键词（BM25）召回，互补长短（lesson04 详讲）。
- **重排序（Re-rank）**：先用向量粗召回 20 条，再用 Cross-Encoder 精排到 Top-3（lesson04 详讲）。
- **元数据过滤**：只查"生效中"的政策、"2024 版"的合同，用 SQL/元数据条件先过滤再向量检索。

```yaml
# ch07-rag/src/main/resources/application-rag.yml
rag:
  retrieval:
    vector:
      enabled: true
      topK: 20            # 粗召回数量，留给 rerank 精排
      metric: cosine
    keyword:
      enabled: true       # BM25 关键词召回，兜底专业术语
      index: elasticsearch
    rerank:
      enabled: true
      model: bge-reranker-large
      finalTopK: 3        # 最终送进 prompt 的条数
    metadataFilter:
      - field: status
        op: eq
        value: active
      - field: version
        op: gte
        value: "2024"
```

<callout emoji="💡"><p>一个判断框架给你：召回率（Recall）靠"粗召回广撒网"保证，精确率（Precision）靠"重排序精筛选"保证。新手常犯的错误是向量检索直接 maxResults=3，等于把精排的机会提前掐死了。先把网撒大（Top-20），再让 reranker 挑真正相关的 3 条。</p></callout>

---

## 四、第三阶段：生成（Generation）——把检索结果"缝合"进答案

检索拿到上下文后，要把它和原始 Query 拼成一个增强 prompt，交给 LLM 生成。这一步的核心工程问题是：**怎么拼，才能让模型既用上资料、又不瞎编**。

### 4.1 用 LangChain4j 的 RetrievalAugmentor 串起全链路

LangChain4j 提供了 `RetrievalAugmentor`，把"检索 → 重排 → 拼装"封装成一条可插拔的链。

```java
// ch07-rag/src/main/java/demo/GenerationDemo.java
package demo;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.content.retriever.WebSearchContentRetriever;
import dev.langchain4j.rag.query.router.DefaultQueryRouter;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Conversational;
import dev.langchain4j.store.embedding.EmbeddingStore;

import java.util.List;

public class GenerationDemo {

    interface Assistant {
        @Conversational
        String answer(String userMessage);
    }

    public static void main(String[] args) {
        String apiKey = System.getenv("OPENAI_API_KEY");

        ChatLanguageModel chatModel = OpenAiChatModel.builder()
                .apiKey(apiKey).modelName("gpt-4o-mini").build();

        EmbeddingStore store = PgVectorEmbeddingStore.builder()
                .host("localhost").port(5432).database("ragdb")
                .user("postgres").password(System.getenv("PG_PASSWORD"))
                .table("kb_segments").dimension(1536).build();

        // 向量召回器
        EmbeddingStoreContentRetriever vectorRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(store)
                .embeddingModel(OpenAiEmbeddingModel.builder()
                        .apiKey(apiKey).modelName("text-embedding-3-small").build())
                .maxResults(3)
                .minScore(0.75)          // 相似度阈值，低于此值不送入 prompt
                .build();

        // 路由：把 query 同时发给多个 retriever（这里演示向量库）
        DefaultQueryRouter router = new DefaultQueryRouter(List.of(vectorRetriever));
        RetrievalAugmentor augmentor = DefaultRetrievalAugmentor.builder()
                .queryRouter(router)
                .build();

        Assistant assistant = AiServices.builder(Assistant.class)
                .chatLanguageModel(chatModel)
                .retrievalAugmentor(augmentor)
                .build();

        String ans = assistant.answer("我要退昨天买的那双鞋，怎么操作？");
        System.out.println(ans);
    }
}
```

### 4.2 拼装 Prompt 的模板长什么样

LangChain4j 默认会把检索到的内容拼进一个模板。你也可以自己定义，关键要**强制模型"只基于给定资料作答、并标注来源"**，这是抑制幻觉的关键一招。

```text
你是一个严谨的企业客服助手。请仅根据下面提供的【参考资料】回答用户问题。
如果参考资料中没有相关信息，请明确回答"我暂时查不到相关信息"，不要编造。

【参考资料】
---
{context}
---

【用户问题】
{user_question}

【回答要求】
1. 给出明确、可执行的步骤。
2. 在答案末尾用 [来源: 文档名#段落编号] 标注依据。
```

<callout emoji="⚠️"><p>永远不要对模型说"请根据你的知识回答"再叠加检索内容，这会鼓励它用训练记忆覆盖检索事实。正确姿态是"仅依据给定资料"，把模型的"自由发挥权"收掉。这是 RAG 能否抑制幻觉的分水岭。</p></callout>

---

## 五、把三段串成一条流水线：Spring AI 的 Advisors 视角

Spring AI 走的是 Advisor（拦截器）路线，RAG 被拆成 `QuestionAnswerAdvisor` 这一类组件，挂在 `ChatClient` 的调用链上。这一套对 Spring 工程师更亲切。

```java
// ch07-rag/src/main/java/demo/SpringAiRagConfig.java
package demo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SpringAiRagConfig {

    @Bean
    public ChatClient ragChatClient(ChatClient.Builder builder,
                                    VectorStore vectorStore) {
        return builder
            .defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                    .topK(3)
                    .similarityThreshold(0.75)
                    .build())
                // 自定义检索内容的拼装模板
                .userTextAdvise("{question}\n请仅依据以下资料回答：\n{question_answer_context}")
                .build())
            .build();
    }
}
```

对应的 `application.yml`：

```yaml
# ch07-rag/src/main/resources/application.yml
spring:
  application:
    name: ch07-rag
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: gpt-4o-mini
      embedding:
        options:
          model: text-embedding-3-small
    vectorstore:
      pgvector:
        host: localhost
        port: 5432
        database: ragdb
        user: postgres
        password: ${PG_PASSWORD}
        dimensions: 1536
```

<callout emoji="💡"><p>LangChain4j 的 RetrievalAugmentor 和 Spring AI 的 QuestionAnswerAdvisor 是同一件事的两种表达：都是在"模型调用前"插入一段"检索并拼装上下文"的逻辑。你会用哪个，取决于你们团队的技术栈。但底层三段论（索引/检索/生成）思想完全一致——学透原理，框架只是皮。</p></callout>

---

## 六、企业落地场景：为什么要这样设计

给你一个真实画像——某电商客服 Agent：

```
用户问："我上周买的那件羽绒服，吊牌剪了能退吗？"
        │
        ▼
【索引链路已提前建好】
  退换货政策.pdf → 切分 → 向量化 → pgvector
  历史工单.xlsx  → 切分 → 向量化 → pgvector
        │
        ▼
【在线链路】
  Query: "吊牌剪了 羽绒服 能否退货"
   → 向量召回 Top-20（含"剪标不退""影响二次销售不退"等条款）
   → rerank 精排 Top-3
   → 拼装 prompt：仅依据资料作答
   → LLM：明确告知"剪标影响二次销售，按政策不予退货"，并附 [来源: 退换货政策#3.2]
```

**为什么这样设计？** 三个工程考量：

1. **准确性优先于创造性**：客服场景答错一条就可能引发投诉甚至监管风险，所以"仅依据资料 + 标注来源"是硬约束。
2. **延迟可控**：粗召回 20 条 + rerank 3 条，整条链路 P99 控制在 800ms 内，用户无感。
3. **可追溯**：每条答案带来源，出现 badcase 时研发能立刻定位是"召回错了"还是"模型理解错了"，形成评测闭环（第十六章细讲）。

<callout emoji="✅"><p>招聘映射：JD 里常写"熟悉 RAG 在企业知识库/智能客服中的落地"。上面这个链路就是标准答案的骨架——你能讲清楚"为什么检索要分粗召回和精排""为什么 prompt 要强制标注来源"，比背 API 名字值钱得多。</p></callout>

---

## 七、常见翻车点与排查清单

| 症状 | 根因 | 排查方向 |
|------|------|----------|
| 召回的内容牛头不对马嘴 | chunk 切太大/重叠不足 | 调整切分策略，lesson02 |
| 答案一本正经胡说 | prompt 没约束"仅依据资料" | 收紧系统提示，加来源要求 |
| 专业术语搜不到 | 纯向量检索对精确词不敏感 | 加 BM25 关键词召回，lesson04 |
| 延迟高到超时 | 粗召回太多 / rerank 模型重 | 降 TopK，rerank 模型换轻量版 |
| 新文档查不到 | 索引链路没跑/增量没更新 | 检查离线 pipeline 调度 |

---

## 八、RAG 的离线/在线链路解耦（工程落地关键）

前面反复强调"索引离线、检索在线"，这里给一个真实的生产调度设计：

```yaml
# ch07-rag/src/main/resources/pipeline-schedule.yml
indexing:
  # 全量索引：每天凌晨低峰，重建全库（防增量漂移）
  full-rebuild:
    cron: "0 3 * * *"
    source: s3://kb-raw/
    chunk-size: 600
    overlap: 120
  # 增量索引：文件变更事件触发，分钟级入庫
  incremental:
    trigger: file-event | message-queue
    max-batch: 200
  # 重建期间不中断在线服务（双索引热切换）
  blue-green: true
retrieval:
  online: true
  timeout-ms: 800
```

```
离线索引(慢,可重试)          在线检索(快,低延迟)
  ┌────────────┐              ┌────────────┐
  │ 文档事件    │              │ 用户Query   │
  │   ↓        │              │   ↓        │
  │ 解析→切分   │  双写/热切换  │ 向量召回    │
  │   ↓        │ ───────────► │   ↓        │
  │ Embedding  │              │ rerank     │
  │   ↓        │              │   ↓        │
  │ 写索引B    │              │ 生成        │
  └────────────┘              └────────────┘
   (索引A在线服务中，B建好后切换)
```

<callout emoji="✅"><p>蓝绿双索引是生产 RAG 的标配：重建索引时不中断服务，新索引建好原子切换。否则每次全量重建都要停服，业务方会杀了你。这一设计直接对应"高可用"的招聘要求。</p></callout>

---

## 九、RAG 质量评测指标（接第十六章）

RAG 好不好不能拍脑袋，要量化。核心指标：

| 指标 | 衡量 | 工具 |
|------|------|------|
| Recall@K | 相关文档是否在前K被召回 | RAGAS/人工 |
| Context Precision | 召回内容相关性 | RAGAS |
| Answer Faithfulness | 答案是否忠于上下文(不幻觉) | LLM-as-Judge |
| Answer Relevancy | 答案是否切题 | RAGAS |

```java
// ch07-rag/src/main/java/demo/eval/RagEvalProbe.java
package demo.eval;

public class RagEvalProbe {
    /** 离线探针：给定标准答案集，自动算 Recall@K */
    public static double recallAtK(java.util.List<String> relevant,
                                   java.util.List<String> retrieved, int k) {
        int hit = 0;
        for (int i = 0; i < Math.min(k, retrieved.size()); i++)
            if (relevant.contains(retrieved.get(i))) hit++;
        return (double) hit / relevant.size();
    }
}
```

---

## 十、常见 badcase 诊断树

```
答案不对，先定位是哪个阶段出问题：
├─ 检索为空/很少  → 索引阶段：文档未解析/未入库/embedding维度错
├─ 检索到但无关  → 检索阶段：chunk切错/无混合检索/无rerank
├─ 检索对但答错  → 生成阶段：prompt未约束"仅依据资料"/模型理解错
├─ 答非所问      → query阶段：未改写/指代未消解(lesson07)
└─ 答得好但慢    → 链路：粗召回太多/rerank模型重/无缓存
```

<callout emoji="💡"><p>这道诊断树是面试和生产的双用法宝。被问"RAG 答错了你怎么排查"，按"索引→检索→生成→query"四段逐层剥，比瞎猜专业得多。也是第十六章评测闭环的入口。</p></callout>

## 小结

这节课我们沿"索引—检索—生成"三段论把 RAG 拆开讲了一遍：

- **索引**决定上限：清洗、切分、向量化、入库，离线慢慢跑。
- **检索**决定下限：粗召回广撒网保证召回率，重排序精筛选保证精确率。
- **生成**是缝合点：用"仅依据资料 + 标注来源"的强约束抑制幻觉。
- 框架只是皮：LangChain4j 的 `RetrievalAugmentor` 和 Spring AI 的 `QuestionAnswerAdvisor` 底层思想一致。
- 企业落地永远先够用再升级，pgvector 起步、Milvus 兜底，工程取舍比技术炫技重要。

下一节我们钻进索引阶段最关键的切分与元数据提取（lesson02），把"为什么你的 chunk 总切不对"讲透。

---

## 延伸阅读 / 引用文档

1. Lewis et al., *Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks*（RAG 开山论文）: https://arxiv.org/abs/2005.11401
2. LangChain4j RAG 官方文档（RetrievalAugmentor）: https://docs.langchain4j.dev/tutorials/rag
3. Spring AI RAG 文档（QuestionAnswerAdvisor）: https://docs.spring.io/spring-ai/reference/api/Advisors.html
4. pgvector 官方仓库: https://github.com/pgvector/pgvector
5. OpenAI Embeddings 文档: https://platform.openai.com/docs/guides/embeddings
6. Microsoft *RAG: State of the Art* 技术报告: https://www.microsoft.com/en-us/research/publication/retrieval-augmented-generation-rag-and-beyond/

# 第七章第七节：【新增】查询改写、上下文压缩与引用溯源

大家好，我是秋天。

检索拿到 chunk 之后，就直接拼进 prompt 扔给模型？太天真。真实生产里还有三道关键工序没做：**查询改写（Query Rewriting）**让模糊问题变清晰、**上下文压缩（Context Compression）**把塞进 prompt 的冗余砍掉、**引用溯源（Citation）**让答案可核查。这三件事决定了 RAG 从"能跑"到"能用"。

这节课是 V2 新增深化章节。学完你能掌握：

1. **查询改写**：多轮对话里怎么把"它""这个"还原成完整意图，以及 HyDE（假设性文档嵌入）这种进阶技巧。
2. **上下文压缩**：检索回 20 条但 prompt 塞不下 / 噪声大时，怎么用 LLM 或重排器提纯。
3. **引用溯源**：让模型每句话都标注来源，badcase 可定位、答案可核查。

<callout emoji="💡"><p>招聘映射：JD 明确点名"查询改写、上下文压缩、引用溯源"。这三项直接对应"检索质量"和"答案可信度"两条招聘高频要求，是 RAG 工程师和"调 API 的人"的分水岭。</p></callout>

---

## 一、查询改写（Query Rewriting）：先让问题"说人话"

### 1.1 为什么需要改写

两类典型场景：

- **多轮对话指代消解**：用户先问"退款要多久"，接着问"那换货呢？"——第二个 query 的"那换货"指代的是"时效"。直接拿"那换货呢"去检索，向量根本 Match 不上"换货时效"。
- **口语化 / 模糊 query**："钱啥时候能回来"应该改写成"退款到账时间"。

### 1.2 多轮对话的查询重写

```java
// ch07-rag/src/main/java/demo/rewrite/ConversationRewrite.java
package demo.rewrite;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;

import java.util.List;

public class ConversationRewrite {

    interface Rewriter {
        @UserMessage("""
            你是一个查询改写器。基于【对话历史】把【当前问题】改写成
            一个独立、无歧义、可被向量检索的查询。不要回答，只输出改写后的查询。
            【对话历史】
            {{history}}
            【当前问题】
            {{current}}
            """)
        String rewrite(String history, String current);
    }

    public static void main(String[] args) {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini").build();
        Rewriter rewriter = AiServices.create(Rewriter.class, model);

        String history = "用户: 退款一般多久到账？\n助手: 审核通过后24小时内。";
        String current = "那换货呢？";   // 模糊指代
        String rewritten = rewriter.rewrite(history, current);
        System.out.println("改写后: " + rewritten);  // "换货的办理时效是多久"
    }
}
```

### 1.3 HyDE：假设性文档嵌入（进阶）

HyDE 的思路很妙：**先让 LLM 针对问题"编"一段假想的答案文档，再把这段假文档向量化去检索**。因为"假答案"和"真答案"在向量空间里比"问题"和"真答案"更接近，召回率反而更高。

```
传统:  Query → Embedding → 检索
HyDE:  Query → LLM生成假答案 → Embedding(假答案) → 检索
```

```java
// ch07-rag/src/main/java/demo/rewrite/HyDE.java
package demo.rewrite;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.search.Request;

public class HyDE {
    public static String searchWithHyDE(String query,
                                        ChatLanguageModel llm,
                                        EmbeddingModel emb,
                                        EmbeddingStore store) {
        // 1) 让 LLM 生成一段"假设性答案"
        String hypothetical = llm.generate(
            "请写一段可能回答下面问题的文本（不必真实准确）：" + query).content();
        // 2) 用假设答案的向量去检索，而非原问题
        var qe = emb.embed(hypothetical).content();
        var hits = store.search(Request.builder().queryEmbedding(qe).maxResults(3).build());
        return hits.matches().stream()
                .map(m -> m.embedded().text()).reduce("", (a, b) -> a + "\n" + b);
    }

    public static void main(String[] args) {
        ChatLanguageModel llm = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY")).modelName("gpt-4o-mini").build();
        EmbeddingModel emb = OpenAiEmbeddingModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("text-embedding-3-small").build();
        System.out.println("HyDE 检索示意完成");
    }
}
```

<callout emoji="⚠️"><p>HyDE 的坑：它生成的"假答案"可能包含错误事实，如果直接喂给最终生成模型会引入幻觉。正确用法是 HyDE 只用于"检索阶段找相似文档"，拿到真文档后，**生成阶段只用真文档**，假答案在检索完就丢弃。别把假答案送进最终 prompt。</p></callout>

---

## 二、上下文压缩（Context Compression）：少即是多

### 2.1 为什么要压缩

检索召回 20 条、rerank 到 5 条，每条 500 字，拼进 prompt 就是 2500 字。问题是：

- **长上下文 ≠ 好效果**：研究证明 LLM 对超长 context 中间部分"注意力稀释"（lost in the middle）。
- **浪费 token 和钱**：很多召回段落只有一两句相关，其余是噪声。

上下文压缩 = 把"大段召回"压成"只相关的句子/片段"。

### 2.2 压缩方式一：LLM 抽取相关句

```java
// ch07-rag/src/main/java/demo/compress/LlmCompressor.java
package demo.compress;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;

import java.util.List;

public class LlmCompressor {

    interface Compressor {
        @UserMessage("""
            从下面的【检索内容】中抽取与【用户问题】直接相关的句子，
            删除无关内容。只输出保留的句子，不要解释。
            【用户问题】{{question}}
            【检索内容】{{context}}
            """)
        String compress(String question, String context);
    }

    public static void main(String[] args) {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini").build();
        Compressor c = AiServices.create(Compressor.class, model);

        String ctx = "退款审核需1工作日。夏季新品满300减50。换货需寄回瑕疵品。冬装不支持7天无理由。";
        String compressed = c.compress("退款审核要多久", ctx);
        System.out.println("压缩后: " + compressed);  // 只保留"退款审核需1工作日"
    }
}
```

### 2.3 压缩方式二：用重排分数做"软截断"

不调 LLM，直接用 rerank 分数，只保留分数高于阈值的句子，零额外成本：

```java
// ch07-rag/src/main/java/demo/compress/ScoreBasedCompress.java
package demo.compress;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.search.RelevantSegment;

import java.util.List;
import java.util.stream.Collectors;

public class ScoreBasedCompress {
    /** 只保留 rerank 分数 >= threshold 的片段，其余丢弃 */
    public static String compress(List<RelevantSegment> hits, double threshold) {
        return hits.stream()
                .filter(h -> h.score() >= threshold)
                .map(h -> h.embedded().text())
                .collect(Collectors.joining("\n"));
    }
}
```

<callout emoji="💡"><p>两种压缩怎么选？LLM 抽取质量高但多一次调用（慢、贵），适合对答案精度极端敏感的场景；分数截断零成本但可能截断边界句，适合大多数场景。实战里常"分数截断打底 + 关键 query 才上 LLM 抽取"。这也呼应了第十六章的成本治理思想。</p></callout>

### 2.4 LangChain4j 的 ContextCompressor 思路

LangChain4j 提供 `ContentCompressor` 接口，把压缩做成可插拔组件挂在检索链上：

```java
// ch07-rag/src/main/java/demo/compress/CompressorConfig.java
package demo.compress;

import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.router.DefaultQueryRouter;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.injector.DefaultContentInjector;

import java.util.List;

public class CompressorConfig {
    public static RetrievalAugmentor build(EmbeddingStoreContentRetriever retriever) {
        // 检索 → (这里可插入压缩器) → 注入
        return DefaultRetrievalAugmentor.builder()
                .queryRouter(new DefaultQueryRouter(List.of((ContentRetriever) retriever)))
                // contentInjector 决定如何把压缩后的内容拼进 prompt
                .contentInjector(DefaultContentInjector.builder().build())
                .build();
    }
}
```

---

## 三、引用溯源（Citation）：让答案"可核查"

### 3.1 为什么必须有引用

没有引用的 RAG 答案，用户不敢信、出了错你查不到。引用溯源解决两件事：

1. **可信度**：用户能点开来源自己核实。
2. **可调试**：badcase 时研发能定位"是哪条召回错了"还是"模型理解错了"。

### 3.2 给每个 chunk 打上稳定 ID

```java
// ch07-rag/src/main/java/demo/citation/ChunkId.java
package demo.citation;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import java.util.UUID;

public class ChunkId {
    /** 给每个 chunk 生成稳定 ID（文档名+页码+序号），用于引用 */
    public static TextSegment withCitation(Document doc, String text, int index) {
        Metadata md = doc.metadata().copy();
        String citeId = doc.metadata().getString("source")
                + "#p" + doc.metadata().get("page") + "#c" + index;
        md.put("cite_id", citeId);
        return TextSegment.from(text, md);
    }
}
```

### 3.3 强制模型输出带引用的答案

```java
// ch07-rag/src/main/java/demo/citation/CitedAnswer.java
package demo.citation;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public class CitedAnswer {

    interface CitedAssistant {
        @SystemMessage("""
            你是一个严谨的助手。只依据【资料】回答，并在每句事实后
            用 [来源: cite_id] 标注。若资料无相关信息，回答"暂无可查证资料"。
            【资料】
            {{context}}
            """)
        String answer(String context, @UserMessage String question);
    }

    public static void main(String[] args) {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o").build();
        CitedAssistant a = AiServices.create(CitedAssistant.class, model);

        String ctx = "[c1] 退款审核通过后24小时内原路退回。\n[c2] 吊牌完好方可退货。";
        String ans = a.answer(ctx, "退款多久到账？");
        System.out.println(ans);  // 退款审核通过后24小时内原路退回。[来源: c1]
    }
}
```

### 3.4 Spring AI 的引用实现思路

```java
// ch07-rag/src/main/java/demo/citation/SpringAiCitation.java
package demo.citation;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.VectorStore;

public class SpringAiCitation {
    public static ChatClient buildCitedRag(VectorStore vs, ChatClient.Builder b) {
        return b.defaultAdvisors(
            QuestionAnswerAdvisor.builder(vs)
                .userTextAdvise("{question}\n仅依据以下资料，每句事实标注[来源]:\n{question_answer_context}")
                .build())
            .build();
    }
}
```

<callout emoji="✅"><p>企业落地的硬要求：金融、医疗、政企的 RAG 答案，**没有引用几乎不可能过合规审查**。把"引用溯源"当成 RAG 的必选组件，而不是可选项。前端展示时把 [来源: c1] 做成可点击链接，体验和专业感立刻拉满。</p></callout>

---

## 四、三件套串进完整检索-生成链路

```yaml
# ch07-rag/src/main/resources/post-retrieval.yml
post-retrieval:
  query-rewrite:
    enabled: true
    multi-turn: true        # 多轮指代消解
    hyde:                   # 进阶，按场景开启
      enabled: false
      note: 仅用于检索阶段，假答案不入最终prompt
  context-compression:
    strategy: score-threshold   # score-threshold | llm-extract
    threshold: 0.3
    llm-model: gpt-4o-mini
  citation:
    enabled: true
    chunk-id-format: "{source}#p{page}#c{idx}"
    render: clickable-link
```

```
检索结果(Top-20)
      │
      ▼
 [查询改写] 多轮指代消解 / HyDE（可选）
      │
      ▼
 [上下文压缩] score截断 或 LLM抽取相关句
      │  精简后的相关片段(+ cite_id)
      ▼
 [引用溯源] 拼装"仅依据资料+标注来源"的 prompt
      │
      ▼
   LLM 生成带引用的答案
```

<callout emoji="💡"><p>一个判断框架：查询改写解决"问得不清"，上下文压缩解决"给得太多"，引用溯源解决"说得不准查不到"。三者分别在检索前、检索后、生成时三道关口把关。漏掉任何一道，RAG 质量都上不去。</p></callout>

---

## 五、企业落地场景：客诉 Agent 的可信回答

某银行客诉 Agent 的真实链路：

```
用户: "我上个月那笔退款怎么还没到？"
  │
  ▼ 查询改写 → "查询用户上月退款订单的到账状态与时效"
  ▼ 检索 → 退款政策 + 该用户订单记录(带 cite_id)
  ▼ 上下文压缩 → 只留"到账时效24h"和"该笔订单状态:处理中"
  ▼ 引用溯源 → 答案: "您的退款已于X日处理，预计24小时内到账[来源:订单#20251012]。
                         依据政策到账时效为24小时[来源:退款政策#3.1]"
```

**为什么这样设计？** 客诉场景"答错即投诉、投诉即舆情"。改写保证问得准、压缩保证答得聚焦、引用保证可核查。三方协同，把"可能出错"堵在生成之前。

---

## 六、踩坑清单

| 坑 | 后果 | 解法 |
|----|------|------|
| 多轮不重写直接检索 | 指代丢失召回错 | 加对话历史做重写 |
| HyDE 假答案进最终prompt | 引入幻觉 | 假答案仅用于检索 |
| 不压缩直接塞长context | 中间注意力稀释 | 分数截断/LLM抽取 |
| 答案无引用 | 不可信不可查 | 强制 cite_id 标注 |
| 引用 ID 不稳定 | 来源对不上 | chunk ID 确定性生成 |

---

## 八、端到端后处理 Pipeline 示例

把查询改写、压缩、引用串成完整骨架：

```java
// ch07-rag/src/main/java/demo/post/PostRetrievalPipeline.java
package demo.post;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import demo.rewrite.ConversationRewrite;
import demo.compress.ScoreBasedCompress;

import java.util.List;

public class PostRetrievalPipeline {
    private final ChatLanguageModel model;

    public PostRetrievalPipeline(ChatLanguageModel m) { this.model = m; }

    public String process(String rawQuery, String history,
                         List<TextSegment> retrieved) {
        // 1. 查询改写（多轮指代消解）
        String query = ConversationRewrite.rewrite(model, history, rawQuery);
        // 2. (此处省略检索，假设 retrieved 已基于改写后 query 拿到)
        // 3. 上下文压缩（score 截断）
        String compressed = ScoreBasedCompress.compress(retrieved, 0.3);
        // 4. 引用溯源的 prompt 由 CitedAnswer 组装
        return compressed; // 交给 CitedAnswer 生成带来源答案
    }
}
```

---

## 九、三道工序的评测（接第十六章）

| 工序 | 评测指标 | 方法 |
|------|----------|------|
| 查询改写 | 改写后 Recall@5 提升 | A/B 对比有/无改写 |
| 上下文压缩 | 答案 Faithfulness 不降 | 压缩前后答案一致性 |
| 引用溯源 | 引用准确率 | 人工抽检来源标注 |

<callout emoji="💡"><p>引用溯源的评测特别重要：不仅要看"有没有标来源"，更要看"标的对不对"。标了错误来源比没标更危险——用户被误导去查一个不存在的出处。评测集要包含"来源正确性"这一项。</p></callout>

---

## 十、生产后处理的降级

```
降级优先级：
  正常: 改写 + 压缩 + 引用
  降级1: 关 HyDE(lesson07第一节, 省一次LLM)
  降级2: 压缩改 score 截断(去 LLM 抽取)
  降级3: 引用降级为"文档名"不标段落
```

## 十一、引用溯源的前端呈现

后端标好 `[来源: c1]`，前端要渲染成可点击链接。给你一个 JSON 结构设计：

```json
{
  "answer": "退款审核通过后24小时内原路退回[1]。吊牌完好方可退货[2]。",
  "citations": [
    {"id": 1, "source": "退款政策2024.pdf", "page": 3, "chunk": 12, "url": "/kb/refund.pdf#page=3"},
    {"id": 2, "source": "退换货规则.docx", "page": 1, "chunk": 5, "url": "/kb/return.docx#page=1"}
  ]
}
```

```java
// ch07-rag/src/main/java/demo/citation/CitationRenderer.java
package demo.citation;

import com.fasterxml.jackson.annotation.JsonProperty;

public class CitationRenderer {
    public record Citation(@JsonProperty("id") int id,
                           @JsonProperty("source") String source,
                           @JsonProperty("page") int page) {}
    // 前端用正则把 [1] 替换为 <a href="url">[1]</a>
}
```

<callout emoji="✅"><p>引用可点击是"可信 RAG"的用户体感关键。金融/医疗场景合规要求"每句结论能追到原文"，前端不做成链接，这个合规价值就打折了。后端给结构化 citations，前端负责渲染，职责清晰。</p></callout>

---

## 十二、查询改写与压缩的协同陷阱

| 陷阱 | 后果 | 解法 |
|------|------|------|
| 改写后语义漂移 | 召回跑偏 | 改写结果人工抽检 |
| HyDE 假答案泄露 | 幻觉 | 仅用于检索(lesson07) |
| 压缩过度 | 丢关键信息 | 阈值别太高 |
| 引用 ID 与压缩脱节 | 来源对不上 | 压缩保留 cite_id |

---

## 十三、检查清单

| 能力 | 是否具备 |
|------|----------|
| 多轮指代消解 | ☐ |
| HyDE（按需） | ☐ |
| 上下文压缩 | ☐ |
| 强制引用溯源 | ☐ |
| 引用可点击呈现 | ☐ |

## 小结

这节课补齐了检索-生成的"最后一公里"三道工序：

- **查询改写**：多轮指代消解 + HyDE（假答案仅用于检索），让模糊问题变清晰、召回更准。
- **上下文压缩**：分数截断（零成本）或 LLM 抽取（高质量），砍掉噪声、聚焦相关句。
- **引用溯源**：给 chunk 打稳定 cite_id，强制模型标注来源，答案可核查、badcase 可定位。
- 三者分别在检索前/后/生成时把关，是企业级 RAG 可信度的基石。

下一节（lesson08）我们讲多知识库路由与企业级 RAG 架构，把整章收口到"生产怎么搭"。

---

## 延伸阅读 / 引用文档

1. HyDE 论文 *Precise Zero-Shot Dense Retrieval*: https://arxiv.org/abs/2212.10496
2. LangChain4j Context Compression / ContentInjector: https://docs.langchain4j.dev/tutorials/rag
3. Lost in the Middle（长上下文注意力研究）: https://arxiv.org/abs/2307.03172
4. Spring AI QuestionAnswerAdvisor: https://docs.spring.io/spring-ai/reference/api/Advisors.html
5. RAG 引用溯源实践（LlamaIndex Citation）：https://docs.llamaindex.ai/
6. Microsoft GraphRAG 引用机制: https://github.com/microsoft/graphrag

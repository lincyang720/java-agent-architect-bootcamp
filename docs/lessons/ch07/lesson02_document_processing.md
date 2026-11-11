# 第七章第二节：文档处理技术：文本分割策略（递归字符分割、语义分割）、元数据提取

大家好，我是秋天。

上节课我们搭起了 RAG 三段论的骨架，但你把一整本 PDF 直接丢进向量库，效果一定烂——因为"怎么切"这件事，直接决定了检索的颗粒度对不对。这节课我们钻进索引链路最容易被轻视、却最影响效果的环节：**文档处理（Document Processing）**，重点讲两件事——文本分割（Chunking）和元数据提取。

学完这节课，你能掌握：

1. **递归字符分割（Recursive Character Splitting）为什么是默认首选**，以及 chunk size / overlap 怎么调。
2. **语义分割（Semantic / Context-aware Splitting）的原理与适用场景**，什么时候该从"按字数切"升级到"按意思切"。
3. **元数据提取怎么给你的 RAG 加上"过滤器"和"可追溯性"**，这是企业级 RAG 和玩具 RAG 的分水岭。

<callout emoji="💡"><p>招聘映射：JD 里常见"熟悉文档预处理、文本切分策略"。面试官问"chunk size 设多少合适"，你要能回答"取决于模型上下文窗口、文档类型和检索目标，没有银弹"，并讲出递归切分和语义切分的取舍——这节就是答案。</p></callout>

---

## 一、为什么切分是 RAG 的"隐形天花板"

先讲一个残酷的事实：**RAG 的效果，70% 取决于索引阶段（切分 + 向量化），只有 30% 取决于检索和生成的花活**。很多团队在重排序、Agentic RAG 上卷半天，却连 chunk 都切得稀烂，属于典型"在二楼和一楼吵架"。

切分要解决三个矛盾：

- **颗粒度 vs 上下文**：切太细，一个完整步骤被劈成两半，模型拼不出逻辑；切太粗，召回时噪声大、token 浪费、相似度被稀释。
- **重叠 vs 冗余**：完全不重叠，边界处的语义可能断裂；重叠太多，同一条信息多次进 prompt，浪费 token 还可能让模型重复。
- **结构 vs 扁平**：代码、表格、Markdown 标题层级是有语义边界的，纯按字符数切会破坏结构。

```
            切分粒度对检索的影响
  ┌───────────────────────────────────────────────┐
  │  太细 (100字)      │ 召回准但缺上下文，答案碎片化 │
  │  适中 (400-800字)  │ ★ 大多数场景的甜区          │
  │  太粗 (3000字+)    │ 召回全但噪声大、相似度被稀释 │
  └───────────────────────────────────────────────┘
            ▲
            │ 重叠(overlap)在相邻 chunk 间保留
            │ 一段共享文本，避免边界语义断裂
   [chunk1 文字……‖重叠区‖文字……chunk2]
```

---

## 二、递归字符分割（Recursive Character Splitting）

### 2.1 原理：先按"结构"切，再按"字符"兜底

递归字符分割的核心思想是**尊重文档的结构边界**。它按一组"分隔符优先级"递归地往下切：

```
优先级（从高到低）：
1. 段落 / 章节边界（如 "\n\n"、"\n### "、Markdown 标题）
2. 句子边界（"。"、". "、"！"、"？"）
3. 词语边界（空格、逗号）
4. 字符（兜底，任意位置硬切）
```

只有当"按当前分隔符切出来的块仍然超过 chunk size"时，才降级到下一个更细的分隔符继续切。这样能最大程度保留语义完整块（比如一整段话）不被腰斩。

### 2.2 LangChain4j 实现

```java
// ch07-rag/src/main/java/demo/RecursiveSplitting.java
package demo;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;

import java.nio.file.Path;
import java.util.List;

public class RecursiveSplitting {
    public static void main(String[] args) {
        Document doc = FileSystemDocumentLoader.loadDocument(
                Path.of("/data/kb/产品白皮书.md"));

        // 递归切分：每块 600 字符，相邻块重叠 120 字符
        List<TextSegment> segments = DocumentSplitters.recursive(600, 120)
                .split(doc);

        System.out.println("切出 chunk 数: " + segments.size());
        segments.forEach(s ->
                System.out.println("[" + s.text().length() + "字] " +
                        s.text().substring(0, Math.min(30, s.text().length())) + "..."));
    }
}
```

### 2.3 用 Python 直观看切分效果（对照理解）

很多团队用 Python 做离线索引 pipeline，再喂给 Java 在线服务。给你一个等价的 LangChain 实现，方便你做对照和工程对齐：

```python
# ch07-rag/scripts/recursive_split.py
from langchain_text_splitters import RecursiveCharacterTextSplitter

with open("/data/kb/产品白皮书.md", "r", encoding="utf-8") as f:
    text = f.read()

splitter = RecursiveCharacterTextSplitter(
    chunk_size=600,        # 与目标块大小
    chunk_overlap=120,     # 重叠，保持边界语义连续
    # 中文场景建议显式指定分隔符，默认的 \n\n / \n / 空格对中文不够
    separators=["\n## ", "\n### ", "\n\n", "\n", "。", "！", "？", "；", "，", ""],
    keep_separator=True,
)

chunks = splitter.split_text(text)
print(f"切出 {len(chunks)} 个 chunk")
for i, c in enumerate(chunks[:3]):
    print(f"[{len(c)}字] {c[:30]}...")
```

<callout emoji="⚠️"><p>中文 RAG 的一个大坑：默认的英文分隔符（按空格断词）对中文几乎无效，因为中文没有词间空格。你必须把"。"、"！"、"？"、"；"放进 separators，否则递归切分退化成"按字符硬切"，破坏句子边界。这一点 Python 和 Java 两边都要改。</p></callout>

### 2.4 chunk size / overlap 怎么定？给一套决策表

| 文档类型 | 建议 chunk size | overlap | 理由 |
|----------|----------------|---------|------|
| FAQ / 短问答 | 200–300 | 30–50 | 单条即完整语义，无需大块 |
| 政策 / SOP 文档 | 400–800 | 80–150 | 一条操作步骤需完整保留 |
| 技术文档 / 论文 | 800–1200 | 150–200 | 概念跨段，需更大上下文 |
| 代码仓库 | 按函数/类切 | 小 | 用 AST 切分优于字符切分 |
| 法律合同 | 512–1024 | 100–200 | 条款关联强，需保上下文 |

<callout emoji="💡"><p>一个实战经验：overlap 一般取 chunk size 的 10%–20%。太小边界断裂，太大浪费 token 还引入重复。先用 15% 起步，用第十六章的评测闭环去量化调优，别拍脑袋。</p></callout>

---

## 三、语义分割（Semantic / Context-aware Splitting）

### 3.1 为什么递归切分还不够

递归字符切分本质上是"无脑按长度"。但考虑这种情况：一段 800 字的文本里，前半讲"退款流程"、后半讲"换货流程"，硬按 500 字切，结果一个 chunk 里混了两种主题——检索时只要命中其中一个主题，就得把另一个无关主题一起喂给模型，噪声就来了。

**语义分割的思路**：不按长度，而按"语义是否发生了跳转"来切。两个相邻句子的 embedding 相似度突然下降，说明主题变了，就在那儿切一刀。

```
句子序列的 embedding 余弦相似度折线：
1.0 │●●●●●‾‾‾‾‾‾‾‾‾‾‾‾‾‾●
0.8 │     ●●        ●●
0.6 │       ●      ●
0.4 │        ●    ●
0.2 │         ●  ●
0.0 └─────────────────────
     退 退 退 退 换 换 换 换
     款 款 款 款 货 货 货 货
           ↑ 相似度断崖 → 在这里切分
```

### 3.2 语义切分的实现（基于句子相似度断点）

```java
// ch07-rag/src/main/java/demo/SemanticSplitting.java
package demo;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 语义切分简化实现：按句子 embedding 相似度断点切分。
 * 生产可用 LangChain4j 的 SemanticTextSplitter 或自研。
 */
public class SemanticSplitting {

    record Sentence(String text, Embedding emb) {}

    public static List<String> split(List<String> rawSentences,
                                     OpenAiEmbeddingModel model,
                                     double threshold) {
        List<Sentence> sents = rawSentences.stream()
                .map(s -> new Sentence(s, model.embed(s).content()))
                .collect(Collectors.toList());

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        Embedding prev = null;

        for (Sentence s : sents) {
            if (prev != null) {
                double sim = prev.cosineSimilarity(s.emb);
                if (sim < threshold && current.length() > 0) {
                    chunks.add(current.toString().trim());
                    current.setLength(0);
                }
            }
            current.append(s.text()).append(" ");
            prev = s.emb;
        }
        if (current.length() > 0) chunks.add(current.toString().trim());
        return chunks;
    }

    public static void main(String[] args) {
        OpenAiEmbeddingModel model = OpenAiEmbeddingModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("text-embedding-3-small").build();

        List<String> sentences = List.of(
            "退款申请提交后，系统会在24小时内审核。",
            "审核通过后，款项原路退回。",
            "换货流程需要先寄回瑕疵商品。",
            "仓库验收无误后发出新商品。"
        );
        // threshold 越低越容易切分；0.55 是经验起点
        List<String> chunks = split(sentences, model, 0.55);
        System.out.println("语义切分出 " + chunks.size() + " 块");
        chunks.forEach(System.out::println);
    }
}
```

### 3.3 语义切分的代价与边界

语义切分不是银弹，它有明显的成本：

- **要为每个句子调一次 Embedding 模型**，文档越大，embedding 调用次数越多，离线条成本上升。
- **相似度阈值难调**，不同领域文档的"语义跳跃"幅度不一样。
- **无法保证块大小**，可能出现极长或极短的 chunk，给向量库和 prompt 拼装带来变数。

<callout emoji="✅"><p>我的建议：先用递归字符切分打底（便宜、可控、稳定），对"主题跳跃明显"的长文档（如技术白皮书、研报）叠加语义切分做二次优化。别一上来就全量语义切分，成本会教育你。</p></callout>

---

## 四、代码 / 结构化文档的切分：AST 才是正解

纯文本切分对代码、JSON、表格是失效的。一个函数被切成两半，检索"怎么调用 XXX 方法"时只能召回半截，模型根本拼不出完整签名。

```java
// ch07-rag/src/main/java/demo/CodeSplitter.java
package demo;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 用 JavaParser 按方法粒度切分 Java 源码，保留结构化边界 */
public class CodeSplitter {
    public static List<String> splitByMethod(File javaFile) throws Exception {
        var cu = JavaParser.parse(javaFile);
        List<String> chunks = new ArrayList<>();

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
            String classSig = "class " + cls.getName() + " {";
            cls.findAll(MethodDeclaration.class).forEach(m -> {
                // 每个方法作为一个 chunk，天然语义完整
                chunks.add(classSig + "\n  " + m.getSignature() + " {\n" +
                        m.getBody().map(b -> b.toString()).orElse("") + "\n  }");
            });
        });
        return chunks;
    }

    public static void main(String[] args) throws Exception {
        List<String> chunks = splitByMethod(new File("/data/src/OrderService.java"));
        System.out.println("按方法切出 " + chunks.size() + " 个代码块");
    }
}
```

对应的 `pom.xml` 依赖（AST 解析需要）：

```xml
<!-- ch07-rag/pom.xml 片段 -->
<dependency>
    <groupId>com.github.javaparser</groupId>
    <artifactId>javaparser-core</artifactId>
    <version>3.25.10</version>
</dependency>
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-core</artifactId>
    <version>0.35.0</version>
</dependency>
```

---

## 五、元数据提取：让 RAG 从"全文盲搜"升级到"精准过滤"

### 5.1 元数据解决三个问题

没有元数据的 chunk 只是一段漂浮的文本。加上元数据后，你的检索可以从"盲搜"变成"带条件的精准检索"：

1. **过滤**：只查"生效中"的政策、"2024版"合同——用 `status=active` 先过滤再向量检索。
2. **加权**："官方文档"权重高于"用户论坛帖"，rerank 时加分。
3. **溯源**：答案能标注"来源：退换货政策 v2024 #3.2"，方便用户核实和研发定位。

### 5.2 元数据的来源

```
元数据的三类来源：
┌─────────────────────────────────────────────┐
│ 1. 文件系统自带：文件名、路径、修改时间、大小    │
│ 2. 文档结构提取：标题、章节、页码、作者          │
│ 3. LLM 抽取：让模型从文本里抽出"主题/实体/日期" │
└─────────────────────────────────────────────┘
```

### 5.3 用 LLM 抽取结构化元数据

```java
// ch07-rag/src/main/java/demo/MetadataExtractor.java
package demo;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;

public class MetadataExtractor {

    interface Extractor {
        @UserMessage("""
            请从下面的文本中抽取元数据，返回 JSON：
            { "topic": 主题, "entities": [实体列表],
              "docDate": 文档日期(YYYY-MM-DD或null),
              "category": 分类(政策/技术/合同/FAQ) }
            文本：
            {{text}}
            """)
        String extract(String text);
    }

    public static void main(String[] args) {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini").build();

        Extractor extractor = AiServices.create(Extractor.class, model);
        String text = "根据2024年3月发布的《退换货管理政策》，吊牌完好...";
        String json = extractor.extract(text);
        System.out.println("抽取元数据: " + json);

        // 转成 LangChain4j Metadata 写入向量库
        Metadata md = Metadata.from(json); // 生产用 Jackson 解析更稳
        System.out.println("category=" + md.getString("category"));
    }
}
```

### 5.4 入库时带上元数据（pgvector 示例）

```java
// ch07-rag/src/main/java/demo/IndexWithMetadata.java
package demo;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;

import java.nio.file.Path;
import java.util.List;

public class IndexWithMetadata {
    public static void main(String[] args) {
        String apiKey = System.getenv("OPENAI_API_KEY");
        OpenAiEmbeddingModel emb = OpenAiEmbeddingModel.builder()
                .apiKey(apiKey).modelName("text-embedding-3-small").build();
        EmbeddingStore store = PgVectorEmbeddingStore.builder()
                .host("localhost").port(5432).database("ragdb")
                .user("postgres").password(System.getenv("PG_PASSWORD"))
                .table("kb_segments").dimension(1536).build();

        Document doc = FileSystemDocumentLoader.loadDocument(
                Path.of("/data/kb/退换货政策2024.pdf"));

        // 切分时把 Document 的元数据继承到每个 segment
        List<TextSegment> segments = DocumentSplitters.recursive(600, 120)
                .split(doc);

        List<Embedding> embeddings = emb.embedAll(segments).content();
        store.addAll(embeddings, segments);

        // 检索时可用元数据过滤（伪代码示意）
        // store.search(Request.builder().filter("category = '政策' AND status = 'active'")...)
    }
}
```

<callout emoji="💡"><p>一个企业级设计要点：元数据尽量在"离线索引"阶段算好存进去，不要放到"在线检索"时才去 LLM 抽取——那样每次查询都多一次 LLM 调用，延迟和成本都扛不住。元数据是"一次计算、反复使用"的典型。第十六章评测时你会发现，好的元数据过滤能让精确率直接上一个台阶。</p></callout>

---

## 六、企业落地场景：客服知识库怎么切

给你一个真实配置——某银行智能客服的文档处理 pipeline：

```yaml
# ch07-rag/src/main/resources/pipeline.yml
document-processing:
  loaders:
    - type: pdf
      parser: pdfbox          # 见 lesson06 文档解析
    - type: docx
      parser: poi
    - type: xlsx
      parser: easyexcel
  cleaners:
    - strip-headers-footers
    - remove-control-chars
    - normalize-whitespace
  splitters:
    - apply: recursive          # 默认打底
      chunk-size: 600
      overlap: 120
      separators: ["\n## ", "\n### ", "\n\n", "。", "！", "？", "；", "，", ""]
    - apply: semantic           # 长文档二次优化
      only-for: [whitepaper, report]
      threshold: 0.55
    - apply: ast                # 代码类
      only-for: [java, python]
  metadata:
    auto: [filename, path, modified, page]
    llm-extract: [topic, entities, docDate, category]
    enforce:                   # 入库前校验，缺字段直接告警
      - category
```

**为什么这样设计？** 三类文档（政策 PDF、技术白皮书、源码）用三套切分策略，而不是一刀切。政策文档重准确性用递归切；白皮书主题跳跃用语义切；源码用 AST 切。元数据在离线算好，让在线检索能"先按 category 过滤、再向量召回"，既快又准。

<callout emoji="✅"><p>招聘映射：JD 里写"具备文档预处理、知识库构建经验"。你能画出上面这种"按文档类型分流处理"的 pipeline 架构图，并讲清每类文档为什么用不同切分策略，这就不是"调过 API"的水平，而是"设计过系统"的水平。</p></callout>

---

## 七、踩坑清单

| 坑 | 现象 | 解法 |
|----|------|------|
| 中文按空格切 | chunk 在词中间断裂 | separators 加中文标点 |
| overlap 过大 | 答案重复、token 浪费 | 降到 chunk 的 10–15% |
| 全量语义切分 | 索引成本爆炸 | 仅长文档用，递归打底 |
| 代码按字符切 | 方法被劈开 | 用 AST/正则按结构切 |
| 元数据在线抽 | 延迟高、成本高 | 离线算好入库 |

---

## 八、端到端文档处理 Pipeline 示例

把前面所有处理步骤串成一个完整可运行骨架：

```java
// ch07-rag/src/main/java/demo/ProcessingPipeline.java
package demo;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;

import java.nio.file.Path;
import java.util.List;

public class ProcessingPipeline {
    public static void run(String filePath, EmbeddingStore store,
                          OpenAiEmbeddingModel emb) {
        // 1. 加载
        Document doc = FileSystemDocumentLoader.loadDocument(Path.of(filePath));
        // 2. 清洗(示意)
        String cleaned = doc.text().replaceAll("\\n{3,}", "\n\n").trim();
        // 3. 切分(递归, 中文 separators 已在 lesson02 强调)
        List<TextSegment> segments = DocumentSplitters.recursive(600, 120)
                .split(Document.from(cleaned, doc.metadata()));
        // 4. 向量化入库
        List<Embedding> embeddings = emb.embedAll(segments).content();
        store.addAll(embeddings, segments);
        System.out.println("处理完成: " + segments.size() + " chunks");
    }
}
```

---

## 九、切分策略的评测（接第十六章）

切分好坏要靠检索效果反推。给你一个评测思路：

```
评测方法：
  1. 准备一批 (问题, 标准答案所在chunk) 标注对
  2. 用不同 chunk_size/overlap 建索引
  3. 跑检索，算 Recall@5
  4. 选 Recall 最高的参数组合
```

| chunk_size | overlap | Recall@5 | 备注 |
|------------|---------|----------|------|
| 300 | 50 | 0.71 | 太碎，上下文断 |
| 600 | 120 | 0.85 | 甜区 |
| 1000 | 200 | 0.82 | 噪声略增 |

<callout emoji="💡"><p>参数不是拍出来的，是评测跑出来的。第十六章会讲 RAGAS 和离线评测集，这里先建立"切分参数要量化调优"的意识。很多企业 RAG 效果差，根因就是 chunk 参数是随便设的。</p></callout>

---

## 十、文档处理的运维要点

| 要点 | 做法 |
|------|------|
| 解析失败隔离 | 单文件异常跳过+告警，不影响全量 |
| 增量更新 | 文件变更事件触发局部重切分 |
| 版本管理 | 文档版本→chunk版本可追溯 |
| 存储分离 | 原文(对象存储)与向量(向量库)分离 |

## 小结

这节课我们钻进了索引链路最关键的文档处理环节：

- **递归字符分割**是默认首选，尊重结构边界、按长度兜底；中文务必自定义 separators。
- **语义分割**按 embedding 相似度断点切，适合主题跳跃的长文档，但要付出 embedding 调用成本，别全量上。
- **代码/结构化文档**用 AST 切分（如 JavaParser），按方法/类保留结构。
- **元数据**让 RAG 从盲搜升级到精准过滤 + 溯源，且必须在离线阶段算好。
- 企业落地按文档类型分流处理，没有"一刀切"的银弹。

下一节我们专门讲 Embedding 模型——文本到底是怎么变成向量的，以及 bge / text-embedding-3 / m3e 这些模型该怎么选（lesson03）。

---

## 延伸阅读 / 引用文档

1. LangChain Text Splitters 文档: https://python.langchain.com/docs/concepts/text_splitters/
2. LangChain4j Document Splitters: https://docs.langchain4j.dev/integrations/document-loaders-and-splitters/
3. Recursive Character Text Splitting 原理（Greg Kamradt 分享）: https://github.com/FullStackRetrieval-com/RetrievalTutorials
4. 语义切分论文 *Dense X Retrieval*（命题级检索）: https://arxiv.org/abs/2312.06648
5. BGE Embedding 系列（智源）: https://github.com/FlagOpen/FlagEmbedding
6. pgvector 元数据过滤语法: https://github.com/pgvector/pgvector#metadata

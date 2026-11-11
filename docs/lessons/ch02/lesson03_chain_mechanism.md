# 第2章第3节：Chain机制：链式调用的实现原理与配置

大家好，我是秋天。

上一节课我们把 AiServices 讲透了，知道了如何用声明式接口快速构建一个 Agent。

这节课，我们来讲 LangChain4j 里另一个重要的概念：**Chain（链）机制**。

Chain 是 LangChain 系列框架的核心设计思想之一。它的本质是：**把多个处理步骤串联起来，形成一条可复用的处理管道。**

学完这节课，你会掌握：

1. Chain 机制的设计思想和适用场景
2. LangChain4j 内置 Chain 的使用方式
3. 如何自定义 Chain 实现复杂的链式逻辑
4. Chain 与 AiServices 的关系和选型建议
5. 生产环境中 Chain 的最佳实践

---

## 一、为什么需要 Chain

在讲 Chain 之前，先思考一个问题：

如果你要实现一个"文档摘要 + 翻译"的功能，流程是：

```Plain Text
原始文档 → 摘要 → 翻译成英文 → 输出

```

用 AiServices 当然可以做，但你需要手动调用两次：

```Java
String summary = summaryAgent.summarize(document);
String translated = translationAgent.translate(summary, "英文");

```

这还好。但如果流程更复杂：

```Plain Text
文档 → 预处理 → 摘要 → 关键词提取 → 情感分析 → 格式化输出

```

每一步都要手动串联，代码会变得很冗长，而且难以复用。

**Chain 解决的就是这个问题：把多步处理流程封装成一个可复用的管道，让调用方只需要关心输入和输出。**

---

## 二、LangChain4j 中的 Chain 体系

LangChain4j 提供了几个开箱即用的内置 Chain，覆盖了最常见的使用场景。

### 内置 Chain 一览

| Chain 类型 | 功能 | 适用场景 |
|-|-|-|
| `ConversationalChain` | 带记忆的对话链 | 多轮对话 |
| `ConversationalRetrievalChain` | 带 RAG 的对话链 | 知识库问答 |

这两个是 LangChain4j 目前最核心的内置 Chain。

---

## 三、ConversationalChain：最简单的对话链

`ConversationalChain` 是最基础的 Chain，它把模型调用和记忆管理封装在一起：

```Java
import dev.langchain4j.chain.ConversationalChain;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;

// 创建模型
ChatLanguageModel model = OpenAiChatModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-4o")
        .build();

// 创建对话链
ConversationalChain chain = ConversationalChain.builder()
        .chatLanguageModel(model)
        .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
        .build();

// 使用对话链
String response1 = chain.execute("我叫张三，是一名 Java 开发者");
String response2 = chain.execute("我在北京工作");
String response3 = chain.execute("你还记得我是谁吗？");
// 输出：你叫张三，是一名在北京工作的 Java 开发者

```

`ConversationalChain` 的内部流程：

```Plain Text
用户输入
    ↓
从 ChatMemory 读取历史消息
    ↓
把历史消息 + 当前输入组合成完整上下文
    ↓
调用 ChatLanguageModel
    ↓
把新的消息对（用户消息 + AI 回复）写入 ChatMemory
    ↓
返回 AI 回复

```

---

## 四、ConversationalRetrievalChain：带 RAG 的对话链

`ConversationalRetrievalChain` 在对话链的基础上，加入了 RAG 检索能力：

```Java
import dev.langchain4j.chain.ConversationalRetrievalChain;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.retriever.EmbeddingStoreRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

// 第一步：准备知识库
EmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("text-embedding-3-small")
        .build();

EmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();

// 加载文档并向量化
List<Document> documents = FileSystemDocumentLoader.loadDocuments("docs/");
EmbeddingStoreIngestor.builder()
        .embeddingModel(embeddingModel)
        .embeddingStore(embeddingStore)
        .build()
        .ingest(documents);

// 第二步：创建检索器
EmbeddingStoreRetriever retriever = EmbeddingStoreRetriever.from(embeddingStore, embeddingModel, 3);

// 第三步：创建带 RAG 的对话链
ConversationalRetrievalChain chain = ConversationalRetrievalChain.builder()
        .chatLanguageModel(model)
        .retriever(retriever)
        .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
        .build();

// 使用
String answer = chain.execute("LangChain4j 支持哪些向量数据库？");

```

`ConversationalRetrievalChain` 的内部流程：

```Plain Text
用户输入
    ↓
用输入内容检索知识库（向量相似度搜索）
    ↓
把检索到的相关文档片段注入到上下文
    ↓
从 ChatMemory 读取历史消息
    ↓
组合：系统提示词 + 检索内容 + 历史消息 + 当前输入
    ↓
调用 ChatLanguageModel
    ↓
更新 ChatMemory
    ↓
返回回复

```

---

## 五、自定义 Chain：实现复杂的链式逻辑

内置 Chain 覆盖了常见场景，但生产环境里往往需要更复杂的链式逻辑。LangChain4j 允许你通过组合多个步骤来实现自定义 Chain。

### 方式一：直接组合 AiServices

最简单的自定义 Chain，就是把多个 AiServices 串联起来：

```Java
// 定义各个处理步骤的 Agent
interface SummaryAgent {
    @SystemMessage("你是一个专业的文档摘要助手，生成简洁准确的摘要，不超过 200 字。")
    @UserMessage("请对以下内容生成摘要：\n\n{{content}}")
    String summarize(@V("content") String content);
}

interface TranslationAgent {
    @SystemMessage("你是一个专业翻译，保持原文语气和风格。")
    @UserMessage("请将以下{{sourceLang}}文本翻译成{{targetLang}}：\n\n{{text}}")
    String translate(
            @V("sourceLang") String sourceLang,
            @V("targetLang") String targetLang,
            @V("text") String text);
}

interface KeywordAgent {
    @UserMessage("从以下文本中提取 5 个最重要的关键词，用逗号分隔：\n\n{{text}}")
    String extractKeywords(@V("text") String text);
}

```

```Java
// 组装成处理管道
public class DocumentProcessingPipeline {

    private final SummaryAgent summaryAgent;
    private final TranslationAgent translationAgent;
    private final KeywordAgent keywordAgent;

    public DocumentProcessingPipeline(ChatLanguageModel model) {
        this.summaryAgent = AiServices.create(SummaryAgent.class, model);
        this.translationAgent = AiServices.create(TranslationAgent.class, model);
        this.keywordAgent = AiServices.create(KeywordAgent.class, model);
    }

    public DocumentAnalysisResult process(String document) {
        // 第一步：生成摘要
        String summary = summaryAgent.summarize(document);

        // 第二步：翻译摘要
        String englishSummary = translationAgent.translate("中文", "英文", summary);

        // 第三步：提取关键词
        String keywords = keywordAgent.extractKeywords(document);

        return new DocumentAnalysisResult(summary, englishSummary, keywords);
    }
}

```

### 方式二：实现 Chain 接口

如果需要更精细的控制，可以实现 LangChain4j 的 `Chain` 接口：

```Java
import dev.langchain4j.chain.Chain;

public class SentimentAnalysisChain implements Chain<String, SentimentResult> {

    private final ChatLanguageModel model;
    private final ChatMemory memory;

    public SentimentAnalysisChain(ChatLanguageModel model) {
        this.model = model;
        this.memory = MessageWindowChatMemory.withMaxMessages(5);
    }

    @Override
    public SentimentResult execute(String input) {
        // 第一步：情感分析
        String sentimentPrompt = "分析以下文本的情感倾向（正面/负面/中性），只返回一个词：\n" + input;
        String sentiment = callModel(sentimentPrompt);

        // 第二步：提取情感原因
        String reasonPrompt = "文本：" + input + "\n情感：" + sentiment + "\n请简要说明情感判断的原因（不超过 50 字）：";
        String reason = callModel(reasonPrompt);

        // 第三步：给出置信度
        String confidencePrompt = "基于以上分析，给出置信度（0-100 的整数）：";
        String confidence = callModel(confidencePrompt);

        return new SentimentResult(sentiment, reason, Integer.parseInt(confidence.trim()));
    }

    private String callModel(String prompt) {
        memory.add(new UserMessage(prompt));
        AiMessage response = model.generate(memory.messages()).content();
        memory.add(response);
        return response.text();
    }
}

```

---

## 六、Chain 的提示词模板配置

Chain 的行为很大程度上由提示词模板决定。LangChain4j 提供了灵活的模板配置方式：

### 自定义系统提示词

```Java
import dev.langchain4j.chain.ConversationalRetrievalChain;
import dev.langchain4j.model.input.PromptTemplate;

// 自定义 RAG 的提示词模板
String promptTemplate = """
        你是一个专业的技术文档助手。
        
        请根据以下参考资料回答用户的问题：
        
        {{information}}
        
        用户问题：{{userMessage}}
        
        回答要求：
        1. 只根据参考资料中的内容回答
        2. 如果资料中没有相关信息，明确告知用户
        3. 回答要简洁准确，不超过 300 字
        """;

ConversationalRetrievalChain chain = ConversationalRetrievalChain.builder()
        .chatLanguageModel(model)
        .retriever(retriever)
        .promptTemplate(PromptTemplate.from(promptTemplate))
        .build();

```

### 动态提示词注入

```Java
// 根据用户角色动态调整提示词
public class RoleAwareChain {

    private final ChatLanguageModel model;

    public String execute(String userRole, String question) {
        String systemPrompt = buildSystemPrompt(userRole);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(question));

        return model.generate(messages).content().text();
    }

    private String buildSystemPrompt(String role) {
        return switch (role) {
            case "developer" -> "你是一个资深 Java 架构师，用技术语言回答，可以包含代码示例。";
            case "manager" -> "你是一个技术顾问，用业务语言回答，避免过多技术细节。";
            case "beginner" -> "你是一个耐心的编程导师，用简单易懂的语言解释，多举生活中的例子。";
            default -> "你是一个通用助手，根据问题的性质调整回答风格。";
        };
    }
}

```

---

## 七、Chain 的错误处理与重试

生产环境中，Chain 需要处理各种异常情况：

```Java
import dev.langchain4j.exception.HttpException;

public class ResilientChain {

    private final ConversationalChain chain;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1000;

    public String execute(String input) {
        int attempts = 0;
        Exception lastException = null;

        while (attempts < MAX_RETRIES) {
            try {
                return chain.execute(input);
            } catch (HttpException e) {
                lastException = e;
                attempts++;

                if (e.statusCode() == 429) {
                    // 限流，等待后重试
                    log.warn("Rate limited, attempt {}/{}, waiting {}ms", attempts, MAX_RETRIES, RETRY_DELAY_MS * attempts);
                    sleep(RETRY_DELAY_MS * attempts);
                } else if (e.statusCode() >= 500) {
                    // 服务端错误，重试
                    log.warn("Server error {}, attempt {}/{}", e.statusCode(), attempts, MAX_RETRIES);
                    sleep(RETRY_DELAY_MS);
                } else {
                    // 客户端错误，不重试
                    throw new RuntimeException("Chain execution failed: " + e.getMessage(), e);
                }
            }
        }

        throw new RuntimeException("Chain execution failed after " + MAX_RETRIES + " attempts", lastException);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

```

---

## 八、Chain 与 AiServices 的关系

很多同学会问：Chain 和 AiServices 有什么区别？什么时候用哪个？

### 核心区别

| 维度 | AiServices | Chain |
|-|-|-|
| 定义方式 | 声明式接口 + 注解 | 命令式构建器 |
| 适合场景 | 单一职责的 Agent | 多步骤处理管道 |
| 灵活性 | 高（注解驱动） | 更高（完全可编程） |
| 代码量 | 少 | 相对多 |
| 可读性 | 很好 | 取决于实现 |

### 选型建议

**用 AiServices 的场景：**

- 定义一个有明确职责的 Agent（客服、代码审查、翻译等）
- 需要工具调用、记忆管理、RAG 的完整 Agent
- 团队更熟悉声明式风格

**用 Chain 的场景：**

- 需要把多个 AI 处理步骤串联成管道
- 每个步骤的输出是下一步的输入
- 需要在步骤之间做条件判断或分支处理
- 需要更精细地控制每一步的行为

**实际上，两者经常结合使用：**

```Java
// 用 AiServices 定义各个专职 Agent
SummaryAgent summaryAgent = AiServices.create(SummaryAgent.class, model);
TranslationAgent translationAgent = AiServices.create(TranslationAgent.class, model);

// 用 Chain 把它们串联成处理管道
public class ContentProcessingChain implements Chain<String, ProcessedContent> {

    @Override
    public ProcessedContent execute(String rawContent) {
        String summary = summaryAgent.summarize(rawContent);
        String translation = translationAgent.translate("中文", "英文", summary);
        return new ProcessedContent(summary, translation);
    }
}

```

---

## 九、完整示例：文档分析 Chain

把上面的知识点整合成一个完整的生产级示例：

```Java
// 定义输出结构
record DocumentAnalysis(
    String summary,           // 中文摘要
    String englishSummary,    // 英文摘要
    List<String> keywords,    // 关键词列表
    String sentiment,         // 情感倾向
    String category           // 文档分类
) {}

```

```Java
// 定义各个处理 Agent
interface DocumentAnalysisAgents {

    @SystemMessage("你是专业的文档分析助手。")
    @UserMessage("请对以下文档生成不超过 150 字的中文摘要：\n\n{{doc}}")
    String summarize(@V("doc") String doc);

    @UserMessage("请将以下中文翻译成英文，保持专业风格：\n\n{{text}}")
    String translateToEnglish(@V("text") String text);

    @UserMessage("从以下文档中提取 5 个最重要的关键词，以 JSON 数组格式返回，如 [\"关键词1\", \"关键词2\"]：\n\n{{doc}}")
    List<String> extractKeywords(@V("doc") String doc);

    @UserMessage("分析以下文档的情感倾向，只返回：正面、负面 或 中性：\n\n{{doc}}")
    String analyzeSentiment(@V("doc") String doc);

    @UserMessage("将以下文档分类到最合适的类别（技术文档/产品文档/用户反馈/新闻资讯/其他），只返回类别名称：\n\n{{doc}}")
    String classify(@V("doc") String doc);
}

```

```Java
// 组装成 Chain
@Component
public class DocumentAnalysisChain implements Chain<String, DocumentAnalysis> {

    private final DocumentAnalysisAgents agents;

    public DocumentAnalysisChain(ChatLanguageModel model) {
        this.agents = AiServices.create(DocumentAnalysisAgents.class, model);
    }

    @Override
    public DocumentAnalysis execute(String document) {
        // 并行执行独立步骤（实际项目中可用 CompletableFuture 并行化）
        String summary = agents.summarize(document);
        String englishSummary = agents.translateToEnglish(summary);
        List<String> keywords = agents.extractKeywords(document);
        String sentiment = agents.analyzeSentiment(document);
        String category = agents.classify(document);

        return new DocumentAnalysis(summary, englishSummary, keywords, sentiment, category);
    }
}

```

```Java
// 使用
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    @Autowired
    private DocumentAnalysisChain analysisChain;

    @PostMapping("/analyze")
    public ResponseEntity<DocumentAnalysis> analyze(@RequestBody String document) {
        DocumentAnalysis result = analysisChain.execute(document);
        return ResponseEntity.ok(result);
    }
}

```

---

## 十、常见坑和最佳实践

### 坑一：Chain 步骤过多导致延迟累积

每个步骤都是一次模型调用，步骤越多，总延迟越高。

**解决方案**：对独立步骤使用并行调用：

```Java
public DocumentAnalysis execute(String document) {
    // 并行执行独立步骤
    CompletableFuture<String> summaryFuture =
            CompletableFuture.supplyAsync(() -> agents.summarize(document));
    CompletableFuture<List<String>> keywordsFuture =
            CompletableFuture.supplyAsync(() -> agents.extractKeywords(document));
    CompletableFuture<String> sentimentFuture =
            CompletableFuture.supplyAsync(() -> agents.analyzeSentiment(document));

    // 等待所有步骤完成
    String summary = summaryFuture.join();
    List<String> keywords = keywordsFuture.join();
    String sentiment = sentimentFuture.join();

    // 翻译依赖摘要，串行执行
    String englishSummary = agents.translateToEnglish(summary);

    return new DocumentAnalysis(summary, englishSummary, keywords, sentiment);
}

```

### 坑二：中间步骤的错误没有被正确处理

Chain 中任何一步失败，整个 Chain 就会失败。

**解决方案**：为每个步骤加降级处理：

```Java
private String safeExecute(Supplier<String> step, String fallback) {
    try {
        return step.get();
    } catch (Exception e) {
        log.error("Chain step failed, using fallback", e);
        return fallback;
    }
}

// 使用
String sentiment = safeExecute(
        () -> agents.analyzeSentiment(document),
        "未知"
);

```

### 最佳实践：Chain 要有明确的输入输出契约

```Java
// 好的设计：输入输出类型明确
public class ContentChain implements Chain<ContentInput, ContentOutput> {
    record ContentInput(String text, String language, String targetAudience) {}
    record ContentOutput(String processed, List<String> tags, double qualityScore) {}
}

// 不好的设计：用 String 传递所有内容，容易出错
public class ContentChain implements Chain<String, String> { ... }

```

---

## 十一、本节小结

这节课我们把 LangChain4j 的 Chain 机制讲透了。核心要点：

- **Chain 的本质**：把多个处理步骤封装成可复用的管道，输入 → 处理 → 输出
- **内置 Chain**：`ConversationalChain`（带记忆的对话）、`ConversationalRetrievalChain`（带 RAG 的对话）
- **自定义 Chain**：通过组合 AiServices 或实现 `Chain` 接口来构建复杂处理管道
- **Chain vs AiServices**：单一职责用 AiServices，多步骤管道用 Chain，两者可以结合使用
- **生产实践**：独立步骤并行化、每步加降级处理、输入输出类型明确

Chain 机制让你能把复杂的 AI 处理流程模块化、可复用化。掌握了它，你就能构建出更复杂、更健壮的 AI 应用。

下一节课，我们会深入 Tool（工具）的定义与注册机制，看看 Function Calling 在 LangChain4j 底层是如何实现的。

---

## 十二、引用文档与延伸阅读

- LangChain4j Chain 官方文档：https://docs.langchain4j.dev/tutorials/chains
- LangChain4j ConversationalChain 源码：https://github.com/langchain4j/langchain4j/blob/main/langchain4j/src/main/java/dev/langchain4j/chain/ConversationalChain.java
- LangChain4j ConversationalRetrievalChain 源码：https://github.com/langchain4j/langchain4j/blob/main/langchain4j/src/main/java/dev/langchain4j/chain/ConversationalRetrievalChain.java
- LangChain4j RAG 文档：https://docs.langchain4j.dev/tutorials/rag
- LangChain4j 记忆管理文档：https://docs.langchain4j.dev/tutorials/chat-memory
- Java CompletableFuture 官方文档：https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletableFuture.html
- GitHub 示例代码：https://github.com/langchain4j/langchain4j-examples

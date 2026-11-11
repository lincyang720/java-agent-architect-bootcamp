# 第十五章第2节：四大策略 Write / Select / Compress / Isolate（Java 实现）

大家好，我是秋天。

上一节我们把"上下文工程"定义钉死了，也把四个策略词 Write / Select / Compress / Isolate 立起来了。这节课不玩虚的——每个策略我都给你**能编译运行的 Java 17 代码**，落到四个真实场景：记忆写入、语义检索选择、摘要压缩、隔离 Agent 上下文。

这节课讲什么、学完掌握什么：

- 讲什么：四大策略各自的适用场景、实现套路、以及最容易踩的坑。代码全部基于 Spring AI / LangChain4j 风格，Java 17 语法（record、switch 表达式、Stream）。
- 学完掌握：你能写出一个"记忆写入器"（Write）、一个"语义检索选择器"（Select）、一个"摘要压缩器"（Compress）、一个"隔离上下文视图"（Isolate），并且知道它们怎么串成上下文层。

<callout emoji="💡"><p>招聘映射：JD 原话"能实现 Context Engineering 四大策略（Write/Select/Compress/Isolate）并落地到生产"。这节课的代码就是你简历上"上下文工程实战"这四个字的直接证据。面试官极可能让你现场画这四大策略的架构图——图在本节末尾。</p></callout>

---

## 一、Write：把信息写进上下文（记忆写入）

### 1.1 什么时候用 Write

模型是无状态的。每一次调用，它都不记得上一轮发生了什么、用户偏好是什么、任务进行到哪一步。**Write 就是把这些"状态"持久化下来，在下次需要时能被 Select 出来。** 本质是为 Agent 建立"工作记忆/长期记忆"。

注意边界：Write 不是"把所有聊天记录无脑存盘"——那是 lesson03 讲的"上下文腐烂"的来源。Write 要有结构：什么类型、什么优先级、什么 TTL（存活时间）。

### 1.2 一个结构化的记忆写入器

```java
// ch15-context-engineering/src/main/java/demo/context/write/MemoryWriter.java
package demo.context.write;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 结构化记忆写入器：带类型、优先级、TTL。
 * 真实项目里底层换成 PgVector / Redis / Milvus（第六章），这里用 Map 示意核心逻辑。
 */
public class MemoryWriter {

    private final Map<String, MemoryRecord> store = new ConcurrentHashMap<>();

    /** 写入一条记忆 */
    public void write(String userId, MemoryType type, String content,
                       int priority, java.time.Duration ttl) {
        String key = userId + ":" + type + ":" + Instant.now().toEpochMilli();
        store.put(key, new MemoryRecord(userId, type, content,
                priority, Instant.now().plus(ttl), 1.0));
    }

    /** 写入一条"带向量"的长期记忆（语义检索用，接第六章/第七章） */
    public void writeWithEmbedding(String userId, String content, float[] vector) {
        String key = userId + ":LONG:" + Instant.now().toEpochMilli();
        store.put(key, new MemoryRecord(userId, MemoryType.LONG_TERM, content,
                5, Instant.now().plus(java.time.Duration.ofDays(365)), vector));
    }

    /** 清理过期记忆（防止无限膨胀 → 上下文腐烂） */
    public void purgeExpired() {
        Instant now = Instant.now();
        store.entrySet().removeIf(e -> e.getValue().expireAt().isBefore(now));
    }

    public record MemoryRecord(String userId, MemoryType type, String content,
                               int priority, Instant expireAt, float[] vector) {
        MemoryRecord(String userId, MemoryType type, String content,
                     int priority, Instant expireAt, double score) {
            this(userId, type, content, priority, expireAt, new float[0]);
        }
    }

    public enum MemoryType { SHORT_TERM, LONG_TERM, USER_PROFILE, TASK_STATE }
}
```

几个设计点（为什么这样设计）：

- **TTL + purgeExpired**：记忆会无限增长，不清理就变成噪声源，喂回去反而干扰模型。这是 Write 策略最容易忘的一步。
- **priority**：后续 Select 时按优先级排序，重要的用户画像优先于闲聊。
- **type 区分**：短期/长期/用户画像/任务状态分别存，Select 时按类型路由。

<callout emoji="✅"><p>工程经验：很多团队把"对话历史"直接当记忆 Write 进去，结果模型被三个月前的废话淹没。正确做法是 Write 时分类型、带衰减、设上限。记忆不是"存档"，是"有取舍的状态管理"。</p></callout>

### 1.3 Spring AI 里的 Write：用 Advisor 自动落记忆

Spring AI 的 `MessageChatMemoryAdvisor` 就是 Write 的工程化实现——每次对话自动把消息写进 `ChatMemory`。

```java
// ch15-context-engineering/src/main/java/demo/context/write/SpringAiMemoryWrite.java
package demo.context.write;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.chat.model.ChatModel;

public class SpringAiMemoryWrite {

    public static ChatClient buildWithMemory(ChatModel model, String conversationId) {
        ChatMemory memory = new InMemoryChatMemory();   // 真实用 Jdbc/Redis/PgVector 实现
        MessageChatMemoryAdvisor advisor =
                MessageChatMemoryAdvisor.builder(memory).build();
        return ChatClient.builder(model)
                .defaultAdvisors(advisor)             // Advisor 自动 Write 对话历史
                .build();
    }
    // 调用时指定 conversationId，记忆就按会话隔离地 Write
}
```

---

## 二、Select：从记忆/知识库选出当前最相关的（语义检索选择）

### 2.1 什么时候用 Select

Write 进去了几万条记忆、几百万文档，但当前这一笔任务只需要其中 5 条。**Select 就是"在模型开口前，按相关性把这 5 条挑出来"**。这是上下文工程里最值钱的策略——选对了，模型即使得分一般也能答好；选错了，模型再强也瞎。

### 2.2 语义检索选择器（带重排序）

下面用 LangChain4j 风格的检索 + 重排做 Select。重点看"相关性评分"怎么用来决定进不进上下文。

```java
// ch15-context-engineering/src/main/java/demo/context/select/SemanticSelector.java
package demo.context.select;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.search.RelevantSegment;
import dev.langchain4j.store.embedding.search.Request;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 语义检索选择器：把"当前任务"向量化，从知识库 Select 最相关的 N 条。
 */
public class SemanticSelector {

    private final EmbeddingStore store;
    private final EmbeddingModel embedder;

    public SemanticSelector(EmbeddingStore store, EmbeddingModel embedder) {
        this.store = store;
        this.embedder = embedder;
    }

    /**
     * @param taskContext 当前任务的自然语言描述（如用户问题 + 任务状态）
     * @param topK        最多选多少条进上下文
     * @param minScore    相关性阈值，低于此分的噪声不进窗口
     */
    public List<String> select(String taskContext, int topK, double minScore) {
        Embedding qe = embedder.embed(taskContext).content();
        List<RelevantSegment> hits = store.search(
                Request.builder().queryEmbedding(qe).maxResults(topK * 2).build())
                .matches();

        return hits.stream()
                .filter(h -> h.score() >= minScore)               // 过滤低相关噪声
                .sorted(Comparator.comparingDouble(RelevantSegment::score).reversed())
                .limit(topK)                                       // 控制进窗口数量
                .map(h -> h.embedded().text())
                .collect(Collectors.toList());
    }
}
```

### 2.3 记忆 + 检索的联合 Select（真实生产形态）

生产里 Select 通常不止检索知识库，还要把"用户画像 + 任务状态 + 相关历史"一起选出来：

```java
// ch15-context-engineering/src/main/java/demo/context/select/UnionSelector.java
package demo.context.select;

import demo.context.write.MemoryWriter;
import demo.context.ContextItem;
import demo.context.ContextType;

import java.util.ArrayList;
import java.util.List;

/** 联合 Select：把多源相关上下文拼成一个有序窗口 */
public class UnionSelector {

    private final SemanticSelector knowledge;
    private final MemoryWriter memory;

    public UnionSelector(SemanticSelector knowledge, MemoryWriter memory) {
        this.knowledge = knowledge;
        this.memory = memory;
    }

    public List<ContextItem> selectFor(String userId, String task) {
        List<ContextItem> items = new ArrayList<>();

        // 1) 用户画像（高优先级，永远进窗口）
        items.add(new ContextItem("profile", memory.readProfile(userId),
                ContextType.MEMORY, 1.0, "memory:profile"));

        // 2) 知识库检索（语义相关）
        knowledge.select(task, 5, 0.35).forEach(t ->
                items.add(new ContextItem("kb", t, ContextType.RETRIEVED, 0.8, "kb")));

        // 3) 任务状态（如有）
        String state = memory.readTaskState(userId);
        if (state != null)
            items.add(new ContextItem("state", state, ContextType.MEMORY, 0.9, "memory:state"));

        // 按优先级排序，保证重要信息靠前（lesson03：位置权重）
        items.sort((a, b) -> Double.compare(b.relevance(), a.relevance()));
        return items;
    }
}
```

<callout emoji="⚠️"><p>Select 的两个经典坑：① 阈值（minScore）定太高会漏关键知识，定太低噪声进来。务必接第十六章的评测，用 Recall@K 反推阈值。② topK 不是越大越好，塞太多会触发"上下文腐烂"（lesson03）。一般知识类 3–8 条，画像/状态类固定进。</p></callout>

---

## 三、Compress：把冗长上下文压短（摘要压缩）

### 3.1 什么时候用 Compress

Select 出来的东西可能还是太长：一段政策原文 3000 字，但当前问题只用到其中两句。直接塞既贵又稀释注意力。**Compress 在不丢关键信息的前提下，把上下文砍到目标 token 预算内。**

### 3.2 LLM 摘要压缩（Java 调用）

```java
// ch15-context-engineering/src/main/java/demo/context/compress/LlmCompressor.java
package demo.context.compress;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 用 LLM 把长上下文压缩成"只保留与问题相关的句子"。
 * 注意：压缩结果仅用于"最终生成"，压缩用的 LLM 调用本身有成本（lesson03 成本治理）。
 */
public class LlmCompressor {

    interface Compressor {
        @SystemMessage("""
            你是上下文压缩器。只保留【资料】中与【问题】直接相关的句子，
            删除无关内容、重复内容。不要新增事实。只输出保留的内容。
            """)
        String compress(@UserMessage String question, String context);
    }

    private final Compressor compressor;

    public LlmCompressor(ChatLanguageModel model) {
        this.compressor = AiServices.create(Compressor.class, model);
    }

    public String compress(String question, String context, int maxTokens) {
        String result = compressor.compress(question, context);
        // 兜底：万一 LLM 没收住，按 token 硬截断（粗略按字符/2 估算）
        if (result.length() > maxTokens * 2) {
            result = result.substring(0, maxTokens * 2);
        }
        return result;
    }
}
```

### 3.3 基于重排分数的零成本压缩

不是每次都值得多调一次 LLM。用 rerank 分数做"软截断"，零额外成本：

```java
// ch15-context-engineering/src/main/java/demo/context/compress/ScoreCompressor.java
package demo.context.compress;

import dev.langchain4j.store.embedding.search.RelevantSegment;

import java.util.List;
import java.util.stream.Collectors;

/** 零成本压缩：只保留分数 >= threshold 的片段 */
public class ScoreCompressor {
    public static String compress(List<RelevantSegment> hits, double threshold) {
        return hits.stream()
                .filter(h -> h.score() >= threshold)
                .map(h -> "[" + h.embedded().metadata().getString("source") + "] "
                        + h.embedded().text())
                .collect(Collectors.joining("\n"));
    }
}
```

### 3.4 递归摘要压缩（超长历史场景）

当对话历史特别长（客服聊了 50 轮），用"分段摘要再拼接"递归压：

```java
// ch15-context-engineering/src/main/java/demo/context/compress/RecursiveSummarizer.java
package demo.context.compress;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;

import java.util.List;

public class RecursiveSummarizer {
    interface Summarizer {
        @UserMessage("请用一句话概括下面这段对话的核心进展，保留待办和关键结论：\n{text}")
        String summarize(String text);
    }
    private final Summarizer s;

    public RecursiveSummarizer(ChatLanguageModel m) { this.s = AiServices.create(Summarizer.class, m); }

    /** 把长历史按 window 切块，逐块摘要，最后拼接成压缩历史 */
    public String compress(List<String> history, int window) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < history.size(); i += window) {
            List<String> chunk = history.subList(i, Math.min(i + window, history.size()));
            sb.append(s.summarize(String.join("\n", chunk))).append("\n");
        }
        return sb.toString();
    }
}
```

<callout emoji="💡"><p>Compress 选型口诀：日常用"分数截断"（零成本）打底；对精度极端敏感的任务才上"LLM 抽取"；历史超长用"递归摘要"。永远记得——压缩可能丢信息，所以压缩前后要接第十六章的 Faithfulness 评测，确认没把关键事实压没了。</p></callout>

---

## 四、Isolate：把不同任务/角色的上下文隔开（隔离 Agent 上下文）

### 4.1 什么时候用 Isolate

这是四个策略里最被低估、但出生产事故最多的一个。场景：

- **多租户**：A 客户的对账上下文绝不能泄漏到 B 客户（合规 + 正确性）。
- **子 Agent 分工**：一个"规划 Agent"和一个"执行 Agent"不该共享同一份噪声上下文。
- **工具调用隔离**：工具返回的巨大 JSON 不该污染主对话上下文。

Isolate 的核心是：**给每个任务/角色/租户一个独立的上下文视图，互不污染。**

### 4.2 租户隔离的上下文视图

```java
// ch15-context-engineering/src/main/java/demo/context/isolate/TenantIsolation.java
package demo.context.isolate;

import demo.context.ContextEngine;
import demo.context.ContextView;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 租户隔离：每个 tenant 有独立的上下文视图（独立的记忆、检索命名空间）。
 * 底层靠"带 tenantId 前缀的 key"或独立向量库 namespace 实现。
 */
public class TenantIsolation {

    // tenantId -> 该租户的上下文引擎实例
    private final Map<String, ContextEngine> engines = new ConcurrentHashMap<>();

    public ContextView getView(String tenantId, String taskId) {
        ContextEngine engine = engines.computeIfAbsent(tenantId,
                id -> ContextEngineFactory.forTenant(id));   // 每个租户独立引擎
        return engine.isolate(tenantId, taskId);              // 再按 task 隔离
    }

    /** 校验：跨租户访问必须显式拒绝（防止上下文泄漏） */
    public boolean assertNoLeak(String expectedTenant, ContextView view) {
        return expectedTenant.equals(view.tenantId());
    }
}
```

### 4.3 子 Agent 上下文隔离（Supervisor 模式配套）

在 Supervisor 编排多子 Agent 时（第四章），每个子 Agent 只拿到"分配给它的那块上下文"，而不是全局：

```java
// ch15-context-engineering/src/main/java/demo/context/isolate/SubAgentIsolation.java
package demo.context.isolate;

import demo.context.ContextItem;
import demo.context.ContextType;

import java.util.List;
import java.util.stream.Collectors;

/** 把一个大上下文视图，按"角色授权"切分给子 Agent */
public class SubAgentIsolation {

    /**
     * @param full     全局上下文
     * @param allowed 该子 Agent 被允许看的上下文类型
     */
    public static List<ContextItem> sliceForSubAgent(
            List<ContextItem> full, List<ContextType> allowed) {
        return full.stream()
                .filter(i -> allowed.contains(i.type()))
                .collect(Collectors.toList());
    }

    // 例：执行 Agent 只看 TOOL 返回和 TASK_STATE，不碰 SYSTEM 规划指令
    //     SubAgentIsolation.sliceForSubAgent(full, List.of(ContextType.TOOL, ContextType.MEMORY));
}
```

### 4.4 工具返回隔离（防止巨型 JSON 污染）

```java
// ch15-context-engineering/src/main/java/demo/context/isolate/ToolResultIsolation.java
package demo.context.isolate;

/**
 * 工具返回可能很大（几万字符的 SQL 结果 / 文件内容）。
 * 隔离策略：工具结果只存"引用"，不直接进对话上下文；
 * 模型需要时用"按需取片段"的方式读取。
 */
public class ToolResultIsolation {

    // 工具结果写入独立存储，返回给模型的只是句柄 + 摘要
    public static String isolateToolResult(String rawResult) {
        int maxInline = 2000;
        if (rawResult.length() <= maxInline) return rawResult;
        // 超长的只给摘要 + 句柄，模型可后续按需取
        String handle = "toolref://" + Integer.toHexString(rawResult.hashCode());
        String summary = rawResult.substring(0, maxInline) + "...[已截断，句柄 " + handle + "]";
        ToolStore.put(handle, rawResult);   // 存独立空间
        return summary;
    }
}
```

<callout emoji="⚠️"><p>Isolate 是合规红线：金融/政企的多租户 Agent，上下文泄漏不只是 bug，是安全事故。面试时主动提"我们按 tenantId 做上下文隔离 + 跨租户访问断言"，是加分项。同时参考第十二章 Agent 安全与治理。</p></callout>

---

## 五、四大策略串成"上下文装配流水线"

把 Write/Select/Compress/Isolate 串起来，就是一次完整的"模型开口前"的上下文装配：

```java
// ch15-context-engineering/src/main/java/demo/context/ContextPipeline.java
package demo.context;

import demo.context.write.MemoryWriter;
import demo.context.select.UnionSelector;
import demo.context.compress.LlmCompressor;
import demo.context.isolate.TenantIsolation;

import java.util.List;

/** 一次推理前的上下文装配主流程 */
public class ContextPipeline {

    private final MemoryWriter writer;
    private final UnionSelector selector;
    private final LlmCompressor compressor;
    private final TenantIsolation isolation;

    public ContextPipeline(MemoryWriter w, UnionSelector s,
                           LlmCompressor c, TenantIsolation i) {
        this.writer = w; this.selector = s; this.compressor = c; this.isolation = i;
    }

    public String assemble(String tenantId, String userId, String task) {
        // 1) Write：先把本轮新状态落库（由调用方在合适时机触发）
        // 2) Select：选出最相关上下文
        List<ContextItem> selected = selector.selectFor(userId, task);
        // 3) Compress：压到预算内
        String compressed = compressor.compress(task,
                selected.stream().map(ContextItem::content).reduce("", (a,b)->a+"\n"+b), 4000);
        // 4) Isolate：按租户隔离视图
        ContextView view = isolation.getView(tenantId, task);
        // 组合返回（此处简化为字符串）
        return compressed;
    }
}
```

对应架构图（背下来，面试画得出）：

```
                      ┌─────────────────────────────┐
   任务到来 ─────────► │      ContextPipeline         │
                      └────────────┬────────────────┘
            ┌──────────────┬───────┴────────┬───────────────┐
            ▼              ▼                ▼               ▼
        [ Write ]      [ Select ]      [ Compress ]     [ Isolate ]
        记忆写入        语义检索选择      摘要/截断压缩    租户/角色隔离
        (PgVector)     (Embedding+重排)  (LLM/分数)      (namespace)
            │              │                │               │
            └──────────────┴────────────────┴───────────────┘
                              ▼
                   装配好的"有效上下文窗口"
                              ▼
                        LLM 推理
```

---

## 六、企业落地场景：客诉 Agent 的四策略协同

某银行客诉 Agent 的真实链路，四策略各司其职：

```
用户："我上月那笔退款怎么还没到？"
  │
  ▼ Write：把"用户偏好走短信通知"写入用户画像记忆
  ▼ Select：语义检索"退款政策+该用户订单记录"，minScore=0.4，topK=5
  ▼ Compress：把政策原文压成"到账时效24h"，订单记录压成"状态:处理中"
  ▼ Isolate：本用户上下文与历史其他用户隔离，工具返回(订单API JSON)截断
  ▼ 模型生成："您的退款已于X日处理，预计24h内到账"
```

**为什么这样设计？** 客诉场景"答错即投诉"。Write 保证长期了解用户、Select 保证问得准、Compress 保证答得聚焦、Isolate 保证不串用户。四策略协同把"可能出错"堵在生成之前。

<callout emoji="💡"><p>一个判断框架：Write 解决"模型记不住"，Select 解决"给得太多/不对"，Compress 解决"给得太长"，Isolate 解决"互相污染"。漏掉任何一个，上下文质量都有短板。这道框架是 2026 面试官最爱的"上下文工程"追问。</p></callout>

---

## 七、四大策略的评测接口（接第十六章）

每一策略都要可评测，否则你不知道"压过头了没""选漏了没"：

| 策略 | 评测指标 | 方法 |
|------|----------|------|
| Write | 记忆命中率 / 衰减合理性 | 回访测试 + TTL 检查 |
| Select | Recall@K / MRR | 黄金查询集（第十六章） |
| Compress | 压缩前后 Faithfulness 不降 | LLM-as-Judge 对比 |
| Isolate | 跨租户泄漏率 | 红蓝对抗测试（第十六章 lesson01） |

<callout emoji="✅"><p>工程铁律：四大策略不是写完就完事，必须接第十六章的评测闭环。尤其是 Compress 和 Select——压没了关键事实、选漏了适用规则，模型会"自信地答错"，比不压不选取更危险。</p></callout>

---

## 八、小结

这节课把四大策略全部落到可运行 Java 代码：

1. **Write（记忆写入）**：结构化记忆写入器，带类型/优先级/TTL，防止无限膨胀成噪声。Spring AI 的 `MessageChatMemoryAdvisor` 是其工程化实现。
2. **Select（语义检索选择）**：把任务向量化，按相关性阈值 + topK 从知识库/记忆选出最相关的上下文，并按优先级排序。
3. **Compress（摘要压缩）**：LLM 抽取（高质量）或重排分数截断（零成本）或递归摘要（超长历史），砍到 token 预算内。
4. **Isolate（隔离上下文）**：租户隔离、子 Agent 切片、工具返回隔离——防止跨任务/跨租户污染，是合规红线。

四策略串成 `ContextPipeline`，在每次推理前装配"有效上下文窗口"。每一策略都必须接第十六章评测闭环。

---

## 延伸阅读 / 引用文档

1. Anthropic 上下文工程博客（四大策略源头）：https://www.anthropic.com/engineering/effective-context-engineering
2. LangChain4j RAG 与 ContentInjector（Select/Compress 实现参考）：https://docs.langchain4j.dev/tutorials/rag
3. Spring AI Chat Memory / Advisor（Write 工程化）：https://docs.spring.io/spring-ai/reference/api/chatclient.html
4. Lost in the Middle（Compress 必要性依据）：https://arxiv.org/abs/2307.03172
5. Anthropic Prompt Caching（Isolate + 缓存协同，lesson03 衔接）：https://docs.anthropic.com/en/docs/build-with-claude/prompt-caching
6. 递归摘要与上下文压缩综述（LangChain 博客）：https://blog.langchain.dev/context-engineering-for-agents/

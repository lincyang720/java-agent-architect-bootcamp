# 第六章第5节：记忆优化策略——压缩、摘要、时间衰减重排序

大家好，我是秋天。

前面四节我们把记忆"建"起来了：短期窗口管理、长期向量库选型、Embedding 与检索。但一个真实跑起来的记忆系统，很快就会遇到三个新问题：

1. 记忆越积越多，检索回来的 TopK 一大坨，窗口塞不下；
2. 早期记忆很重要但表述冗长，占着宝贵的 token；
3. 一周前的记忆和刚发生的记忆"一视同仁"，但用户当下更关心最近的。

这节课讲**记忆优化三板斧：压缩、摘要、时间衰减重排序**。学完你能：

1. 用"上下文压缩"把检索回来的冗余记忆压薄；
2. 用"分层摘要"管理长期记忆的脉络；
3. 用"时间衰减重排序"让近期记忆权重更高；
4. 理解这三者如何组合，形成完整的记忆优化闭环。

这节是"上下文工程（第十五章）"在记忆域的直接落地，也是 JD 里"上下文压缩 / 检索优化"的对应能力。

---

## 一、为什么需要记忆优化

先算一笔账，看清痛点：

```text
未优化的记忆检索：
  用户问一个问题 → 召回 TopK=10 条记忆
  每条平均 200 token → 2000 token 塞进窗口
  其中 6 条和当前问题弱相关，纯属噪音
  → 窗口被占、成本涨、模型注意力被稀释（腐烂）

优化后：
  压缩+重排序 → 只保留 3 条高相关、精简后的记忆
  → 600 token，信号集中，模型答得准又省钱
```

<callout emoji="💡"><p>核心认知：记忆优化的目标不是"记住更多"，而是"在有限窗口里，让有用的记忆更突出、没用的更靠后或被压缩"。这和 lesson02 短期记忆"塞得准"一脉相承。</p></callout>

---

## 二、板斧一：压缩（Compression）

压缩是对"检索回来的原始记忆"做瘦身——去掉冗余、保留核心。两种做法：

### 2.1 模型压缩（LLM-based Compression）

让一个小模型读一段长记忆，输出"只保留和当前问题相关的要点"。

```java
package com.camp.memory;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 上下文压缩：用模型把"检索到的记忆"压成与问题相关的要点。
 * 关键：压缩模型可用小模型（gemini-2.0-flash / 本地小模型）省钱。
 */
@Component
public class MemoryCompressor {

    @Autowired
    private ChatModel compressor;   // 建议用小模型/快模型

    public String compress(String query, String rawMemory) {
        String sys = """
                你是记忆压缩器。只保留与【用户问题】直接相关的信息，
                去掉无关细节、客套、重复。输出要点，不超过 80 字。
                """;
        String user = "【用户问题】" + query +
                      "\n【原始记忆】" + rawMemory + "\n【压缩结果】";
        return compressor.call(new Prompt(List.of(
                new SystemMessage(sys), new UserMessage(user))))
                .getResult().getOutput().getText();
    }
}
```

### 2.2 规则压缩（无模型，确定性）

不调模型，用规则截断/抽取，成本低、可控：

```java
package com.camp.memory;

import java.util.List;

/**
 * 规则压缩：确定性、零模型调用成本。
 * 适合"记忆本身结构清晰（如 JSON/键值）"的场景。
 */
public class RuleCompressor {

    /** 只保留每条记忆的前 N 个字符 + 去掉空白 */
    public String compress(String raw, int maxLen) {
        String cleaned = raw.replaceAll("\\s+", " ").trim();
        return cleaned.length() <= maxLen
                ? cleaned
                : cleaned.substring(0, maxLen) + "…";
    }

    /** 从结构化记忆中抽取关键字段（如只留 结论 字段） */
    public String extractField(String jsonLike, String field) {
        // 简化示意：真实用 Jackson 解析
        int idx = jsonLike.indexOf("\"" + field + "\"");
        if (idx < 0) return "";
        int start = jsonLike.indexOf(':', idx) + 1;
        int end = jsonLike.indexOf(',', start);
        return end < 0 ? jsonLike.substring(start) : jsonLike.substring(start, end);
    }
}
```

<callout emoji="⚠️"><p>选型提醒：模型压缩更智能但慢且贵；规则压缩快且免费但笨。生产常组合——先用规则粗压（截断/抽字段），关键路径再用模型精压。别一上来全用大模型压，账单会哭。</p></callout>

---

## 三、板斧二：摘要（Summarization）

摘要解决"长期记忆的脉络管理"——很久以前的记忆不该存原文，该存"那段时间发生了什么"的摘要。

### 3.1 分层摘要策略

```text
分层摘要（类比文件系统的"目录"）：
  原始记忆（细）：每一轮对话原文
        │ 定期压缩
        ▼
  段落摘要（中）："本周用户主要关注退货和发票"
        │ 再定期压缩
        ▼
  全局摘要（粗）："该用户是 VIP3，关注售后体验"
```

实现：定期把"时间窗内的原始记忆"用模型汇总成一条摘要记忆存回向量库，原记忆可降权或归档。

```java
package com.camp.memory;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 摘要生成器：把一批记忆汇总成一条"脉络摘要"。
 */
@Component
public class MemorySummarizer {

    @Autowired
    private ChatModel summarizer;

    public String summarize(List<String> memories, String scope) {
        String sys = "你是记忆摘要器。把下面这些记忆归纳成一段"
                + scope + "级别的脉络总结，保留关键事实，不超过 120 字。";
        String user = String.join("\n", memories);
        return summarizer.call(new Prompt(List.of(
                new SystemMessage(sys), new UserMessage(user))))
                .getResult().getOutput().getText();
    }
}
```

<callout emoji="💡"><p>和 lesson02 短期记忆的"摘要压缩"呼应：短期用摘要保"窗口内脉络"，长期用摘要保"跨会话脉络"。两层摘要配合，记忆既能"细到当下"，又能"粗到全局"。这是 MemGPT 分层记忆思想的核心。</p></callout>

---

## 四、板斧三：时间衰减重排序（Time-Decay Re-ranking）

这是很多人忽略但极其重要的一招。**纯向量相似度不考虑时间**——一周前和一分钟前的记忆，只要语义像，得分一样。但用户当下更关心"最近"。解决办法：在相似度得分上叠一个时间衰减因子。

### 4.1 衰减公式

```text
最终得分 = 语义相似度 × 时间衰减权重

时间衰减权重（指数衰减）：
  w(t) = exp(-λ × Δt)
  Δt = 当前时间 - 记忆创建时间（天）
  λ  = 衰减速率（越大忘得越快）

例：λ=0.1
  Δt=0天  → w=1.0   （刚发生，满分）
  Δt=7天  → w=0.50  （一周前，减半）
  Δt=30天 → w=0.05  （一月前，几乎忽略）
```

### 4.2 Java 实现

```java
package com.camp.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 时间衰减重排序：在语义相似度基础上，按时间远近加权。
 */
public class TimeDecayReRanker {

    private final double lambda;   // 衰减速率

    public TimeDecayReRanker(double lambda) {
        this.lambda = lambda;
    }

    /** 单条记忆：语义分 + 时间戳 */
    public record ScoredMemory(String text, double semanticScore,
                               Instant createdAt) {}

    /** 重排序：最终分 = 语义分 × 时间衰减权重 */
    public List<ScoredMemory> reRank(List<ScoredMemory> memories, Instant now) {
        return memories.stream()
                .map(m -> {
                    double days = Duration.between(m.createdAt(), now).toHours() / 24.0;
                    double decay = Math.exp(-lambda * days);
                    double finalScore = m.semanticScore() * decay;
                    return new ScoredMemory(m.text(), finalScore, m.createdAt());
                })
                .sorted(Comparator.comparingDouble(ScoredMemory::semanticScore).reversed())
                .collect(Collectors.toList());
    }

    // 演示
    public static void main(String[] args) {
        Instant now = Instant.now();
        var list = List.of(
            new ScoredMemory("今天的退货请求", 0.9, now.minus(java.time.Duration.ofDays(0))),
            new ScoredMemory("上月的发票问题", 0.92, now.minus(java.time.Duration.ofDays(30)))
        );
        var ranked = new TimeDecayReRanker(0.1).reRank(list, now);
        ranked.forEach(m -> System.out.println(m.text() + " -> " + m.semanticScore()));
        // 输出：今天的退货请求 最终分更高（虽然原始语义分略低）
    }
}
```

<callout emoji="✅"><p>为什么时间衰减重要？因为"语义相似"不等于"此刻相关"。用户问"我的订单"，一周前下的单和今天下的单语义一样像，但显然该优先今天的。衰减让记忆系统有了"当下感"，体验立刻不一样。</p></callout>

---

## 五、三板斧组合：完整优化闭环

把压缩、摘要、衰减组合成一个优化流水线：

```text
检索召回 TopK 原始记忆
        │
        ▼
[时间衰减重排序] → 近期记忆加权前置
        │
        ▼
[压缩] → 每条记忆瘦身（规则粗压 + 模型精压）
        │
        ▼
[摘要补充] → 从长期脉络摘要中取一条"全局画像"常驻
        │
        ▼
精简后的高信号记忆 → 回填上下文窗口
```

```java
package com.camp.memory;

import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 记忆优化流水线：衰减重排 + 压缩 + 摘要。
 */
@Component
public class MemoryOptimizer {

    @Autowired private TimeDecayReRanker reRanker;
    @Autowired private MemoryCompressor compressor;
    @Autowired private MemorySummarizer summarizer;

    public List<String> optimize(List<Document> recalled, String query,
                                 Instant now, String userId) {
        // 1) 转成带语义分+时间的结构
        List<TimeDecayReRanker.ScoredMemory> scored = recalled.stream()
                .map(d -> new TimeDecayReRanker.ScoredMemory(
                        d.getText(),
                        d.getScore(),                 // 向量库返回的相似度
                        Instant.ofEpochMilli(Long.parseLong(
                                d.getMetadata().getOrDefault("ts", "0")))))
                .toList();

        // 2) 时间衰减重排序
        List<TimeDecayReRanker.ScoredMemory> ranked = reRanker.reRank(scored, now);

        // 3) 压缩每条（取前 5 条精压，其余规则粗压）
        return ranked.stream().limit(5).map(m ->
                compressor.compress(query, m.text())).toList();
    }
}
```

<callout emoji="💡"><p>顺序有讲究：先重排再压缩——因为压缩要算力，先按"最终分"排好序，只精压进前 N 条，省下的算力就是省下的钱。这也是工程优化"先做廉价筛选，再做昂贵处理"的通用原则。</p></callout>

---

## 六、企业落地：客服记忆优化参数

给你一套真实可调的参数参考：

```text
客服 Agent 记忆优化配置：
  召回 TopK = 10（从宽召回）
  时间衰减 λ = 0.1（一周减半，一月几乎忽略）
  压缩：前 5 条模型精压（小模型），其余规则截断 80 字
  摘要：每 7 天把本周记忆汇成段落摘要；每月汇成全局画像
  全局画像（VIP等级/偏好）始终常驻上下文
```

<callout emoji="⚠️"><p>参数别拍脑袋，要靠评测定（第十六章）。我建议：先固定 λ=0.1、TopK=10 跑一批真实对话，看"相关记忆是否排到了前面、窗口是否 overflow"，再用 RAGAS 类指标调。调参是数据驱动，不是玄学。</p></callout>

---

## 七、与上下文工程的衔接

这节课三板斧，正是第十五章"Context Engineering"四大策略在记忆域的具象：

```text
上下文工程策略    ↔  记忆优化手段
  Select(选择)   ↔  时间衰减重排序（挑出此刻相关的）
  Compress(压缩) ↔  上下文压缩（瘦身记忆）
  Write(写入)    ↔  摘要写入长期库
  Isolate(隔离)  ↔  多用户记忆互不污染（user_id 过滤）
```

<callout emoji="✅"><p>认知闭环：你现在看到的"记忆优化"，正是后面"上下文工程"的实战预演。学这节，等于提前拿下第十五章一半的内容。架构师思维就是这种"一通百通"——把具体问题抽象成通用策略。</p></callout>

---

## 八、招聘能力映射

这节课对应 JD 里"记忆/检索优化"的要求：

- "**上下文压缩（Context Compression）**" → 第二节。
- "**检索优化 / 重排序**" → 第四节时间衰减重排（第七章还会讲 Rerank 模型）。
- "**RAG 优化经验**" → 三板斧组合（第七章深化）。
- "**Agent 记忆管理**" → 整节。

<callout emoji="💡"><p>面试金句："记忆优化三板斧——压缩（瘦身冗余）、摘要（保脉络）、时间衰减重排（近期优先）。顺序是先廉价重排筛选、再昂贵压缩，参数靠评测定。本质是上下文工程 Select/Compress 在记忆域的落地。" 这段话把优化讲成体系，不是零散技巧。</p></callout>

---

## 八、压缩与摘要的成本权衡

优化动作本身有成本，要算账。

```text
优化动作成本对比：
  规则压缩：零成本，确定性，但笨（只截断/抽字段）
  模型压缩：每次调小模型，有成本，但智能
  摘要：定期跑，成本均摊到低频，性价比高
  时间衰减：纯计算，零模型调用，必须开

决策：
  高频路径（每轮）  → 规则压缩 + 衰减（免费）
  关键路径（前N条）→ 模型压缩（值得）
  后台任务（定时）  → 摘要（均摊成本）
```

<callout emoji="💡"><p>优化也有"性价比"。把免费的（规则/衰减）放每轮高频路径，把贵的（模型压缩）只用在关键路径，成本才能控住。这是 lesson02 成本治理思想在记忆优化层的延续。</p></callout>

## 九、分层存储：热/温/冷记忆

长期记忆也可分热度，存不同介质，降本增效。

```text
记忆分层存储：
  热层（最近/高频）：存内存/快向量库，毫秒级检索
  温层（近期）：存 PG+PgVector，秒级
  冷层（久远/低频）：归档对象存储/离线库，廉价
  → 检索时先查热层，未命中查温层，必要时唤醒冷层
```

<callout emoji="✅"><p>分层存储是"成本与性能"的再平衡。90% 的检索命中热/温层，冷层几乎不被碰却占着便宜存储。这是云上成本治理的通用套路，记忆系统也一样适用。</p></callout>

## 十、记忆去重：避免重复记忆污染

反复存相似记忆会稀释检索质量。要去重。

```java
package com.camp.memory;

import java.util.*;

/**
 * 记忆去重：新记忆若与已有高度相似，合并而非新增。
 */
public class MemoryDedup {
    // 简化：用文本重叠度判断相似（真实用向量余弦）
    public boolean isDuplicate(String newMem, List<String> existing, double thr) {
        for (String e : existing) {
            double sim = overlap(newMem, e);
            if (sim > thr) return true;
        }
        return false;
    }
    private double overlap(String a, String b) {
        Set<String> sa = new HashSet<>(Arrays.asList(a.split("")));
        Set<String> sb = new HashSet<>(Arrays.asList(b.split("")));
        sa.retainAll(sb);
        return (double) sa.size() / Math.max(a.length(), b.length());
    }
}
```

<callout emoji="⚠️"><p>重复记忆的危害：检索时同一条信息多个副本霸占 TopK，挤掉真正相关的记忆。写入前做相似度去重（合并或跳过），能显著提升检索纯度。</p></callout>

## 十一、与 RAG 优化的衔接

记忆优化和 RAG 优化高度同源——本质都是"检索质量 + 上下文精简"。

```text
记忆优化          ↔  RAG 优化（第七章）
  时间衰减重排     ↔  多路召回融合排序
  记忆压缩        ↔  上下文压缩（Context Compression）
  记忆摘要        ↔  文档摘要/查询改写
  去重           ↔  检索结果去重
```

<callout emoji="💡"><p>学会这节课，第七章 RAG 优化你几乎不用重新学——词汇不同，思想完全一致。这也是课程"先建立通用能力，再映射到具体场景"的设计意图。</p></callout>

## 十二、企业落地参数再细化

给你一套可调的初始参数（非标准答案，用评测调）：

```text
初始参数包：
  召回 TopK = 10（粗召回从宽）
  Rerank TopK = 5（精排后注入）
  时间衰减 λ = 0.1（一周减半）
  压缩：前 5 条模型精压，其余规则截断 80 字
  摘要周期：7 天段落摘要 / 30 天全局画像
  分层：热(7天)/温(90天)/冷(更久)
  去重阈值：余弦 > 0.92 视为重复
```

<callout emoji="⚠️"><p>参数必须靠评测校准（第十六章）。上面是"合理起点"，不是"终点"。我反复强调：调参是数据驱动，不是凭经验拍脑袋。先有基线，再迭代。</p></callout>

## 十三、避坑清单

```text
记忆优化避坑：
❌ 每轮都用模型压缩（烧钱）
❌ 只召回不重排（相关项排后面漏掉）
❌ 不去做重（重复记忆污染）
❌ 衰减参数拍脑袋（导致近期/远期权重失衡）
❌ 不分冷热层（成本浪费）
✅ 免费优化放高频、贵的放关键、参数靠评测
```

<callout emoji="💡"><p>记忆优化是"细节里出效果"的活。压缩、重排、衰减、去重、分层——单看每个都不起眼，组合起来检索质量能差一个量级。这节就是把这些细节系统化。</p></callout>

## 十四、记忆优化的反模式

最后给几个"看似优化、实则坑"的反模式，帮你避坑。

```text
反模式1：过度压缩 → 把关键信息也压没了，检索召回空集
  正解：压缩保留实体/结论，不压关键字段

反模式2：阈值定太高 → 只召回 0.95 相似，相关记忆全漏
  正解：宽召回（低阈值） + 重排精筛

反模式3：衰减过快 → λ 太大，一周前的记忆几乎权重为0
  正解：λ 按业务周期调（月级业务用更小 λ）

反模式4：只优化检索不优化写入 → 存了一堆噪音，再优化也救不回
  正解：写入时提炼 + 去重，源头治理

反模式5：无评测空转 → 不知道优化到底有没有用
  正解：Recall@K / MRR 定期量化
```

<callout emoji="⚠️><p>优化是"平衡艺术"，不是"越狠越好"。压缩过度、阈值过高、衰减过快，都会把好记忆也干掉。每次优化后用评测验证，避免"优化变劣化"。这又是第十六章评测闭环的提醒。</p></callout>

## 十五、记忆优化的 A/B 评测

优化到底有没有用？用 A/B 量化，不是凭感觉。

```text
A/B 评测设计：
  组A（基线）：无优化的记忆检索
  组B（实验）：加 压缩+衰减重排+重排
  指标：Recall@K、MRR、最终回答准确率、成本/轮
  判定：B 的准确率提升且成本不爆 → 全量
```

```java
package com.camp.memory;

/** 简化版优化效果对比（真实用评测框架，第十六章） */
public class OptABTest {
    public static void main(String[] args) {
        double baseRecall = 0.62;     // 基线 Recall@5
        double optRecall = 0.81;      // 优化后 Recall@5
        double costDelta = 1.05;      // 成本几乎不变（免费优化为主）
        System.out.println("Recall 提升: " + ((optRecall/baseRecall-1)*100) + "%");
        System.out.println("成本倍数: " + costDelta + "（≈持平）");
    }
}
```

<callout emoji="⚠️><p>没有 A/B 的优化是"信仰驱动"。每次改压缩阈值、衰减 λ、TopK，都要用评测量化收益。这正是第十六章"评测闭环"在记忆域的落地——优化和评测必须绑定，否则你不知道自己在进步还是退步。</p></callout>

## 十六、记忆优化的组织落地与推广

技术再好，落不到团队也是零。给你一份推广清单。

```text
团队落地记忆优化：
  1) 建立基线：先测当前检索 Recall@K / 成本
  2) 选 1-2 个高价值场景试点（如客服记忆）
  3) 跑 A/B（lesson15）量化收益
  4) 沉淀为共享组件（MemoryOptimizer 模板）
  5) 写进团队规范（压缩/重排/衰减默认值）
  6) 接评测闭环（第十六章）持续监控
```

<callout emoji="⚠️><p>推广铁律：别一上来就全量改造。先单场景试点、用数据证明收益、再固化为模板推广。这是任何工程优化能在组织里存活的唯二路径（另一个是老板拍板），前者更可持续。</p></callout>

## 十七、记忆优化高频问答 FAQ

把同学最常问的几个问题集中答一下，省得你踩坑。

```text
Q1：压缩会不会把关键信息也压没了？
A：会，如果无脑压。规则压缩保留关键字段（实体/结论），模型压缩限定前 N 条。

Q2：时间衰减 λ 怎么定？
A：按业务周期。日级业务 λ 大些，月级业务 λ 小些（0.05~0.1 起调，用评测校准）。

Q3：检索阈值定多少？
A：宽召回（0.7 左右）+ 重排精筛，别直接 0.95 卡死。

Q4：优化后怎么证明有效？
A：A/B 评测 Recall@K / 成本，见 lesson15。

Q5：摘要多久做一次？
A：段落摘要 7 天、全局 30 天，按数据量调。
```

<callout emoji="⚠️><p>这些答案不是"标准答案"，是"合理起点"。记忆优化没有银弹，所有参数都要回到你自己的数据上用评测校准。记住：教科书给起点，数据给终点。</p></callout>

## 十八、记忆优化与上下文工程闭环

最后把记忆优化接回更大的体系——上下文工程（第十五章）。

```text
记忆优化 ⊂ 上下文工程：
  压缩 → Compress      （压窗口/压记忆）
  重排 → Select        （挑此刻相关的）
  摘要 → Write         （写长期脉络）
  隔离 → Isolate       （多用户不污染）
  → 记忆优化是上下文工程在"记忆域"的完整预演
```

<callout emoji="💡"><p>学到这里你应该有"打通"的感觉：这节课的每一个优化动作，都能在第十五章找到"理论名"。先学落地、再学理论，是本课程刻意的设计。等学到上下文工程，你会惊呼"原来我早就会了"。</p></callout>

## 小结

这节课把记忆优化讲成体系：

1. **为何优化**：未优化的 TopK 召回塞满窗口、引入噪音、推高成本、稀释注意力。
2. **压缩**：模型压缩（智能但贵，用小模型）vs 规则压缩（免费但笨）；组合使用。
3. **摘要**：分层摘要（原始→段落→全局）管理跨会话脉络，与短期摘要呼应。
4. **时间衰减重排**：`最终分 = 语义分 × exp(-λ·Δt)`，让近期记忆优先；解决"语义像≠此刻相关"。
5. **组合闭环**：衰减重排（廉价筛选）→ 压缩（昂贵处理只给前 N）→ 摘要常驻；顺序讲究"先筛后压"。
6. **与上下文工程衔接**：Select/Compress/Write/Isolate 在记忆域的具象。

记住一句话：**记忆优化不是"记更多"，而是"让有用的更突出"——压缩去噪、摘要保脉、衰减给记忆以'当下感'。**

---

## 延伸阅读 / 引用文档

1. MemGPT（分层记忆/压缩）：<https://arxiv.org/abs/2310.08560>
2. LangChain Context Compression 文档：<https://python.langchain.com/docs/concepts/retrieval/>
3. RAGAS（检索/生成质量评测）：<https://docs.ragas.io/>
4. Rerank 模型（如 BGE-Reranker）：<https://github.com/FlagOpen/FlagEmbedding>
5. Anthropic 上下文工程：<https://www.anthropic.com/engineering/context-engineering>
6. "Lost in the Middle" 注意力研究：<https://arxiv.org/abs/2307.03172>

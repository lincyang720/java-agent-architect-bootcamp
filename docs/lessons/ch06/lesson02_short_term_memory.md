# 第六章第2节：短期记忆实现——上下文窗口管理、缓存策略（LRU、TTL）

大家好，我是秋天。

上节课我们建立了"记忆双层模型"，把短期记忆定义为"当前上下文窗口里的即时信息"。这节课落地短期记忆——**怎么在有限的窗口里，既装得下必要信息，又不撑爆、不腐烂、不浪费钱**。

学完这节课你能：

1. 掌握上下文窗口管理的三种基本策略（裁剪、摘要、滑动）；
2. 用 Java 实现一个 LRU 缓存来管理短期记忆片段；
3. 理解 TTL（生存时间）在记忆过期清理中的作用；
4. 看清"提示词缓存（Prompt Caching）"为什么是短期记忆的成本大杀器。

短期记忆管得好不好，直接决定 Agent 是"聪明且省钱"还是"又傻又烧钱"。

---

## 一、上下文窗口管理：三种基本策略

短期记忆的载体就是上下文窗口。窗口有限，所以必须"精选进窗口的内容"。三种基础策略：

```text
策略1：滑动窗口（Sliding Window）
  只保留最近 N 轮对话，老的直接丢弃。
  ┌─── 保留 ───┐
  [..][t-2][t-1][t]   ← t-3 及之前被丢掉
  优点：简单、恒定成本  缺点：可能丢重要早期信息

策略2：摘要压缩（Summarization）
  把早期对话压缩成一段摘要，和近期原文一起进窗口。
  [摘要: 用户是VIP, 关注退货] [t-2][t-1][t]
  优点：保留长期脉络  缺点：摘要可能丢细节

策略3：重要性裁剪（Importance-based）
  给每条消息打分，保留高分，丢弃低分。
  [重要:用户身份][t][低分:闲聊→丢]
  优点：精准  缺点：需要打分模型/规则
```

<callout emoji="💡"><p>工程实践：三种策略常组合用——滑动窗口保近期、摘要保脉络、重要性裁剪保关键。别只挑一种。比如"最近 5 轮原文 + 更早的摘要 + 始终保留的用户身份卡"。</p></callout>

### 1.1 滑动窗口的 Java 实现

```java
package com.camp.memory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 滑动窗口短期记忆：只保留最近 N 条消息。
 * 这是最朴素但最常用的短期记忆管理。
 */
public class SlidingWindowMemory {

    private final int maxTurns;                       // 最大保留轮数
    private final Deque<String> window = new ArrayDeque<>();

    public SlidingWindowMemory(int maxTurns) {
        this.maxTurns = maxTurns;
    }

    public void add(String message) {
        window.addLast(message);
        // 超出则丢弃最老的
        while (window.size() > maxTurns) {
            window.pollFirst();
        }
    }

    /** 当前窗口内的全部消息，用于拼装 prompt */
    public List<String> snapshot() {
        return List.copyOf(window);
    }

    public int size() { return window.size(); }
}
```

<callout emoji="⚠️"><p>坑点：纯滑动窗口会"丢身份"。如果用户第一轮说"我是 VIP"，第 11 轮被滑出去了，Agent 就不记得 VIP 身份。所以**身份/关键事实必须额外常驻**（单独存一张"用户卡"始终拼进 prompt），不能依赖滑动窗口。</p></callout>

---

## 二、LRU 缓存：短期记忆片段的"最近最少使用"淘汰

Agent 推理时经常需要"临时查一点东西放窗口里"——比如检索到的知识片段、工具结果。这些东西不能无限堆，要用 LRU（Least Recently Used）淘汰最久没用的。

### 2.1 用 LinkedHashMap 实现 LRU

Java 标准库 `LinkedHashMap` 一行就能开 LRU 模式：

```java
package com.camp.memory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LRU 短期记忆缓存：容量满时淘汰最久未访问的条目。
 * 用于缓存"工具结果 / 检索片段"等临时记忆。
 */
public class LruMemoryCache<K, V> extends LinkedHashMap<K, V> {

    private final int maxSize;

    public LruMemoryCache(int maxSize) {
        // accessOrder=true → 按访问顺序排序，便于淘汰最久未用
        super(16, 0.75f, true);
        this.maxSize = maxSize;
    }

    /**
     * 当 size > maxSize 时，移除最久未访问的条目（队首）。
     * 这是 LinkedHashMap 的淘汰钩子，返回 true 即删除 eldest。
     */
    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > maxSize;
    }

    // 演示用法
    public static void main(String[] args) {
        LruMemoryCache<String, String> cache = new LruMemoryCache<>(3);
        cache.put("tool:weather:BJ", "晴 22度");
        cache.put("rag:退款政策", "7天无理由");
        cache.put("tool:order:U123", "已发货");
        cache.get("tool:weather:BJ");      // 访问一下，刷新热度
        cache.put("rag:发票", "电子发票"); // 超出容量，最久未用的被淘汰
        System.out.println(cache);          // 可见被淘汰的是未被访问的那条
    }
}
```

### 2.2 为什么短期记忆需要 LRU

```text
没有 LRU 的临时记忆：
  每轮检索/工具结果都堆进窗口 → 窗口爆 → 截断 → 答非所问

有 LRU 的临时记忆：
  只保留"最近用过的"若干条 → 窗口稳定 → 既快又省
  典型场景：多轮对话里反复查的"用户订单"，命中缓存不重复检索
```

<callout emoji="✅"><p>记忆点：LRU 解决的是"临时记忆的边际成本"。最近用过的优先留，很久没碰的先扔。这和人短期记忆的"近期效应"一致。在 Agent 里，它直接降低重复检索/重复调工具的 token 消耗。</p></callout>

---

## 三、TTL：给记忆一个"保质期"

有些短期记忆是有时效的——比如"用户刚说要验证码""临时锁定的订单"。过了时间就该失效，否则会误导推理。TTL（Time To Live）就是给每条记忆设过期时间。

### 3.1 带 TTL 的短期记忆条目

```java
package com.camp.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 带 TTL 的短期记忆存储：过期自动失效。
 * 适合"临时状态/验证码/会话级锁"等有时效的信息。
 */
public class TtlMemoryStore {

    private record Entry(String value, Instant expireAt) {}

    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    private final Duration defaultTtl;

    public TtlMemoryStore(Duration defaultTtl) {
        this.defaultTtl = defaultTtl;
    }

    public void put(String key, String value) {
        put(key, value, defaultTtl);
    }

    public void put(String key, String value, Duration ttl) {
        store.put(key, new Entry(value, Instant.now().plus(ttl)));
    }

    /** 读取时检查是否过期，过期则视为不存在并清理 */
    public String get(String key) {
        Entry e = store.get(key);
        if (e == null) return null;
        if (e.expireAt().isBefore(Instant.now())) {
            store.remove(key);   // 惰性过期
            return null;
        }
        return e.value();
    }

    /** 后台定期清理（生产用定时任务/ScheduledExecutor） */
    public void sweep() {
        Instant now = Instant.now();
        Iterator<Map.Entry<String, Entry>> it = store.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expireAt().isBefore(now)) it.remove();
        }
    }
}
```

### 3.2 TTL 的典型场景

```text
TTL 短期记忆场景：
  ├─ 验证码/一次性 token      TTL=5min
  ├─ 会话级操作锁（防并发）    TTL=30s
  ├─ 临时草稿/中间计算结果      TTL=10min
  └─ 用户"当前正在办的流程"    TTL=会话时长
```

<callout emoji="💡"><p>为什么不用"永久记住"？因为过期信息会毒害推理。比如用户 5 分钟前说"我要退款"，现在改主意了，但 Agent 还记着旧意图——这就是"记忆陈旧导致答错"。TTL 是防止短期记忆过期的第一道防线。</p></callout>

---

## 四、组合拳：滑动窗口 + LRU + TTL + 常驻卡

真实短期记忆管理是组合策略。我给你一个生产可用的"短期记忆管理器"骨架：

```java
package com.camp.memory;

import java.time.Duration;
import java.util.List;

/**
 * 生产级短期记忆管理器：组合四种机制。
 *  - 常驻卡（alwaysInContext）：身份/关键事实，永远进窗口
 *  - 滑动窗口（recent）：最近 N 轮原文
 *  - LRU 缓存（tempCache）：工具/检索结果，最近最少淘汰
 *  - TTL（临时态）：有时效的临时信息
 */
public class ShortTermMemoryManager {

    private final String userCard;                       // 常驻：用户身份卡
    private final SlidingWindowMemory recent;             // 滑动窗口
    private final LruMemoryCache<String, String> tempCache; // LRU
    private final TtlMemoryStore ttlStore;                // TTL

    public ShortTermMemoryManager(String userCard, int windowSize,
                                 int cacheSize, Duration ttl) {
        this.userCard = userCard;
        this.recent = new SlidingWindowMemory(windowSize);
        this.tempCache = new LruMemoryCache<>(cacheSize);
        this.ttlStore = new TtlMemoryStore(ttl);
    }

    /** 组装本轮进入模型的上下文内容（顺序有讲究） */
    public List<String> buildContext(String currentUserMsg) {
        // 1) 常驻卡永远在最前（防 Lost in the Middle，关键放头尾）
        // 2) 近期窗口原文
        // 3) 当前用户消息
        // （tempCache / ttl 的内容按需在被引用时插入，不主动全塞）
        return List.of(
                "[用户卡] " + userCard,
                "[近期对话] " + recent.snapshot(),
                "[当前] " + currentUserMsg
        );
    }

    public void remember(String msg) { recent.add(msg); }
    public void cacheToolResult(String key, String val) { tempCache.put(key, val); }
    public void setTemp(String k, String v, Duration ttl) { ttlStore.put(k, v, ttl); }
}
```

<callout emoji="⚠️"><p>顺序讲究：关键常驻信息（用户身份、当前任务）放窗口**头部和尾部**，因为模型对开头结尾注意力最强（Lost in the Middle）。中间放次要的历史。这是上下文工程的实战技巧，第十五章会展开。</p></callout>

---

## 五、提示词缓存：短期记忆的成本大杀器

前面都在讲"怎么省窗口空间"，但还有更狠的——**提示词缓存（Prompt Caching）**：把"反复出现的上下文前缀"（系统指令、常驻知识、用户卡）缓存下来，模型只对"变化的部分"计费。

```text
没有缓存：每轮都重发完整上下文（系统指令 + 历史 + 当前）
  成本 = Σ(每轮全量 token)  → 随轮数线性增长 💸

有缓存：系统指令/常驻前缀命中缓存，只对新部分计费
  成本 = 固定前缀(缓存价低) + Σ(每轮变化部分)  → 大幅降低 💰
```

Gemini / Claude 都支持缓存。ADK 里通过配置开启（概念示意）：

```yaml
# application.yml 片段：开启提示词缓存
google:
  adk:
    model: gemini-2.0-flash
    caching:
      enabled: true
      # 缓存系统指令 + 常驻用户卡这一段前缀
      cache-prefix: "system_instruction + user_card"
      ttl-minutes: 10
```

<callout emoji="✅"><p>成本治理考点：提示词缓存是"短期记忆经济学"的核心。凡是"每轮重复发送的大段前缀"（指令、知识、用户卡），都应该缓存。一个客服 Agent 每天百万次对话，缓存能砍掉 30%~60% 的输入 token 成本。对应 JD 里"Token 成本治理"要求。</p></callout>

---

## 六、短期记忆与上下文腐烂的对抗

上节课提过"上下文腐烂"——信息塞太多，模型反而抓不住重点。短期记忆管理的目标之一就是对抗腐烂：

```text
对抗腐烂的三招：
  1) 精简：LRU/TTL 丢掉无用/过期信息，减少噪音
  2) 结构化：用标签（[用户卡][近期][当前]）让模型分清主次
  3) 常驻关键：身份/任务永远在头尾，不被中间噪音淹没
```

<callout emoji="💡"><p>认知升级：短期记忆管理不是"塞更多"，而是"塞更准"。很多时候"记得少而精"比"记得多而杂"效果好。这也是为什么纯"全量历史"方案不可用——它把噪音和信号一视同仁。</p></callout>

---

## 七、企业落地：客服 Agent 的短期记忆配置

给你一个真实客服场景的短期记忆参数参考：

```text
客服 Agent 短期记忆配置：
  常驻卡：用户ID、VIP等级、当前工单号   （始终进窗口）
  滑动窗口：最近 8 轮对话原文
  摘要：窗口之外的对话压成 200 字摘要
  LRU 缓存：工具结果（订单/政策查询）保留 20 条
  TTL：当前流程锁 30s，验证码 5min
  缓存：系统指令 + 常驻卡前缀开启 Prompt Caching
```

对应 Java 初始化：

```java
ShortTermMemoryManager memory = new ShortTermMemoryManager(
        "VIP3 | 工单W2026 | 用户U12345",  // 常驻卡
        8,                                // 滑动窗口 8 轮
        20,                               // LRU 20 条
        Duration.ofMinutes(5)             // TTL 默认 5 分钟
);
```

<callout emoji="⚠️"><p>落地提醒：窗口大小、缓存条数这些参数没有"标准答案"，要靠评测调（第十六章）。我见过团队拍脑袋设 50 轮窗口，结果成本和腐烂双输。建议从"小窗口 + 摘要 + 常驻卡"起步，再用评测数据往上加。</p></callout>

---

## 八、招聘能力映射

这节课直接对应 JD 高频要求：

- "**熟悉上下文窗口管理 / 上下文工程**" → 三种窗口策略 + 防腐烂。
- "**了解缓存策略（LRU/TTL）**" → 第二节、第三节。
- "**Token 成本优化 / 提示词缓存**" → 第五节。
- "**Agent 状态/记忆管理**" → 整节。

<callout emoji="✅"><p>面试金句："短期记忆管理 = 滑动窗口保近期 + 摘要保脉络 + 常驻卡保关键 + LRU/TTL 控临时 + 提示词缓存控成本。核心是'塞得准'而非'塞得多'。" 这段话能把"我会用聊天历史"提升到"懂上下文工程"。</p></callout>

---

## 九、滑动窗口的变体：摘要窗口与重要性窗口

纯滑动窗口太粗暴，工程里常用两个变体。

```java
package com.camp.memory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 摘要窗口：保留最近 N 轮原文 + 更早的摘要。
 * 解决纯滑动窗口"丢早期脉络"的问题。
 */
public class SummarizingWindow {
    private final int recentTurns;
    private final Deque<String> recent = new ArrayDeque<>();
    private String olderSummary = "";   // 早期对话的摘要

    public SummarizingWindow(int recentTurns) { this.recentTurns = recentTurns; }

    public void add(String turn) {
        recent.addLast(turn);
        while (recent.size() > recentTurns) {
            // 溢出的最老一轮，合并进摘要（真实用模型压缩）
            olderSummary += " | " + recent.pollFirst();
        }
    }

    public List<String> snapshot() {
        return List.of("[早期摘要] " + olderSummary, "[近期] " + recent);
    }
}
```

<callout emoji="💡"><p>变体思想：近期要"原文保真"（细节），早期要"摘要保脉"（脉络）。两者结合，既省窗口又不丢主线。这正是 lesson05 摘要策略在短期层的体现。</p></callout>

## 十、短期记忆与"Lost in the Middle"

上节课提过"Lost in the Middle"——模型对上下文中部信息注意力最低。这对短期记忆布局有直接影响。

```text
上下文布局优化（对抗注意力衰减）：
  ┌─────────────────────────────────────────────┐
  │ [头部] 系统指令 + 用户卡（最常看，强注意力） │
  │ [中部] 历史对话（注意力最弱，放次要信息）    │
  │ [尾部] 当前问题 + 相关记忆（强注意力）       │
  └─────────────────────────────────────────────┘
  关键事实放头尾，噪音放中部。
```

<callout emoji="⚠️"><p>实测结论：关键信息放窗口首尾，模型命中率明显更高。所以我们的 ShortTermMemoryManager 把"用户卡"放头部、当前问题放尾部，不是随意的——是应对注意力衰减的工程决策。</p></callout>

## 十一、对话级短期记忆的完整实现

把前面碎片拼成可运行的管理器完整版（含裁剪 + 摘要 + 常驻）。

```java
package com.camp.memory;

import java.time.Duration;
import java.util.List;

/**
 * 短期记忆管理器（完整版骨架）：
 *  常驻卡 + 摘要窗口 + LRU + TTL + 布局优化
 */
public class StmManagerFull {
    private final String userCard;
    private final SummarizingWindow window;
    private final LruMemoryCache<String, String> lru;
    private final TtlMemoryStore ttl;

    public StmManagerFull(String userCard, int recentTurns, int cacheSize, Duration ttlDur) {
        this.userCard = userCard;
        this.window = new SummarizingWindow(recentTurns);
        this.lru = new LruMemoryCache<>(cacheSize);
        this.ttl = new TtlMemoryStore(ttlDur);
    }

    /** 组装进入模型的上下文：头(卡) + 中(历史) + 尾(当前) */
    public String buildPrompt(String current) {
        StringBuilder sb = new StringBuilder();
        sb.append("[用户卡] ").append(userCard).append("\n");          // 头部：强注意力
        window.snapshot().forEach(s -> sb.append(s).append("\n"));     // 中部：历史
        sb.append("[当前问题] ").append(current);                     // 尾部：强注意力
        return sb.toString();
    }

    public void remember(String turn) { window.add(turn); }
    public void cache(String k, String v) { lru.put(k, v); }
    public void temp(String k, String v, Duration d) { ttl.put(k, v, d); }
}
```

<callout emoji="✅"><p>这个管理器就是"短期记忆的工程化成品"。它把 lesson02 讲的四种机制（窗口/摘要/LRU/TTL）+ 布局优化 + 常驻卡，集成到一个类里。你能写出来，短期记忆就算真懂了。</p></callout>

## 十二、短期记忆的成本实测视角

短期记忆直接影响每轮 token 成本。给你一个估算：

```text
成本构成（每轮）：
  系统指令 ~ 200 token（可缓存）
  用户卡   ~ 100 token（可缓存）
  历史窗口 ~ N轮 × 150 token（N=8 → 1200）
  当前问题 ~ 50 token
  ─────────────────────────
  输入 ≈ 1550 token/轮 × 轮数
  若开提示词缓存，系统指令+用户卡命中缓存 → 省 ~300 token/轮
  100万轮对话 → 缓存省 3 亿 token
```

<callout emoji="💡"><p>这就是为什么 lesson02 花大力气讲"提示词缓存"——它不是锦上添花，是短期记忆经济学的命脉。缓存住"每轮重复的前缀"，成本直接降一截。</p></callout>

## 十三、与上下文工程的衔接

短期记忆管理，正是第十五章"上下文工程"的 Select/Compress 策略在"窗口内"的落地：

```text
上下文工程策略      ↔  短期记忆手段
  Select（选入窗口）↔  滑动窗口/重要性裁剪，挑该进的
  Compress（压缩）  ↔  摘要窗口/规则压缩，省空间
  Isolate（隔离）   ↔  多用户 state 隔离，互不污染
  Write（写入）     ↔  会话落库（lesson05 部署）
```

<callout emoji="⚠️"><p>你没看错——这节课写的每个机制，都能在第十五章找到"理论名字"。先学落地，再学理论命名，是这门课的设计逻辑。学到后面你会发现"原来那就是上下文工程"。</p></callout>

## 十四、避坑清单

```text
短期记忆避坑：
❌ 纯全量历史塞窗口（爆/腐烂）
❌ 关键身份只靠滑动窗口（会被滑掉）
❌ 不缓存重复前缀（白白烧 token）
❌ 噪音和信号一视同仁（Lost in Middle）
✅ 常驻卡放头尾 + 摘要窗口保脉络
✅ LRU/TTL 管临时信息
✅ 提示词缓存省成本
✅ 多用户 state 隔离
```

<callout emoji="💡"><p>短期记忆是"每轮都在发生"的事，它的优化红利是复利——每轮省一点，百万轮就是巨量。别因为它"看起来简单"就敷衍，它是 Agent 成本和质量的命门。</p></callout>

## 十五、短期记忆的监控指标

短期记忆也要可观测。给你几个关键指标：

```text
短期记忆监控指标：
  ├─ 窗口占用率：当前 token / 窗口上限（高=快爆）
  ├─ 缓存命中率：提示词缓存命中比例（低=没生效）
  ├─ 摘要触发频率：多久触发一次摘要压缩（过频=窗口太小）
  ├─ TTL 淘汰数：过期临时记忆清理量
  └─ 裁剪丢条数：滑动窗口丢了多少历史（评估是否丢重要信息）
```

```java
package com.camp.memory;

/** 短期记忆监控计数（示意） */
public class StmMetrics {
    public int windowTokens;
    public int cacheHits;
    public int cacheMisses;
    public double cacheHitRate() {
        int total = cacheHits + cacheMisses;
        return total == 0 ? 0 : (double) cacheHits / total;
    }
}
```

<callout emoji="💡"><p>监控的意义：窗口占用率持续 90%+，说明该扩窗口或加强摘要；缓存命中率低，说明前缀没稳定下来。这些指标帮你"用数据调短期记忆"，而非拍脑袋。</p></callout>

## 十六、短期记忆与长期记忆的协同

短期和长期不是割裂的两套，而是"接力"关系：

```text
协同流程：
  当前对话 → 短期窗口（即时推理）
       │ 关键事实提炼
       ▼
  长期向量库（跨会话持久）
       │ 检索召回
       ▼
  回到短期窗口（注入相关记忆）
```

```java
package com.camp.memory;

/**
 * 短期↔长期协同：短期窗口满了，把重要信息沉淀到长期。
 */
public class StmLtmBridge {
    private final SummarizingWindow stm;
    private final Object ltmStore; // 真实用 VectorStore

    public StmLtmBridge(int windowSize) {
        this.stm = new SummarizingWindow(windowSize);
        this.ltmStore = null; // 占位
    }

    /** 窗口溢出时，把摘要沉淀到长期记忆 */
    public void onOverflow(String summary) {
        // 真实：embedding(summary) → vectorStore.add(...)
        System.out.println("[沉淀长期] " + summary);
    }
}
```

<callout emoji="💡"><p>协同的本质是"信息的升降级"：即时信息在短期流动，重要信息升级到长期，需要时再降回短期。这套升降级机制，是记忆系统"既快又持久"的关键。</p></callout>

## 小结

这节课把短期记忆的实现讲透了：

1. **三种窗口策略**：滑动窗口（保近期）、摘要压缩（保脉络）、重要性裁剪（保关键），常组合使用。
2. **LRU 缓存**：用 `LinkedHashMap(accessOrder=true)` 实现，淘汰最久未用，降低重复检索/工具的 token 消耗。
3. **TTL 过期**：给临时记忆设保质期（验证码、流程锁），过期信息会毒害推理，必须清理。
4. **组合管理器**：常驻卡（头尾防腐烂）+ 滑动窗口 + LRU + TTL 四位一体。
5. **提示词缓存**：缓存重复前缀，砍掉 30%~60% 输入成本，是短期记忆经济学核心。
6. **对抗腐烂**：精简（LRU/TTL）、结构化（标签）、常驻关键（头尾）。

记住一句话：**短期记忆管理的目标不是"塞更多"，而是"塞更准"——在有限窗口里，让信号压倒噪音。**

---

## 延伸阅读 / 引用文档

1. Anthropic：Prompt Caching 文档：<https://docs.anthropic.com/en/docs/build-with-claude/prompt-caching>
2. Gemini 上下文缓存：<https://ai.google.dev/gemini-api/docs/caching>
3. "Lost in the Middle" 论文：<https://arxiv.org/abs/2307.03172>
4. MemGPT 窗口管理（LRU/摘要）：<https://arxiv.org/abs/2310.08560>
5. Java LinkedHashMap 文档：<https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/LinkedHashMap.html>
6. 上下文工程（Context Engineering）：<https://www.anthropic.com/engineering/context-engineering>

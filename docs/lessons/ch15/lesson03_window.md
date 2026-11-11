# 第十五章第3节：上下文窗口的真相——有效上下文、上下文腐烂、提示词缓存

大家好，我是秋天。

前两节我们讲了"上下文工程是什么"和"四大策略怎么写"。这节课要泼一盆冷水：**你的上下文窗口有 200K，不代表你有 200K 有效上下文。** 很多同学把"窗口大小"当成"能力上限"，结果模型在长上下文里翻车翻得不明不白。

这节课讲什么、学完掌握什么：

- 讲什么：**有效上下文（effective context）** 和物理窗口的区别、**上下文腐烂（context rot / Lost in the Middle）** 的真相与对策、**提示词缓存（prompt caching）** 怎么把成本砍掉 50–90%。
- 学完掌握：你能解释为什么"长上下文≠好效果"；你能画出 Lost in the Middle 的注意力曲线；你能用 Anthropic/OpenAI 的 prompt caching 给系统提示词、长知识做缓存，直接降本。

<callout emoji="💡"><p>招聘映射：JD 高频写"理解 LLM 上下文窗口局限性，能设计上下文压缩与缓存方案"。这一节的内容就是这条要求的全部考点——"上下文腐烂"和"提示词缓存"是 2025–26 面试必问二连。</p></callout>

---

## 一、有效上下文 vs 物理上下文窗口

先拆一个最容易被混淆的概念：

```
物理窗口（Physical Window）         有效上下文（Effective Context）
─────────────────────────          ─────────────────────────
模型"能装下"的 token 数            模型"真的会用、用得好"的 token 数
由模型架构决定（如 200K）          由信息质量、位置、噪声决定（往往远小于窗口）
```

**有效上下文 << 物理窗口**。这是第一章就要刻进脑子的事实。

### 1.1 为什么有效上下文远小于窗口

三个原因：

1. **注意力稀释**：Transformer 的注意力是全局的，但实证研究（Lost in the Middle, 2023）证明，模型对长序列**开头和结尾**利用得最好，中间大量被"忽略"。
2. **噪声淹没**：塞进 50 条检索结果，其中 45 条无关，相关的事实被淹没。
3. **指令冲突**：长上下文里前后指令打架，模型无所适从。

<callout emoji="⚠️"><p>反直觉但重要：把窗口"填满"往往比"填一半"效果更差。很多企业一开始就追求"上 200K 模型、把所有知识塞进去"，结果准确率不升反降。正确目标是"把有效上下文控制在高质量区间"，不是"塞满"。</p></callout>

### 1.2 一个估算有效上下文的实用公式

给你一个工程上能用的粗略模型：

```
有效上下文 ≈ 物理窗口 × 利用率(η)

η 受以下因素影响（0~1）：
  - 相关性纯度（检索/Select 质量）：高 → η 高
  - 位置编排（关键放首尾）：好 → η 高
  - 噪声比：噪声少 → η 高
  - 指令一致性：无冲突 → η 高

例：200K 窗口，但检索噪声 60%、关键信息全在中间
  → η ≈ 0.3 → 有效上下文 ≈ 60K
```

这就是为什么 lesson02 的 Select/Compress 如此关键——它们直接拉高 η。

---

## 二、上下文腐烂（Context Rot / Lost in the Middle）

### 2.1 定义与实验真相

**上下文腐烂（context rot）**：随着注入上下文变长、变杂，模型对其中信息的"有效利用率"单调下降的现象。最早被 *Lost in the Middle*（Liu et al., 2023）系统揭示。

实验设计：给模型读 N 篇文档，其中 1 篇含答案，观察"答案放在第几位时模型答对率最高"。结果：

```
答对率
 100% │■                                       ■
      │ ■                                     ■
  80% │  ■                                   ■
      │   ■                                 ■
  60% │    ■                               ■
      │     ■■■■■■■■■■■■■■■■■■■■■■■
  40% │      这一大片是"中间"——模型基本不看
      └─────────────────────────────────────────
        开头(位置1)                  结尾(位置N)
```

**结论：模型对"开头"和"结尾"最敏感，"中间"几乎被无视。** 这就是"Lost in the Middle"。

### 2.2 这对你的上下文工程意味着什么

- 关键信息（用户问题、当前任务、必须用到的规则）必须放在**开头或结尾**，不要丢中间。
- 检索结果不要"按相似度顺序平铺"，要把最关键的几条放首尾。
- 超长历史要 Compress（lesson02），而不是平铺进中间。

### 2.3 Java：把关键上下文重排到首尾（位置工程）

```java
// ch15-context-engineering/src/main/java/demo/context/window/PositionArranger.java
package demo.context.window;

import demo.context.ContextItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 位置工程：把最关键的信息放首尾，降低"中间被忽略"的风险。
 * 依据：Lost in the Middle —— 模型对首尾利用最好。
 */
public class PositionArranger {

    /**
     * @param items  已按相关性排序的上下文条目
     * @return 重排后：最高相关度放最前，次高放最后，其余居中
     */
    public static List<ContextItem> arrange(List<ContextItem> items) {
        List<ContextItem> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingDouble(ContextItem::relevance).reversed());

        if (sorted.size() <= 2) return sorted;

        List<ContextItem> result = new ArrayList<>();
        // 最高相关度 → 最前（开头）
        result.add(sorted.get(0));
        // 次高相关度 → 最后（结尾）
        ContextItem tail = sorted.get(1);
        // 其余按原序放中间
        for (int i = 2; i < sorted.size(); i++) result.add(sorted.get(i));
        result.add(tail);
        return result;
    }
}
```

<callout emoji="✅"><p>位置工程是"零成本"的提升手段，不调模型、不加 token，只改排列顺序就能把关键信息的利用率拉起来。生产里常被忽视，但性价比极高。配合 lesson02 的 Select 排序一起用。</p></callout>

### 2.4 上下文腐烂的量化监控

你应该在线上监控"有效上下文利用率"，而不是只看窗口占用率：

```java
// ch15-context-engineering/src/main/java/demo/context/window/ContextRotMonitor.java
package demo.context.window;

/** 监控一次请求的上下文质量，识别腐烂风险 */
public class ContextRotMonitor {

    public record ContextHealth(
            int physicalTokens,
            int effectiveTokens,      // 估算：高相关 + 首尾
            double utilization,       // effective / physical
            boolean rotRisk) {}

    public static ContextHealth assess(int physicalTokens,
                                       int highRelevanceTokens,
                                       int middleTokens) {
        // 简单估算：首尾 + 高相关算有效，纯中间低相关算"腐烂"
        int effective = highRelevanceTokens + (int)(middleTokens * 0.3);
        double util = (double) effective / physicalTokens;
        boolean rotRisk = util < 0.5;   // 利用率低于 50% 视为腐烂风险
        return new ContextHealth(physicalTokens, effective, util, rotRisk);
    }
}
```

配套的监控图（Prometheus 指标）：

```
context_utilization_ratio  ── 应 > 0.5，跌破则告警
context_physical_tokens    ── 窗口占用
context_rot_risk_count     ── 腐烂风险累计次数
```

<callout emoji="⚠️"><p>腐烂不是"上下文长了就烂"，而是"长了且中间塞了不相关的"。监控时别只盯长度，要盯"利用率"。利用率持续低于 0.4，先查 Select/Compress 是不是没生效，而不是去换更大的模型。</p></callout>

---

## 三、提示词缓存（Prompt Caching）：省 50–90% 成本

### 3.1 为什么需要缓存

你的系统提示词、角色设定、长知识库片段，**每一次请求都原样重发**。假设系统提示词 2000 token、每天 100 万次调用：

```
无缓存：2000 tok × 1,000,000 = 20 亿 输入 tok/天  ← 纯重复开销
有缓存：首次 2000 tok 计入"写入"，后续命中只计极低成本
        → 输入成本可降 50%~90%（各家计费不同）
```

这就是 **Prompt Caching**：把"前缀稳定不变"的那段上下文缓存下来，模型复用 KV Cache，你为"缓存命中"付的钱远低于"重新算"。

### 3.2 哪些内容最适合缓存

```
┌──────────────────────────────────────────────────────────┐
│ 高缓存收益（前缀稳定、反复出现）                            │
│  • 系统提示词 / 角色设定（每次都发，几乎不变）             │
│  • 工具 schema 描述（工具不变就不变）                      │
│  • 长知识库片段 / 政策原文（同一会话多次引用）             │
│  • few-shot 示例库（固定）                                 │
├──────────────────────────────────────────────────────────┤
│ 低缓存收益（每次都变，没法缓存）                            │
│  • 用户当轮问题（每次不同）                               │
│  • 实时检索结果（每次不同）                               │
│  • 多轮对话最新一句                                         │
└──────────────────────────────────────────────────────────┘
```

关键工程原则：**把"稳定不变"的前缀放最前面，把"每次变化"的放最后面**。这样缓存命中率最高——缓存是按"前缀匹配"的。

### 3.3 Anthropic Prompt Caching 实战（Java / Spring AI 风格）

Anthropic 的 prompt caching 通过 `cache_control` 标记来做。下面是 Spring AI 调 Anthropic 时启用缓存的思路：

```java
// ch15-context-engineering/src/main/java/demo/context/cache/AnthropicCacheConfig.java
package demo.context.cache;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.anthropic.ApiKey;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;

import java.util.Map;

/**
 * 启用 Anthropic Prompt Caching：
 * 把"系统提示词 + 工具 schema"这个稳定前缀标为可缓存。
 * 注意：不同 SDK 写法不同，核心是把 cache_control 打到稳定前缀的末尾。
 */
public class AnthropicCacheConfig {

    public static ChatClient buildCached(String apiKey, String systemPrompt,
                                         String toolSchemas) {
        // 关键：把稳定前缀（system + tools）放在最前，并打 cache_control
        String cachedPrefix = systemPrompt + "\n\n" + toolSchemas;

        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .model("claude-sonnet-4-5")
                // 实际 SDK 中通过 message 的 cache_control 字段标记
                // 这里示意：把前缀标记为 ephemeral 缓存（5min TTL）
                .build();

        AnthropicChatModel model = AnthropicChatModel.builder()
                .apiKey(new ApiKey(apiKey))
                .defaultOptions(options)
                .build();

        return ChatClient.builder(model)
                .defaultSystem(cachedPrefix)   // 稳定前缀 → 高频缓存命中
                .build();
    }
}
```

### 3.4 OpenAI 的 Prompt Caching（自动命中）

OpenAI 对"前缀相同的上下文"自动启用缓存（Automatic Prompt Caching），无需显式标记，但前提是**前缀稳定且超过一定 token 阈值**：

```python
# ch15-context-engineering 示例代码（Python 侧对照，便于你理解计费）
# 场景：把超长 system prompt 放最前，OpenAI 自动缓存命中
import os
from openai import OpenAI

client = OpenAI(api_key=os.environ["OPENAI_API_KEY"])

SYSTEM_PROMPT = open("system_prompt.txt").read()  # 稳定的长系统提示（>1024 tok 才易命中）

resp = client.chat.completions.create(
    model="gpt-4o-mini",
    messages=[
        {"role": "system", "content": SYSTEM_PROMPT},   # 稳定前缀 → 自动缓存
        {"role": "user",   "content": user_question},    # 每次变化 → 不缓存
    ],
)
# 计费端：SYSTEM_PROMPT 部分按 cached_input 低价计（约为原价 1/10）
print(resp.usage.prompt_tokens_details)  # 含 cached_tokens 字段
```

<callout emoji="💡"><p>成本直觉：缓存命中部分通常按原价的 10%~50% 计费（各家不同），且有些家对"缓存写入"还有一次性便宜写入费。系统提示词越长、调用越频繁，缓存收益越夸张。把"长系统提示词 + 工具 schema"做成稳定前缀，是成本治理第一课（也呼应第十章）。</p></callout>

### 3.5 缓存与 Isolate 的协同（多租户场景）

缓存有个坑：**别把"带租户隐私"的内容放进共享缓存**。否则 A 客户的缓存被 B 客户命中，就是泄漏。

```java
// ch15-context-engineering/src/main/java/demo/context/cache/TenantAwareCache.java
package demo.context.cache;

import demo.context.ContextItem;

import java.util.List;

/**
 * 租户感知的缓存策略：
 *  • 通用系统提示词 / 工具 schema → 全局共享缓存（高收益）
 *  • 租户私有知识 / 用户画像 → 不进全局缓存，或按 tenantId 分命名空间
 */
public class TenantAwareCache {

    public static boolean eligibleForGlobalCache(ContextItem item) {
        return switch (item.type()) {
            case SYSTEM, TOOL -> true;          // 通用，可共享缓存
            case MEMORY, RETRIEVED -> false;    // 可能含租户私有，隔离
            default -> false;
        };
    }

    /** 组装时分离"可缓存前缀"和"不可缓存尾部" */
    public static record CachePlan(String cacheablePrefix, String volatileTail) {}

    public static CachePlan plan(List<ContextItem> items) {
        StringBuilder prefix = new StringBuilder();
        StringBuilder tail = new StringBuilder();
        for (ContextItem it : items) {
            if (eligibleForGlobalCache(it)) prefix.append(it.content()).append("\n");
            else tail.append(it.content()).append("\n");
        }
        return new CachePlan(prefix.toString(), tail.toString());
    }
}
```

<callout emoji="⚠️"><p>缓存合规红线：把用户隐私、租户数据、内部政策原文标成"全局缓存"，等于把这些内容放进了一个可能被他人请求命中的共享池。多租户场景必须按 tenantId 隔离缓存命名空间，或只对"通用、无敏感"的前缀开缓存。这是上下文工程 + 安全治理（第十二章）的交叉点。</p></callout>

---

## 四、三件事如何协同：有效上下文 = f(腐烂控制, 缓存)

把整节串起来，给你一个"上下文窗口治理"的判断框架：

```
目标：在物理窗口内，最大化"有效上下文"，最小化"成本"

手段一：对抗腐烂
  → Select 提升纯度（lesson02）
  → 位置工程把关键放首尾（本节 2.3）
  → Compress 砍掉中间噪声（lesson02）
  → 监控利用率，低于 0.5 告警（本节 2.4）

手段二：降低成本
  → 稳定前缀放最前（系统提示词/工具 schema）
  → 启用 Prompt Caching，命中部分省 50~90%
  → 租户私有内容隔离缓存（本节 3.5）

约束：租户隔离不能破（Isolate, lesson02）
```

### 4.1 一个完整的"窗口治理"配置（YAML）

```yaml
# ch15-context-engineering/src/main/resources/context-window.yml
context-window:
  physical-limit: 200000            # 物理窗口
  effective-target-ratio: 0.6       # 目标有效利用率（低于告警）
  rot:
    position-arrange: true          # 关键放首尾
    middle-ignore-guard: true       # 监控中间段利用率
  cache:
    enabled: true
    provider: anthropic             # anthropic | openai
    cache-stable-prefix: true       # 系统提示词 + 工具 schema 缓存
    tenant-isolation: true          # 租户私有不进全局缓存
    estimated-saving-percent: 70    # 预期降本 50~90%
  compress-budget-tokens: 4000       # 单轮注入上限
```

### 4.2 衰减曲线对照（为什么大窗口不是银弹）

```
模型准确率 vs 注入上下文长度（示意，实际情况因模型而异）：

准确率
 高 │■■■■■■■■■■■■
    │■■        ■■■■
    │■            ■■
 中 │■              ■■        ← 超过"甜点区"后，利用率下降
    │                  ■■
 低 │                    ■■■■■■■■■■■■■■■■■■■■
    └───────────────────────────────────► 注入长度
        甜点区          过度区(腐烂)
```

工程启示：**找到你的"甜点区"长度，用 Compress 把注入控制在甜点区内，而不是能塞多少塞多少。**

<callout emoji="✅"><p>一句话总结这三件事的关系：有效上下文是"目标"，上下文腐烂是"敌人"，提示词缓存是"降本武器"。上下文工程就是用 Select/Compress/位置工程打敌人、用缓存降成本、最终守住有效上下文这个目标的系统工程。</p></callout>

---

## 五、企业落地：对账 Agent 的成本治理实战

某金融对账 Agent，原方案每次请求发 6000 token 系统提示词（规则全集）+ 3000 token 检索知识，日调用 200 万次：

```
改造前（无缓存）：
  9000 tok × 2,000,000 = 180 亿 输入 tok/天
  × 单价 → 月成本 ≈ 高

改造后（Prompt Caching + Compress）：
  系统提示词 6000 tok → 标为稳定前缀，缓存命中（计 10% 价）
  检索知识改用 Select 压到 1000 tok，Compress 再砍噪声
  → 实际计费 tok 降约 75%
  → 月成本 ≈ 改造前的 25%
```

**为什么这样设计？** 规则全集每次都发是纯重复开销，且 6000 token 里当前这笔往往只用 3 条规则（Isolate/Select 思想）。缓存 + 压缩双管齐下，既降成本又降腐烂风险。

---

## 六、面试自测

1. **有效上下文和物理窗口的区别？** —— 物理是"装得下"，有效是"用得好"，后者远小于前者。
2. **Lost in the Middle 是什么？** —— 模型对长上下文首尾利用好、中间忽略，关键放首尾。
3. **Prompt Caching 省多少？** —— 命中部分通常 50%~90% 降价，前提是稳定前缀放最前。
4. **缓存和租户隔离怎么冲突？** —— 私有内容不能进共享缓存，否则跨租户泄漏，需按 tenantId 隔离。

<callout emoji="💡"><p>面试官最爱追问："你们怎么控制上下文成本的？" 标准答法三件套：① 稳定前缀 + Prompt Caching；② Select/Compress 控制注入长度；③ 租户私有隔离缓存。这三句一出口，成本治理能力直接坐实。</p></callout>

---

## 七、小结

这节课拆穿了"窗口越大越好"的迷思，钉死三件事：

1. **有效上下文 << 物理窗口**：受注意力稀释、噪声淹没、指令冲突影响。目标是抬高利用率 η，不是填满窗口。
2. **上下文腐烂（Lost in the Middle）**：模型对首尾敏感、中间无视。对策是位置工程（关键放首尾）+ Select 提纯度 + Compress 砍噪声 + 线上监控利用率（<0.5 告警）。
3. **提示词缓存省 50–90%**：把稳定前缀（系统提示词/工具 schema）放最前并标记缓存，命中部分低价计费；租户私有内容必须隔离缓存，否则合规事故。

三者协同：以"最大化有效上下文"为目标，用腐烂控制保质量，用缓存降成本。

---

## 延伸阅读 / 引用文档

1. *Lost in the Middle: How Language Models Use Long Contexts*（腐烂源头论文）：https://arxiv.org/abs/2307.03172
2. Anthropic Prompt Caching 官方文档：https://docs.anthropic.com/en/docs/build-with-claude/prompt-caching
3. OpenAI Prompt Caching（自动命中）文档：https://platform.openai.com/docs/guides/prompt-caching
4. Anthropic 上下文工程博客（含缓存协同）：https://www.anthropic.com/engineering/effective-context-engineering
5. Google 长上下文评测研究（Needle In A Haystack）：https://github.com/gkamradt/LLMTest_NeedleInAHaystack
6. 上下文窗口与成本治理（LangChain 博客）：https://blog.langchain.dev/context-engineering-for-agents/

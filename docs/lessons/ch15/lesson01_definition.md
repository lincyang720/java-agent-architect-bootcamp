# 第十五章第1节：上下文工程的定义与为什么比 Prompt Engineering 更重要

大家好，我是秋天。

这一章是 V2 我们根据 2026 招聘需求新增的专章。先说一个残酷的事实：2024 年面试官问你"会不会写提示词"，2026 年面试官问的是"你会做上下文工程吗、有没有搭过评测体系"。前者是手艺人，后者是工程师——这道分水岭比你想的更硬。

这节课讲什么、学完掌握什么：

- 讲什么：先给"上下文工程（Context Engineering）"一个准确的定义（不玩玄学），再把它和"提示词工程（Prompt Engineering）"做一次彻底的对比，最后用企业落地场景（客服、对账、知识库问答）把"为什么它更重要"讲透。
- 学完掌握：你能用一句话向面试官解释上下文工程；你能说清"提示词"和"上下文"的边界在哪；你能指出企业里"上下文没管好"会炸出什么具体事故；你知道这条能力在 JD 里的确切写法。

<callout emoji="💡"><p>招聘映射：打开 2025–2026 任意一份大厂/独角兽的"AI Agent 工程师"JD，几乎必有一条"具备 Context Engineering 能力""能设计上下文管理策略""理解上下文窗口与提示词缓存"。这是和"会调 API"拉开差距的头号硬技能，没有之二。本章四节全部围绕它展开。</p></callout>

---

## 一、先给定义：上下文工程到底是什么

很多人把上下文工程当成"提示词工程的升级版话术"。错。它是**一门独立的工程学科**。

我给你一个能写进简历的定义：

> **上下文工程（Context Engineering）= 在 Agent 的每一次推理前，系统性地构造、筛选、压缩、隔离"喂给大模型的那一段输入上下文"的工程方法。** 目标是：让模型在有限的上下文窗口里，始终看到"对当前决策最有用的信息"。

注意三个关键词：**系统性**（不是手调一句 prompt）、**每一次推理前**（上下文是动态的、随任务变化的）、**最有用的信息**（选择比堆砌重要）。

<callout emoji="✅"><p>一句话记忆：提示词工程回答"怎么跟模型说话"，上下文工程回答"在模型开口前，我们到底把什么、以什么顺序、花多少 token 摆到它面前"。前者是话术，后者是架构。</p></callout>

### 1.1 Anthropic 的官方定义

2025 年 Anthropic 发布《Effective context engineering for AI agents》博客，把上下文工程定义得非常工程化。他们的原话大意是：

> Context engineering is the discipline of filling the context window with just the right information — and only that information — for the task at hand.

并且明确点出：上下文窗口不是越大越好，关键是你往里放了什么、怎么放。Anthropic 在文中把 Agent 的上下文拆成几类内容：

```
Agent 上下文窗口里通常包含（按位置权重排序）：

┌─────────────────────────────────────────────────────────┐
│ 1. 指令 / System Prompt     角色、规则、输出格式（最权威） │
│ 2. 工具描述 / Tool Schemas  模型能调用什么                 │
│ 3. 上下文状态 Context State 记忆、检索结果、当前任务状态   │
│ 4. 对话历史 / 用户消息       多轮交互内容                  │
│ 5. 少数示例 Few-shot        动态检索拼入                  │
└─────────────────────────────────────────────────────────┘
```

Anthropic 的原话还有一个重要判断：**"Prompt engineering is a subset of context engineering."** 即提示词工程只是上下文工程的一个子集——它管"指令怎么写"，而上下文工程管"指令 + 工具 + 记忆 + 检索 + 历史 + 示例"这一整窗子东西怎么组织。

<callout emoji="💡"><p>业界共识：2025 年 8 月那场关于"上下文工程 vs 提示词工程"的讨论（Google 的 Bowen Wang、Shopify 的 Vijay 等都有公开表达）基本达成一致——上下文工程是 Agent 时代的核心工程能力，提示词只是其中一小块。这也是为什么招聘 JD 从"Prompt Engineering"转向"Context Engineering"。</p></callout>

---

## 二、和 Prompt Engineering 的正面对比

为了让"重要在哪"说清楚，我拉一张表，把两者从 7 个维度掰开：

| 维度 | Prompt Engineering（提示词工程） | Context Engineering（上下文工程） |
|------|----------------------------------|-----------------------------------|
| 关注对象 | 单条指令 / 模板怎么写 | 整个上下文窗口的内容组织 |
| 是否动态 | 大多静态（写好就固定） | 高度动态（每次推理前重算） |
| 涉及组件 | System/User 消息 | 指令+工具+记忆+检索+历史+示例 |
| 典型手段 | 角色设定、few-shot、思维链 | Write/Select/Compress/Isolate（本章 lesson02） |
| 成本关注 | 较少涉及 | 核心关注（token 省 50–90%，lesson03） |
| 失败模式 | 模型不听话、输出格式错 | 上下文腐烂、信息淹没、幻觉、超窗口 |
| 工程化 | 模板外置、版本化 | 当作"可治理资产"（本章 lesson04） |

### 2.1 一个具体例子：同样的对账任务

假设你要做一个"银行流水自动对账 Agent"。

**只做 Prompt Engineering 的写法**（典型新手）：

```java
// ❌ 把"所有规则 + 所有数据 + 历史"全塞进一句 prompt
chatClient.prompt()
    .user(u -> u.text("""
        你是对账专家。规则如下：
        1. 借贷必须平衡 ...
        2. 金额以分为单位 ...
        （省略 50 条规则）
        
        以下是本月全部 2 万条流水：
        {allTwoHundredThousandRows}
        
        请找出所有不平的账目。
        """).param("allTwoHundredThousandRows", hugeCsv))
    .call();
```

问题立刻暴露：2 万行流水根本塞不进窗口（超了），塞进去了模型也"看不过来"（中间注意力稀释，lesson03 讲），而且每次请求都发这 50 条规则——贵且慢。

**做上下文工程的写法**（正确姿势）：

```java
// ✅ 上下文工程：只把"当前这一笔"需要的东西选出来、压一下、隔离开
// 1) Select：按当前待对账单据，语义检索相关的历史流水 + 适用规则子集
ContextSlice slice = contextEngine.selectFor(currentVoucher,
        SelectSpec.builder()
            .withRelevantHistory(20)          // 只看最相关的 20 条历史
            .withApplicableRules(ruleStore)   // 只取命中该类型的规则，而非全部
            .build());
// 2) Compress：单据太长就摘要
String compressed = contextEngine.compress(slice, CompressSpec.maxTokens(4000));
// 3) Isolate：对账任务和历史对话隔离，不互相污染
String result = chatClient.prompt()
    .system(isolatedSystemPrompt)            // 隔离后的系统指令
    .user(compressed)                         // 只喂当前相关上下文
    .call().content();
```

看出差别了吗？提示词工程关心"指令怎么说"，上下文工程关心"**在模型开口前，我们到底往窗口里放了什么、放了多长、是不是只跟当前这笔有关**"。后者才是工程。

<callout emoji="⚠️"><p>一个反直觉的真相：很多时候不是你的提示词写得不好，而是你喂的上下文"对，但太多"或"少了一块关键记忆"。调一个月 prompt 不如重做一次上下文选择。这也是为什么资深 Agent 工程师把 60% 精力花在上下文，而不是 prompt 措辞上。</p></callout>

---

## 三、为什么它比 Prompt Engineering 更重要

我给你三个硬理由，都是企业落地里真刀真枪的。

### 3.1 理由一：上下文窗口是 Agent 的"工作记忆"，质量决定天花板

模型再强，也只看得到窗口里的东西。你 prompt 写得再漂亮，如果关键信息（一段记忆、一条检索结果、一个工具返回）没被放进窗口，模型就是瞎子。

换句话说：**Prompt 决定模型"怎么想"，Context 决定模型"能想到什么"。** 没有后者，前者无从谈起。

### 3.2 理由二：成本与延迟是上下文工程直接控制的

这是最容易被忽视的。你的上下文每多 1000 token，每次请求都多花这 1000 token 的钱、都多耗这 1000 token 的延迟。

```
一个真实账单：
  系统提示词 1500 tok + 每次检索注入 3000 tok + 历史 2000 tok = 6500 tok/请求
  每天 50 万次调用 → 32.5 亿输入 tok/天
  用提示词缓存（lesson03）+ 上下文压缩 → 省 50%~90%
  → 每月可能省出一台 GPU 服务器的钱
```

提示词工程基本不碰这个维度；上下文工程是成本治理的第一抓手。

### 3.3 理由三：可治理、可评测、可复用

提示词是"话术"，上下文是"资产"。当你把上下文做成"可写、可选、可压、可隔离"的工程层（lesson04），它就变成可灰度、可回滚、可评测的东西——和前面第十章"工程化"、本章 lesson04、第十六章"评测闭环"完全咬合。

<callout emoji="💡"><p>判断你是不是真懂 Agent：如果你优化一个 Agent 的第一反应是"改 prompt 措辞"，你还在提示词阶段；如果你的第一反应是"先看看这次失败的上下文窗口里到底缺了什么、多了什么噪声"，你已经在上下文工程阶段了。面试官最爱追问这个。</p></callout>

---

## 四、企业场景痛点：上下文没管好会炸出什么

光讲道理不够，我给你三个真实会发生的生产事故，都源于"上下文管理失当"。

### 4.1 客服 Agent：历史越聊越长，越聊越傻

某电商客服 Agent 不管理上下文，把所有历史消息无脑追加。用户聊到第 20 轮，窗口被前 19 轮闲聊和过期优惠信息占满，真正的"退换货政策"被挤到中间——模型开始答非所问、重复提问、甚至忘掉用户刚说的订单号。

根因：**没有做上下文的选择与压缩**（lesson02 的 Select/Compress 直接对症）。

### 4.2 对账 Agent：跨任务上下文污染

某财务对账 Agent 处理 A 客户时，把 A 的规则和中间结论残留在上下文里，处理 B 客户时这些残留被模型误用，导致 B 的账目被错误标记为"不平"。

根因：**没有做上下文隔离（Isolate）**——不同任务/租户的上下文混在一起（lesson02 的 Isolate + lesson04 的运维/策略上下文）。

### 4.3 知识库问答：检索塞太多，答案反而错

某政企知识库问答，检索回来 20 条 chunk 全塞进去，其中 3 条是过时的旧制度。模型"Lost in the Middle"，把旧制度的错误结论当成答案输出——引发合规事故。

根因：**没有做上下文压缩和可信度筛选**（lesson03 的上下文腐烂 + lesson02 的 Compress）。

```text
企业上下文事故分类（出现频率由高到低）：
  1. 上下文腐烂 (Lost in the Middle)      —— 长上下文中间信息被忽略
  2. 跨任务/跨租户污染                    —— 没隔离
  3. 检索噪声淹没关键事实                 —— 没压缩/没精选
  4. 超窗口截断丢失关键信息               —— 没管理有效上下文
  5. 重复发送长系统提示词导致成本爆炸      —— 没用提示词缓存
```

<callout emoji="⚠️"><p>这五类事故，全都可以用本章 lesson02（四大策略）+ lesson03（窗口真相）解决。这也就是为什么"上下文工程"是 2025–26 招聘头号要求——企业不是缺会调接口的人，是缺能把这五类事故摁住的人。</p></callout>

---

## 五、上下文工程的四大策略总览（为 lesson02 铺路）

我把整章的方法论先在这里铺一张总图，下一节逐个实现：

```
┌──────────────────────────────────────────────────────────────┐
│            上下文工程四大策略 (Anthropic 框架)                  │
├──────────┬───────────────────────────────────────────────────┤
│ Write    │ 把信息"写"进上下文：记忆写入、工作笔记、状态持久化  │
│ Select   │ 从记忆/知识库"选"出当前最相关的：语义检索、路由    │
│ Compress │ 把冗长上下文"压"短：摘要、抽取、截断、缓存         │
│ Isolate  │ 把不同任务/角色上下文"隔"开：子 Agent、沙箱、租户  │
└──────────┴───────────────────────────────────────────────────┘
```

记住这四个词：**Write / Select / Compress / Isolate**。下节课我每个都给你可运行的 Java（Java 17）实现，落地到记忆写入、语义检索选择、摘要压缩、隔离 Agent 上下文四个真实场景。

### 5.1 一个最小可运行的"上下文装配"骨架

先给你一个贯穿本章的 Java 接口设计，让你对"上下文层"长什么样有体感：

```java
// ch15-context-engineering/src/main/java/demo/context/ContextEngine.java
package demo.context;

import java.util.List;

/**
 * 上下文引擎：Agent 每次推理前，调用它装配上下文窗口。
 * 这是"上下文即资产"的落点（lesson04 细化）。
 */
public interface ContextEngine {

    /** Write：把一条信息写入长期/短期记忆 */
    void write(MemoryEntry entry);

    /** Select：基于当前任务，从记忆+知识库选出最相关片段 */
    ContextSlice select(SelectSpec spec);

    /** Compress：把一段上下文压到目标 token 预算内 */
    String compress(ContextSlice slice, CompressSpec spec);

    /** Isolate：为某个子任务/租户创建隔离的上下文视图 */
    ContextView isolate(String tenantId, String taskId);
}

/** 一次上下文切片的载体 */
record ContextSlice(List<ContextItem> items, long estimatedTokens) {}

/** 单个上下文条目：带来源、类型、优先级，便于治理 */
record ContextItem(
        String id,
        String content,
        ContextType type,        // SYSTEM / MEMORY / RETRIEVED / HISTORY / TOOL
        double relevance,        // 相关性评分，Select 用
        String source) {}        // 来源（用于血缘/可溯源，lesson04）

enum ContextType { SYSTEM, MEMORY, RETRIEVED, HISTORY, TOOL }
```

这个接口会在 lesson02 被四个策略分别实现，在 lesson04 被"企业级上下文层"包成可治理服务。先记住它的形状。

---

## 六、和课程其他章节的关系

上下文工程不是孤岛，它在课程里是这样咬合的：

```
第六章 记忆机制      ──► 给 ContextEngine.write/select 提供存储底座
第七章 RAG          ──► 给 ContextEngine.select 提供检索与压缩能力
第六章 记忆压缩      ──► 与 lesson03 的上下文压缩同源
第十章 工程化/成本   ──► 与 lesson03 的提示词缓存、lesson04 治理咬合
第十一章 MCP/A2A    ──► 给 lesson04 企业级上下文层提供协议底座
第十六章 评测        ──► 上下文质量必须可评测（本章是评测对象之一）
```

<callout emoji="✅"><p>学习路径建议：如果你前面第六章（记忆）、第七章（RAG）没吃透，这节课的 Select/Compress 会显得抽象。建议先回看这两章，再回来——上下文工程本质上是"把记忆和 RAG 的结果，正确地组织进窗口"，底座就是它们。</p></callout>

---

## 七、常见的三个误解

1. **"上下文工程 = 把窗口塞满"**。错。塞满是灾难的开始。上下文工程的精髓是"只放对的、只放当前需要的"。
2. **"窗口越大越省事"**。错。长上下文带来腐烂（lesson03）、成本和延迟。128K 窗口不等于 128K 有效上下文。
3. **"提示词写好就行"**。错。见第二节对比表。Prompt 是子集，Context 是全集。

<callout emoji="💡"><p>面试高频反问："你们上下文窗口多大？"——别只报数字。正确答法是："我们用 XX K 窗口，但通过 Select/Compress 把有效上下文控制在 Y K 以内，配合提示词缓存把成本降了 Z%，并用 Isolate 防止跨租户污染。"这句话直接把你和"调 API 的"区分开。</p></callout>

---

## 八、上下文工程与经典工程学科的同源思想

很多同学觉得"上下文工程"是 LLM 时代凭空冒出来的新词。其实它的内核和经典软件工程、SRE 完全同源——只是对象从"数据/缓存"变成了"模型的输入窗口"。

### 8.1 与 SRE 错误预算（Error Budget）同源

SRE 用"错误预算"在"迭代速度"和"稳定性"之间找平衡。上下文工程用"有效上下文预算"在"信息全"和"注意力准"之间找平衡：

```
SRE:        发布次数 ↑  ⇒ 出错风险 ↑  ⇒ 用 Error Budget 卡住
上下文工程: 注入信息 ↑  ⇒ 腐烂风险 ↑  ⇒ 用 有效上下文预算 卡住（lesson03）
```

两者都是"承认资源有限，做有约束的优化"，而不是"无限堆料"。

### 8.2 与数据库索引/缓存同源

你不会把整张表扫进内存，而是建索引、做缓存。上下文工程同理：不会把整库塞进窗口，而是 Select（索引）+ Cache（提示词缓存，lesson03）。

```java
// ch15-context-engineering/src/main/java/demo/context/ContextBudget.java
package demo.context;

/**
 * 有效上下文预算：类比"错误预算"，在注入前先算账。
 * 防止无脑堆信息导致上下文腐烂（lesson03）。
 */
public class ContextBudget {
    private final long physicalLimit;     // 物理窗口上限
    private final double targetUtil;      // 目标有效利用率（如 0.6）

    public ContextBudget(long physicalLimit, double targetUtil) {
        this.physicalLimit = physicalLimit;
        this.targetUtil = targetUtil;
    }

    /** 允许注入的"有效 token 预算" */
    public long effectiveBudget() {
        return (long) (physicalLimit * targetUtil);
    }

    /** 校验：拟注入量是否超预算（超了就必须 Compress/Select） */
    public boolean overBudget(long plannedTokens) {
        return plannedTokens > effectiveBudget();
    }
}
```

### 8.3 与"关注点分离"同源

软件工程讲"关注点分离"，上下文工程讲"上下文分层"（第七章客服分层提示词、本章 lesson04 四层上下文）。本质都是"把会变的东西和稳定的东西分开管"。

<callout emoji="✅"><p>把上下文工程和 SRE / 数据库 / 软件工程类比，不是为了掉书袋，而是让你能**用已有的工程直觉去理解新概念**。面试官听到"上下文预算类比错误预算"，会立刻觉得你是工程思维，不是追热词。</p></callout>

---

## 九、从 Prompt Engineering 到 Context Engineering 的团队迁移路径

这是很多技术负责人真实的困惑："我团队现在只会写 prompt，怎么过渡到能做上下文工程？" 给你一条可执行的路径，分三阶段。

### 9.1 阶段一：提示词资产化（1–2 周）

- 把硬编码 prompt 外置到配置中心（Nacos/Apollo）或 Prompt 平台（第三章 lesson07）。
- 建立 prompt 版本管理与 A/B（第三章 lesson03）。
- 产出：prompt 可变、可灰度、可回滚。

### 9.2 阶段二：上下文组件化（2–4 周）

- 落地本章 lesson02 的四大策略：Write（记忆）、Select（语义检索）、Compress（压缩）、Isolate（隔离）。
- 引入上下文预算（8.3 节）防止腐烂。
- 产出：每次推理前有"上下文装配"环节。

### 9.3 阶段三：上下文治理化（1–2 月）

- 建企业级上下文层（lesson04）：语义/血缘/运维/策略四类 + MCP/A2A 协同。
- 接提示词缓存降本（lesson03）。
- 接第十六章评测闭环。
- 产出：上下文成为可治理资产。

```text
团队能力演进：
  只会写 prompt ──► 会管上下文组件 ──► 会治理上下文资产
      │                  │                    │
   阶段一(资产化)     阶段二(组件化)       阶段三(治理化)
   招聘要求"会提示词" 招聘要求"会上下文工程" 招聘要求"企业级 Agent 架构"
```

<callout emoji="💡"><p>技术负责人视角：这个迁移路径本身就是你简历上的"团队建设"亮点。面试被问"你怎么带团队做 Agent 工程化"，用这三阶段回答，比空谈"我们用了 LangChain"有力得多。</p></callout>

---

## 十、更多企业落地场景：不止客服和对账

前面用了客服、对账两个场景，再补三个高频场景，让你看到上下文工程的普适性。

### 10.1 知识库问答（KBQA）

痛点：知识库几百万文档，直接塞不进窗口；旧制度干扰新答案。
上下文工程解法：Select（语义检索最相关 5 条）+ Compress（压掉无关段落）+ 血缘（标来源，lesson04）+ Isolate（按租户隔离私有知识）。

```
用户问 → Select 检索 Top5 → Compress 压到 2000 tok
       → Isolate(租户) → 带血缘生成 → 答案可溯源
```

### 10.2 代码生成 Agent

痛点：仓库几百万行，模型看不到全部；历史改动上下文易冲突。
上下文工程解法：Select（按当前文件语义检索相关模块/历史 PR）+ Compress（长文件摘要）+ Isolate（不同任务分支上下文隔离，防互相污染）。

### 10.3 数字员工（长效自主 Agent）

痛点：任务跨数天、数十步，上下文必须跨会话持久。
上下文工程解法：Write（任务状态持久化到长期记忆）+ Select（每步只取相关状态）+ Compress（历史递归摘要，lesson02）+ Isolate（多个子目标各自上下文）。

<callout emoji="⚠️"><p>一个共性规律：场景越"长任务、多知识、多租户"，上下文工程的价值越大。短平快的单轮问答，提示词工程勉强够用；但只要是"企业级 Agent"，上下文工程几乎必上。这也是招聘 JD 把"上下文工程"列为头号要求的原因。</p></callout>

---

## 十一、这一能力在 JD 里的确切写法

我给几个 2025–2026 真实 JD 片段（脱敏），你写简历时对照：

- "设计并实现 Agent 的 Context Engineering 策略（Write/Select/Compress/Isolate）"
- "理解 LLM 上下文窗口的局限性，能设计上下文压缩与缓存方案"
- "有 RAG 上下文压缩、提示词缓存、上下文隔离的落地经验"
- "能搭建 Agent 评测与质量闭环"（这条是第十六章）

看到没？**"上下文工程"已经和"评测闭环"并列，成为 Agent 岗位的两大硬通货。** 你把这章学透，面试主动权直接翻转。

---

## 十二、小结

这一节我们钉死了三件事：

1. **定义**：上下文工程是在 Agent 每次推理前，系统性地构造、筛选、压缩、隔离"喂给模型的输入上下文"的工程方法。提示词工程只是它的子集。
2. **对比**：从关注对象、动态性、涉及组件、成本、失败模式等 7 个维度，提示词工程管"怎么说话"，上下文工程管"把什么、按顺序、花多少 token 摆到模型面前"。
3. **为什么更重要**：上下文决定模型"能想到什么"（天花板）、直接控制成本与延迟、且可治理可评测。企业里五类上下文事故（腐烂/污染/噪声/截断/成本爆炸）全靠它解决。

记住四个策略词 **Write / Select / Compress / Isolate**，下节课逐个给你可运行的 Java 实现。

---

## 延伸阅读 / 引用文档

1. Anthropic《Effective context engineering for AI agents》官方博客：https://www.anthropic.com/engineering/effective-context-engineering
2. Google DeepMind / Google 的上下文工程观点（Bowen Wang 等公开论述整理）：https://www.youtube.com/watch?v=59Otl-Oofpk
3. Shopify Vijay 关于"Context Engineering"的公开讨论（Latent Space）：https://www.latent.space/
4. LCM 论文 *Lost in the Middle: How Language Models Use Long Contexts*：https://arxiv.org/abs/2307.03172
5. Anthropic《Claude 提示词工程》手册（子集参考）：https://docs.anthropic.com/en/docs/build-with-claude/prompt-engineering/overview
6. LangChain 上下文工程实践（RAG + 压缩 + 记忆）：https://blog.langchain.dev/context-engineering-for-agents/

# 第八章第六节：自我提示（Self-Prompting）：动态思维模式切换技术

大家好，我是秋天。

前面五节我们讲完了认知架构的五大支柱。这一节和下一节，我们讲两个"让支柱协同得更好"的高级技巧。先讲**自我提示（Self-Prompting）**——简单说，就是让 Agent 自己决定"我现在该用哪种思维模式"，而不是你写死一套 prompt 用到底。

学完这节课，你能掌握：

1. **自我提示的本质**：让模型先"规划自己的提示/思维框架"，再据此行动，而非依赖静态写死的 prompt。
2. **动态思维模式切换**：面对不同子任务，自动切到"分析模式/创意模式/严谨模式/检索模式"。
3. **在 Java 里怎么实现**一个会"先想清楚用什么脑子再干活"的 Agent。

<callout emoji="💡"><p>招聘映射：JD 里开始出现"上下文工程""动态提示""思维链调度"等高级词。Self-Prompting 是 Context Engineering（第十五章）在认知层的落地，能讲清它，说明你理解了"Agent 不是一套 prompt 走天下"。</p></callout>

---

## 一、静态 prompt 的天花板

传统做法是给 Agent 一套固定系统提示：

```
系统提示(写死): "你是一个严谨的客服助手，只依据资料回答。"
```

问题来了：同一个 Agent 处理"用户投诉"（需要共情安抚）和"政策查询"（需要严谨精确）时，用同一套语气和策略，必然顾此失彼。

```
静态 prompt 的困境：
  投诉用户 ──► 严谨模式 → 冷冰冰，用户更怒 ❌
  查政策   ──► 共情模式 → 啰嗦不精确 ❌

自我提示的解法：
  先判断任务性质 → 动态生成"本次适用思维模式" → 再执行
```

---

## 二、自我提示的核心循环

Self-Prompting 的精髓：**让模型先为"当前任务"生成一段专属的、指导自己行动的提示（meta-prompt），再带着这段提示去干活**。

```
         ┌─────────── 自我提示循环 ───────────┐
         │                                     │
   任务 ──► [Selector] 选思维模式               │
         │        │                            │
         │        ▼                            │
         │  [Prompt Generator] 生成专属提示     │
         │        │                            │
         │        ▼                            │
         │  [Executor] 带专属提示执行子任务 ────┘
         │                                     │
         └──── 多子任务重复，各自切换思维模式 ──┘
```

### 2.1 思维模式定义

```java
// ch08-cognition/src/main/java/demo/selfprompt/ThinkingMode.java
package demo.selfprompt;

public enum ThinkingMode {
    ANALYTICAL("严谨分析模式", "逐步推理，只依据事实与数据，标注来源。"),
    CREATIVE("创意发散模式", "多角度提出方案，鼓励非常规思路，不做最终断言。"),
    EMPATHETIC("共情沟通模式", "先共情用户情绪，再提供解决方案，语气温和。"),
    RETRIEVAL("检索求证模式", "优先调用工具检索资料，不凭记忆作答。"),
    CRITICAL("批判审视模式", "对方案挑刺，列出风险与反例。");

    public final String label;
    public final String instruction;
    ThinkingMode(String l, String i) { this.label = l; this.instruction = i; }
}
```

### 2.2 选择器：判断该用哪种模式

```java
// ch08-cognition/src/main/java/demo/selfprompt/ModeSelector.java
package demo.selfprompt;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;

public class ModeSelector {

    interface Selector {
        @UserMessage("""
            从下列思维模式中选择最合适的一个来处理任务，只回模式名：
            ANALYTICAL / CREATIVE / EMPATHETIC / RETRIEVAL / CRITICAL
            任务：{{task}}
            """)
        String select(String task);
    }

    public static ThinkingMode selectMode(ChatLanguageModel model, String task) {
        Selector s = AiServices.create(Selector.class, model);
        String name = s.select(task).trim().toUpperCase();
        try { return ThinkingMode.valueOf(name); }
        catch (Exception e) { return ThinkingMode.ANALYTICAL; } // 默认严谨
    }

    public static void main(String[] args) {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY")).modelName("gpt-4o-mini").build();
        System.out.println(selectMode(model, "用户说买的手机坏了很生气"));
        // 预期: EMPATHETIC
    }
}
```

### 2.3 生成专属提示并执行（自我提示闭环）

```java
// ch08-cognition/src/main/java/demo/selfprompt/SelfPromptingAgent.java
package demo.selfprompt;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

public class SelfPromptingAgent {

    private final ChatLanguageModel model;

    public SelfPromptingAgent(ChatLanguageModel model) { this.model = model; }

    /**
     * 自我提示：先选模式 → 生成专属 prompt → 带 prompt 执行
     */
    public String solve(String task) {
        // 1) 选思维模式
        ThinkingMode mode = ModeSelector.selectMode(model, task);
        // 2) 生成"专属提示"（self-prompt）
        String selfPrompt = String.format(
            "你当前应以【%s】工作。规则：%s\n基于此，请处理任务。",
            mode.label, mode.instruction);
        // 3) 带专属提示执行
        return model.generate(selfPrompt + "\n任务:" + task).content();
    }

    public static void main(String[] args) {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY")).modelName("gpt-4o").build();
        SelfPromptingAgent agent = new SelfPromptingAgent(model);
        System.out.println(agent.solve("用户投诉物流慢，很生气"));
        System.out.println(agent.solve("对比三款机型的续航参数"));
        // 两次会自动切到 共情模式 / 严谨分析模式
    }
}
```

<callout emoji="✅"><p>Self-Prompting 的工程价值：一套系统提示变"一族思维模式 + 动态选择"。好处是同一个 Agent 能优雅地处理异质子任务，不用为每类任务单独部署一个 bot。但代价是多一次"选模式"的 LLM 调用——对延迟敏感的场景，可以把模式选择做成规则路由（见 lesson08 思路）省掉这次调用。</p></callout>

---

## 三、与规划、反思的协同

自我提示不是孤立的，它和前面讲的支柱咬合：

```
规划(lesson02) 拆出子任务
      │
      ▼
自我提示(本节) 为每个子任务选思维模式
      │  例: 子任务1=检索求证, 子任务2=严谨分析, 子任务3=共情沟通
      ▼
工具使用(lesson03) 按模式调不同工具
      │
      ▼
反思(lesson04) 评估本次模式选择是否合适 → 沉淀为策略(lesson05)
```

```java
// ch08-cognition/src/main/java/demo/selfprompt/PlanModeCoupling.java
package demo.selfprompt;

import java.util.List;

public class PlanModeCoupling {
    /** 把计划里的每一步映射到思维模式 */
    public static ThinkingMode modeForStep(String stepDesc) {
        if (stepDesc.contains("查") || stepDesc.contains("检索")) return ThinkingMode.RETRIEVAL;
        if (stepDesc.contains("对比") || stepDesc.contains("分析")) return ThinkingMode.ANALYTICAL;
        if (stepDesc.contains("用户") || stepDesc.contains("投诉")) return ThinkingMode.EMPATHETIC;
        if (stepDesc.contains("方案") || stepDesc.contains("创意")) return ThinkingMode.CREATIVE;
        return ThinkingMode.CRITICAL;
    }

    public static void main(String[] args) {
        List<String> steps = List.of("检索竞品资料", "对比功能差异", "设计新功能方案");
        steps.forEach(s -> System.out.println(s + " → " + modeForStep(s).label));
    }
}
```

---

## 四、进阶：让模型自己"写提示"而非你预设模式

更激进的 Self-Prompting：**不预设模式枚举**，而是让模型针对任务直接生成一段"自提示"，再执行。这更接近原始论文思路。

```java
// ch08-cognition/src/main/java/demo/selfprompt/OpenEndedSelfPrompt.java
package demo.selfprompt;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;

public class OpenEndedSelfPrompt {

    interface SelfPrompter {
        @UserMessage("""
            你需要处理一个任务。请先写一段给"未来的自己"的提示，
            说明处理该任务时应遵循的思维策略、步骤与注意事项。
            只输出这段提示，不要直接答题。
            任务：{{task}}
            """)
        String generateSelfPrompt(String task);

        @UserMessage("""
            {{selfPrompt}}
            ---
            现在请依据上面的自我提示，处理任务：{{task}}
            """)
        String execute(String selfPrompt, String task);
    }

    public static void main(String[] args) {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY")).modelName("gpt-4o").build();
        SelfPrompter sp = AiServices.create(SelfPrompter.class, model);

        String task = "给老板写一封项目延期说明邮件";
        String selfP = sp.generateSelfPrompt(task);   // 模型自己写提示
        String result = sp.execute(selfP, task);       // 带提示执行
        System.out.println(result);
    }
}
```

<callout emoji="⚠️"><p>开放式的自我提示最灵活，但也最不可控——模型生成的提示可能跑偏、带偏见、甚至自相矛盾。生产建议：预设模式枚举（第二节）做主，开放生成做补充，且生成的提示要过一道"安全校验"（是否含违规指令）。完全放开 self-prompt 是风险操作。</p></callout>

---

## 五、企业落地：全能数字员工的"心态切换"

某银行"全能客户经理 Agent"，处理一天的工作：

```
09:00 客户咨询利率 → 自我提示 → 检索求证模式 → 调费率库精确回答
10:30 客户抱怨服务差 → 自我提示 → 共情沟通模式 → 先安抚再解决
14:00 设计新理财产品方案 → 自我提示 → 创意发散 + 批判审视 → 多方案比选
16:00 核查合规风险 → 自我提示 → 严谨分析模式 → 逐条对照监管条款
```

**为什么这样设计？** 一个 Agent 面对"查询/投诉/创意/合规"四类性质完全不同的子任务，用一套死 prompt 必然出错。Self-Prompting 让它在每个子任务前"切换脑子"，既保质量又省去维护多个独立 bot 的成本。

---

## 六、踩坑清单

| 坑 | 后果 | 解法 |
|----|------|------|
| 一套死 prompt 通吃 | 异质子任务顾此失彼 | 动态模式切换 |
| 每次都 LLM 选模式 | 延迟/成本增 | 规则路由兜底 |
| 开放 self-prompt 不校验 | 跑偏/违规 | 预设模式为主+安全校验 |
| 模式间无衔接 | 上下文割裂 | 共享记忆贯穿 |
| 模式选择不沉淀 | 重复判断 | 沉淀策略库(lesson05) |

---

## 七、用规则路由替代理模型选模式（省一次 LLM 调用）

高吞吐场景下，每次都让 LLM 选模式太贵。可以用关键词规则路由：

```java
// ch08-cognition/src/main/java/demo/selfprompt/RuleModeRouter.java
package demo.selfprompt;

import java.util.Map;
import java.util.regex.Pattern;

public class RuleModeRouter {
    private static final Map<Pattern, ThinkingMode> RULES = Map.of(
        Pattern.compile("投诉|生气|不满|差评"), ThinkingMode.EMPATHETIC,
        Pattern.compile("对比|分析|评估|参数"), ThinkingMode.ANALYTICAL,
        Pattern.compile("方案|创意|点子|脑暴"), ThinkingMode.CREATIVE,
        Pattern.compile("查|检索|资料|文档"), ThinkingMode.RETRIEVAL,
        Pattern.compile("风险|审核|检查|质疑"), ThinkingMode.CRITICAL
    );

    public static ThinkingMode route(String task) {
        for (var e : RULES.entrySet())
            if (e.getKey().matcher(task).find()) return e.getValue();
        return ThinkingMode.ANALYTICAL; // 兜底
    }

    public static void main(String[] args) {
        System.out.println(route("用户投诉物流太慢"));  // EMPATHETIC
        System.out.println(route("对比三款手机续航"));    // ANALYTICAL
    }
}
```

<callout emoji="✅"><p>工程经验：规则路由覆盖 80% 明确场景（零成本零延迟），只对规则匹配不上的长尾才上 LLM 选择。这是全课程反复出现的"规则优先、模型兜底"哲学——能用确定性的就别上模型。</p></callout>

---

## 八、自我提示的安全护栏

开放式的 self-prompt 有风险，生成的提示可能带违规指令或偏见。加一道校验：

```java
// ch08-cognition/src/main/java/demo/selfprompt/SelfPromptGuard.java
package demo.selfprompt;

import dev.langchain4j.model.chat.ChatLanguageModel;

public class SelfPromptGuard {
    public static boolean safe(String selfPrompt, ChatLanguageModel judge) {
        String v = judge.generate(
            "检查下面这段自我提示是否含违规/危险指令，只回 SAFE/UNSAFE：\n" + selfPrompt).content();
        return v.contains("SAFE");
    }
}
```

---

## 九、与上下文工程（第十五章）的关系

自我提示本质是**上下文工程（Context Engineering）**在认知层的体现：你不是在"写死 prompt"，而是在"动态构造最合适的上下文"。第十五章会系统讲 Write/Select/Compress/Isolate 四大策略——自我提示对应的是 Select（为当前任务选择/生成合适的思维模式与提示）。

```text
Context Engineering 四大策略 × 自我提示：
  Write   → 把思维模式定义写进上下文
  Select  → 为当前子任务选择/生成合适提示（自我提示核心）
  Compress→ 精简模式描述，控制 token
  Isolate → 不同子任务的模式上下文隔离，互不干扰
```

---

## 十、落地检查清单

| 能力 | 是否具备 |
|------|----------|
| 多思维模式定义 | ☐ |
| 动态选择(规则+LLM兜底) | ☐ |
| 开放 self-prompt 安全校验 | ☐ |
| 与规划/反思/学习协同 | ☐ |

## 十一、端到端示例：心态自适应的客服 Agent

```java
// ch08-cognition/src/main/java/demo/selfprompt/AdaptiveAgent.java
package demo.selfprompt;

import dev.langchain4j.model.chat.ChatLanguageModel;

public class AdaptiveAgent {
    public String handle(ChatLanguageModel model, String userMsg) {
        // 1. 选思维模式（规则优先，长尾用LLM）
        ThinkingMode mode = RuleModeRouter.route(userMsg);
        // 2. 生成专属 self-prompt
        SelfPromptingAgent agent = new SelfPromptingAgent(model);
        // 3. 带模式执行
        return agent.solveWithMode(userMsg, mode);
    }
}
```

```
自适应流程：
  用户消息 → 路由选模式 → 生成专属提示 → 执行
    投诉类  ─► 共情模式 ─► "先安抚再解决" ─► 温和回复
    查询类  ─► 检索模式 ─► "先查证再答"   ─► 精确带引用
    分析类  ─► 分析模式 ─► "逐步推理"     ─► 结构化结论
```

---

## 十二、自我提示的评测（接第十六章）

| 指标 | 说明 |
|------|------|
| 模式选择准确率 | 选对思维模式的占比 |
| 跨模式一致性 | 同一任务多次结果稳定 |
| 延迟增量 | 选模式增加的耗时 |

<callout emoji="💡"><p>自我提示的"模式选择"本身也要评测——如果模型老选错模式，整个机制就失效。把一批"消息→正确模式"标注对做成评测集，定期跑，监控 SelPrompt 准确率，是工程闭环的一部分。</p></callout>

## 十三、自我提示的招聘话术

```
"我们不让 Agent 用一套死 prompt 通吃所有子任务。
 而是先判断当前子任务的思维模式（分析/创意/共情/检索/批判），
 动态生成专属 self-prompt 再执行。
 高频明确场景用规则路由（零成本），长尾用 LLM 选择兜底。
 开放 self-prompt 加安全校验，防止跑偏违规。
 它本质是上下文工程(第十五章)在认知层的落地。"
```

---

## 十四、自我提示 × 规划 × 反思 的协同示例

```java
// ch08-cognition/src/main/java/demo/selfprompt/Synergy.java
package demo.selfprompt;

public class Synergy {
    /** 计划每步 → 选思维模式 → 执行 → 反思模式选择是否对 */
    public static void run(String goal) {
        // 1. 规划(lesson02) 拆出步骤
        // 2. 自我提示(本节): 每步选 ThinkingMode
        // 3. 工具(lesson03) 执行
        // 4. 反思(lesson04): 评估模式选择，错则沉淀策略(lesson05)
        System.out.println("规划→选模式→执行→反思 协同闭环");
    }
}
```

<callout emoji="💡"><p>这五节（规划/工具/反思/学习/自我提示）不是孤立章节，而是一根认知闭环上的不同环节。面试时能画出"规划→自我提示选模式→工具执行→反思→学习沉淀"的闭环图，比单讲某个技巧值钱得多。</p></callout>

## 十五、自我提示完整参考实现

```java
// ch08-cognition/src/main/java/demo/selfprompt/SelfPromptingFull.java
package demo.selfprompt;

import dev.langchain4j.model.chat.ChatLanguageModel;

public class SelfPromptingFull {
    private final ChatLanguageModel model;

    public SelfPromptingFull(ChatLanguageModel m) { this.model = m; }

    public String solve(String task) {
        // 1. 规则优先选模式（长尾才用LLM）
        ThinkingMode mode = RuleModeRouter.route(task);
        // 2. 生成专属 self-prompt
        String selfPrompt = String.format(
            "你当前以【%s】工作。规则：%s", mode.label, mode.instruction);
        // 3. 安全校验开放提示
        // 4. 带提示执行
        return model.generate(selfPrompt + "\n任务:" + task).content();
    }
}
```

---

## 十六、自我提示检查清单

| 能力 | 是否具备 |
|------|----------|
| 多思维模式 | ☐ |
| 规则+LLM 路由 | ☐ |
| 开放提示安全校验 | ☐ |
| 与规划/反思协同 | ☐ |

<callout emoji="💡"><p>自我提示是"轻量但高杠杆"的能力。它不需要新基础设施，只要在现有 Agent 前加一层"思维模式选择"，就能让同一套系统优雅地处理异质子任务。性价比极高，建议所有 Agent 默认集成。</p></callout>

## 小结

这节课讲了自我提示（Self-Prompting）：

- 本质：让 Agent 先"为当前任务生成专属思维提示"再行动，而非一套死 prompt 走天下。
- 实践：预设思维模式枚举（分析/创意/共情/检索/批判）+ 选择器动态切换，性价比最高。
- 进阶：开放式的"模型自己写提示"最灵活但最不可控，需安全校验。
- 协同：与规划（每步选模式）、反思（评估模式选择）、学习（沉淀模式策略）咬合，形成完整认知闭环。
- 企业落地用"心态切换"让一个 Agent 优雅处理异质子任务。

下一节（lesson07）讲专家角色（Expert Persona）——多领域知识协同与角色扮演机制，把认知架构收尾。

---

## 延伸阅读 / 引用文档

1. Wang et al., *Self-Prompting Large Language Models* (自我提示原始研究): https://arxiv.org/abs/2206.01279
2. Wei et al., *Chain-of-Thought Prompting* (思维模式基础): https://arxiv.org/abs/2201.11903
3. Context Engineering 概览（第十五章主题）: https://www.anthropic.com/engineering/built-multi-agent-research-system
4. LangChain4j AiServices（动态角色设置）: https://docs.langchain4j.dev/tutorials/ai-services
5. Spring AI Alibaba（角色/提示管理）: https://github.com/alibaba/spring-ai-alibaba
6. Google ADK for Java（多 Agent 角色）: https://github.com/google/adk-java

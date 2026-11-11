# 第十三章第2节：从 LLM 到 Agent 的必然：能力边界与突破（推理、规划、工具）

大家好，我是秋天。

上一节我们定义了 Agentic AI 的 L1-L5。这一节我们钻进"为什么"——**为什么一个聊天模型，迟早要变成 Agent？** 答案藏在 LLM 的能力边界里。你只有搞懂 LLM 天生"做不到什么"，才能理解 Agent 是在补哪些短板。这节偏"理论 but 落地导向"，但秋天保证不水，每一点都对应你写代码时会踩的坑。

学完这一节，你将掌握：

1. LLM 的四道天生能力边界（知识截止、无状态、无执行、易幻觉）；
2. Agent 用推理、规划、工具分别"突破"哪道边界；
3. 一个"规划-执行"闭环的 Java 代码骨架；
4. "能力边界"如何直接决定架构设计；
5. 招聘里"理解 LLM 能力与局限"对应的岗位要求。

---

## 一、LLM 的四道天生边界

别神话 LLM。它本质是"超强的文本条件生成器"，有四类硬伤：

```text
   LLM 的天生边界
 ┌─────────────────────────────────────────────┐
 │ 1. 知识截止 (Knowledge Cutoff)               │
 │    → 不知道训练后的事，不掌握你的私有数据      │
 │ 2. 无状态/无持久记忆 (Stateless)             │
 │    → 每次调用是独立事件，不记得上次           │
 │ 3. 无执行能力 (No Actuation)                 │
 │    → 不能真去查库、发邮件、跑代码             │
 │ 4. 幻觉/不确定性 (Hallucination)             │
 │    → 会编看似合理但错误的内容                 │
 └─────────────────────────────────────────────┘
```

这四点，单靠"把模型调更大"解决不了。必须靠 Agent 结构去补。

<callout emoji="💡"><p>面试高频题："LLM 有什么局限？"——上面四点背下来，再各配一个你的业务例子（如"知识截止"导致答不出最新政策），立刻显得你有实战经验而非背书。</p></callout>

---

## 二、Agent 用三大能力补边界

Agent 不是新模型，而是**用结构补齐 LLM 的边界**。三项核心能力对应补三道墙：

```text
   边界  →  Agent 突破手段
 ┌──────────────────────────────────────────────┐
 │ 边界1 知识截止  ──▶  工具(Tool)               │
 │   接 RAG / 实时 API / 数据库，把外部知识喂进来 │
 │                                              │
 │ 边界2 无状态     ──▶  记忆(Memory)            │
 │   短期上下文 + 长期向量库，跨轮保持状态        │
 │                                              │
 │ 边界3 无执行     ──▶  工具(Tool/Action)       │
 │   通过 MCP/function calling 真去干活          │
 │                                              │
 │ 边界4 幻觉       ──▶  推理+规划+反思(Reasoning)│
 │   多步验证、自我修正、工具核对事实             │
 └──────────────────────────────────────────────┘
```

注意：**"推理/规划"主要用来对抗幻觉和复杂任务**，而"工具/记忆"用来对抗知识截止、无状态、无执行。三者合起来，LLM 才从"嘴"变成"手+脑+记忆"。

---

## 三、突破1：工具——对抗知识截止与无执行

最直接的突破。LLM 不知道今天天气，但工具能查。代码上就是 MCP/function calling（第十一章讲过）。这里强调一个架构点：**工具是 LLM 感知真实世界的唯一通道**。

```java
// 工具让 LLM 突破"知识截止 + 无执行"
// 没有工具：模型只能编一个天气（幻觉）
// 有工具：模型调用 query_weather("北京") → 拿到真实数据
chatClient.prompt()
    .user("北京今天天气适合出差吗？")
    .tools(weatherTool)   // ← 这是突破边界的关键
    .call()
    .content();
```

<callout emoji="⚠️"><p>但工具不是万能药——工具 itself 有延迟、有成本、有错误。Agent 设计时要考虑"工具失败怎么办"（降级、重试、换工具）。这又是工程化的坑，第十章讲过异常处理。</p></callout>

---

## 四、突破2：记忆——对抗无状态

LLM 每次调用独立。Agent 用记忆让它在多轮里"连贯"。第六章专门讲过，这里只点明它和"边界"的关系：

```text
   无状态 LLM  vs  有记忆 Agent
 ┌──────────┐        ┌──────────────────────┐
 │ turn1: 问 │        │ 记忆层: [用户偏好,    │
 │ turn2: 忘 │        │  历史对话, 长期画像]  │
 │ (断开)    │        │   ↓ 注入上下文        │
 └──────────┘        │ Agent: 连贯地接着聊   │
                     └──────────────────────┘
```

---

## 五、突破3：推理与规划——对抗幻觉与复杂任务

这是最有技术含量的突破。LLM 默认是"一次性生成答案"。复杂任务需要"先想清楚步骤，再一步步做，做错了能改"。这就是 ReAct（推理+行动）范式的来源。

给你一个**规划-执行-反思闭环**的 Java 骨架（简化版，展示结构）：

```java
package com.qiutian.agentic;

import java.util.*;

/** 极简规划-执行-反思循环（展示"推理突破幻觉"的结构） */
public class PlanActReflectAgent {

    // 假设你注入了 chatClient 和 toolExecutor
    interface Chat { String ask(String p); }
    interface ToolRunner { String run(String tool, Map<String,Object> args); }

    private final Chat chat;
    private final ToolRunner tools;

    public PlanActReflectAgent(Chat chat, ToolRunner tools) {
        this.chat = chat; this.tools = tools;
    }

    public String solve(String goal) {
        // 1. 规划：让模型先列步骤
        String plan = chat.ask("""
            目标：%s
            请输出执行计划，每步标注要用的工具。
            """.formatted(goal));

        List<String> steps = parseSteps(plan);
        StringBuilder context = new StringBuilder();

        // 2. 逐步执行 + 观察
        for (String step : steps) {
            String action = chat.ask("下一步：" + step + "\n当前已知：" + context);
            // 解析出工具调用（示意）
            if (action.contains("CALL:")) {
                String result = tools.run(extractTool(action), Map.of());
                context.append("步骤[").append(step).append("]结果=").append(result).append("\n");
            }
        }

        // 3. 反思：让模型自检是否达成目标、有无幻觉
        String review = chat.ask("""
            目标：%s
            已收集信息：%s
            请检查：是否回答了目标？有无自相矛盾或不确定的断言？
            """.formatted(goal, context));
        context.append("反思:").append(review).append("\n");

        // 4. 生成最终答案
        return chat.ask("基于以上，给出最终回答：\n" + context);
    }

    private List<String> parseSteps(String plan) {
        return Arrays.stream(plan.split("\n"))
                .filter(l -> l.matches("\\d+\\..*")).limit(5).toList();
    }
    private String extractTool(String action) { return "demo_tool"; }
}
```

<callout emoji="💡"><p>这个骨架的精髓是"分三步、每步都让模型基于真实观察再决策"——这比"一次性生成最终答案"抗幻觉得多。真实的 LangChain4j `AiServices` 的 `@Memory` + 工具，或 Spring AI 的 `ChatClient` 加 advisor，底层都是这个结构。理解它，你就不会把 Agent 当成"黑盒咒语"。</p></callout>

---

## 六、能力边界如何直接决定架构

这是这一节的"为什么这样设计"落点。每道边界对应一个架构决策：

```text
   边界  →  架构决策
 ┌──────────────────────────────────────────────────┐
 │ 知识截止 → 必须接 RAG/工具；选型要考虑检索质量    │
 │ 无状态   → 必须设计记忆层；短期用上下文、长期用向量│
 │ 无执行   → 必须设计工具/沙箱（第十二章安全）      │
 │ 幻觉     → 必须设计反思/校验/人工兜底 + 输出护栏   │
 └──────────────────────────────────────────────────┘

 反例：有人想用"更大模型"解决幻觉 → 模型再大也会编
 正解：用"工具核实 + 反思自检 + 人工兜底"结构化解
```

<callout emoji="⚠️"><p>很多团队踩的坑：把 LLM 当"全能大脑"，啥都让它直接答，结果幻觉频发。正确姿势是"LLM 负责推理规划，工具负责事实与执行，护栏负责兜底"。角色分工清楚了，系统才稳。</p></callout>

---

## 七、从 LLM 到 Agent 的"必然"逻辑

把上面串成一句话逻辑链，这就是"必然"：

```text
   LLM 有边界（截/态/执/幻）
        │ 业务要的是"完成真实任务"，不止"聊得漂亮"
        ▼
   必须补边界 → 工具(补截/执) + 记忆(补态) + 推理(补幻)
        ▼
   补边界的结构 = Agent（感知-规划-行动-反思闭环）
        ▼
   ∴ 凡是"要落地干活"的场景，LLM 必然走向 Agent
```

注意限定词：**"要落地干活"**。纯创作、纯问答的轻场景，L1 就够了，不必 Agent。所以"必然"是有条件的必然——条件就是"任务需要真实世界交互与可靠结果"。

---

## 八、招聘映射

```text
【岗位】AI Agent 架构师
【要求】
- 深刻理解 LLM 的能力边界（知识截止/幻觉/无状态/无执行）；
- 能基于边界设计 Agent 的推理、规划、工具、记忆结构；
- 有"用结构化解方案对抗幻觉"的工程经验。
```

<callout emoji="✅"><p>"理解 LLM 局限并据此设计 Agent"是架构师和开发的分水岭。很多人会调 API，但说不清"为什么需要工具/记忆/反思"——这一节给你的逻辑链，就是你面试时的杀手锏。</p></callout>

---

## 九、误区澄清

1. **"Agent 就是更聪明的模型"**：错，Agent 是结构，模型只是其中一环。
2. **"上 Agent 就自动不幻觉了"**：错，幻觉靠结构（工具核实+反思+护栏）治，不是靠 Agent 名头。
3. **"推理能力越强越不需要工具"**：错，推理和工具互补——推理决定"怎么查"，工具提供"真实数据"。

---

## 十、用 LLM-as-Judge 对抗幻觉（代码骨架）

前面说"推理规划对抗幻觉"。落地时用一个小模型做"事实核查员"：

```java
package com.qiutian.agentic;

/** 幻觉检测：让 Judge 模型核对"声称"与"工具证据"是否一致 */
public class HallucinationChecker {

    interface Chat { String ask(String p); }

    public boolean consistent(String claim, String evidence) {
        String judge = chat.ask("""
            你是事实核查员。判断【声明】是否被【证据】支持。
            仅输出 YES 或 NO。
            声明：%s
            证据：%s
            """.formatted(claim, evidence));
        return judge.trim().startsWith("YES");
    }

    private final Chat chat;
    public HallucinationChecker(Chat chat) { this.chat = chat; }
}
```

```text
   推理对抗幻觉的回路
 ┌──────────────────────────────────────────────────┐
 │ 模型生成声明 → 调工具拿证据 → Judge 核对          │
 │   一致 → 输出                                     │
 │   不一致 → 重新生成/标注"不确定"                  │
 │ 这比"直接输出"抗幻觉得多，是 L3 的核心手段       │
 └──────────────────────────────────────────────────┘
```

<callout emoji="💡"><p>注意：Judge 本身也是 LLM，也可能错。所以它和"工具证据"配合——工具给的是真实数据，Judge 只做"声明 vs 证据"的比对，职责单一，可靠性高。</p></callout>

---

## 十一、工具失败的降级设计

突破"无执行"靠工具，但工具会失败。Agent 必须能降级：

```java
package com.qiutian.agentic;

import java.util.*;

public class ResilientToolCaller {
    interface ToolRunner { String run(String t, Map<String,Object> a) throws Exception; }

    public String callWithFallback(String primary, String fallback,
                                   Map<String,Object> args, ToolRunner runner) {
        try {
            return runner.run(primary, args);
        } catch (Exception e) {
            // 主工具失败 → 降级到备用工具，而非直接报错
            try { return runner.run(fallback, args); }
            catch (Exception e2) { return "工具暂不可用，已转人工"; }
        }
    }
}
```

---

## 十二、记忆与"无状态"边界的代码表达

第六章讲过记忆，这里点明它与"边界"的对应。短期记忆解决"本轮会话语境"：

```java
// 短期：把最近 K 轮塞回 prompt（LRU 截断，防超窗口）
List<Message> window = memory.recent(8);   // 解决"无状态"
String ans = chat.ask(buildPrompt(window, userInput));
```

长期记忆解决"跨会话用户画像"：

```java
// 长期：向量库检索相关历史，注入系统上下文
List<Fact> profile = vectorStore.search(userId, currentTopic);
// 解决"模型不知道用户偏好"的隐性无状态问题
```

<callout emoji="⚠️"><p>记忆不是"存越多越好"。存太多反而稀释关键上下文（上下文腐烂，第十五章）。生产要对记忆做压缩/摘要/时间衰减，而非全量回灌。</p></callout>

---

## 十三、推理规划的可视化（调试必备）

Agent 出错时，你要能看到它的"思考链"。把每步落日志：

```text
   [TRACE T123] plan: 1.查订单 2.查物流 3.生成回复
   [TRACE T123] step1 调用 query_order(1001) → {status:已发货}
   [TRACE T123] step2 调用 query_logistics(1001) → 异常:超时
   [TRACE T123] fallback 调用 query_logistics_backup → 在途
   [TRACE T123] step3 生成回复(基于已发货+在途)
```

这套 trace 既用于调试，也用于第十二章的审计回溯。可观测性和推理规划是绑定的。

---

## 十四、面试追问：模型越大越不需要 Agent 吗

```text
问：用 GPT-5 这种更强模型，是不是就不需要工具/规划了？
答：不会。更强模型缓解的是"推理质量"，但解决不了"知识截止"
   （它不知道实时数据）、"无执行"（它不能真发邮件）、
   "无状态"（它不记得你上次说啥）。这些边界只能靠 Agent 结构补，
   与模型强度无关。强模型让 Agent 更聪明，但不让 Agent 消失。
```

---

## 小结

这一节我们从能力边界推导出 Agent 的必然性：

- **四道边界**：知识截止、无状态、无执行、幻觉；
- **三大突破**：工具（补截/执）、记忆（补态）、推理规划（补幻）；
- **代码**：规划-执行-反思闭环骨架，展示"分步基于观察决策"抗幻觉；
- **架构**：每道边界对应一个明确的架构决策，而非"换更大模型"；
- **必然**：凡要落地干活的场景，LLM 必然走向 Agent（结构化补边界）。

下一节我们聊"通用智能体 vs 专用智能体"——同样是 Agent，为什么企业里往往是"专精"胜出，以及它们的架构差异。

---

## 延伸阅读 / 引用文档

1. Anthropic《Effective Agents》（何时用/不用 Agent）：https://www.anthropic.com/engineering/building-effective-agents
2. ReAct 论文（推理+行动范式）：https://arxiv.org/abs/2210.03629
3. Plan-and-Solve / 规划型 Agent 研究：https://arxiv.org/abs/2305.04091
4. LangChain4j 工具与记忆文档：https://docs.langchain4j.dev/
5. Spring AI ChatClient 与 Advisor：https://docs.spring.io/spring-ai/reference/1.0/
6. Google ADK for Java（规划与工具）：https://google.github.io/adk-docs/

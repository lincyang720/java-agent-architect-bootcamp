# 第五章第3节：循环 Agent 与动态编排——实现复杂的多轮交互逻辑

大家好，我是秋天。

上节课我们讲了 Agent 的三种"静态"形态：基础、子、并行。静态的意思是——结构在代码里写死了，跑的时候不变。但真实业务里很多任务**结构本身就是动态的**：比如"反复改方案直到用户满意""先规划再执行再检查，循环到完成""根据中间结果决定下一步去哪"。

这节课解决的就是这种"动态编排"。学完你能掌握：

1. `LoopAgent` 怎么用，它和 `LlmAgent` 内部自带的推理循环有什么区别；
2. 怎么用循环 + 条件实现"多轮打磨 / 自我校验"逻辑；
3. 动态路由（Dynamic Routing）怎么让 Agent 根据中间结果选择下一步。

这是 ADK 编排能力真正拉开差距的地方，也是面试里"多 Agent 协作"高阶题的考点。

---

## 一、先分清两种"循环"

很多同学混淆两个概念，我先帮你厘清：**模型自带的推理循环** 和 **框架层的 LoopAgent**。

```text
┌────────────────────────────────────────────────────────────────┐
│ 概念 A：LlmAgent 内部推理循环（自动的）                          │
│   模型 → 调工具 → 回填 → 再想 → 结束                            │
│   你控制不了"循环几次"，模型自己决定停。                         │
├────────────────────────────────────────────────────────────────┤
│ 概念 B：LoopAgent（框架编排层，显式控制）                        │
│   子 Agent 整体跑一遍 → 检查条件 → 满足就停 / 否则再来一轮       │
│   你控制"最多几轮""什么算完成"，是更高一层的循环。               │
└────────────────────────────────────────────────────────────────┘
```

一句话：**A 是模型"想几步"，B 是框架"跑几遍"。** 一个在 Agent 内部，一个在 Agent 之间。这节课重点在 B。

<callout emoji="💡"><p>为什么需要 B？因为你经常要"整体迭代"：比如让 Agent 写代码→跑测试→失败就改→再跑，这种"写-测-改"的循环是单个 LlmAgent 内部循环表达不出来的，必须用 LoopAgent 把一个子流程反复执行。</p></callout>

---

## 二、LoopAgent 基础：最多 N 轮的循环

`LoopAgent` 的核心是 `maxIterations`——最多跑几轮，以及子 Agent 自己判断是否"已完成"（通过产出特定结束信号）。

### 2.1 一个"反复打磨文案"的 LoopAgent

场景：营销文案要反复改，直到"通过质检 Agent 检查"。

```java
package com.camp.adk;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.loop.LoopAgent;

import java.util.List;

/**
 * 循环 Agent：写文案 → 质检 → 不通过就重写，最多 5 轮。
 */
public class LoopCopywriter {

    // 写手：生成/改写文案
    private LlmAgent writer() {
        return LlmAgent.builder()
                .name("writer")
                .instruction("""
                        你是一名资深文案。根据主题写一段推广文案。
                        如果上一轮质检给过修改意见，请基于意见改写。
                        最后一行写 [DONE] 表示你已完成本轮。
                        """)
                .model("gemini-2.0-flash")
                .build();
    }

    // 质检：检查文案是否达标
    private LlmAgent reviewer() {
        return LlmAgent.builder()
                .name("reviewer")
                .instruction("""
                        你是严格的质量审核。检查文案是否：
                        1. 无夸大违规词；2. 突出卖点；3. 字数<100。
                        达标则输出 [APPROVED]，否则输出具体修改意见。
                        """)
                .model("gemini-2.0-flash")
                .build();
    }

    public LoopAgent build() {
        return LoopAgent.builder()
                .name("copy_loop")
                .maxIterations(5)            // 最多 5 轮，防止死循环
                .subAgents(List.of(writer(), reviewer()))
                .build();
    }
}
```

执行流：

```text
轮次1: [writer] 写初稿 ─▶ [reviewer] 给意见（未 APPROVED）
轮次2: [writer] 改稿   ─▶ [reviewer] 仍不合格
轮次3: [writer] 再改   ─▶ [reviewer] [APPROVED] → 循环结束
（若 5 轮都没 APPROVED，框架强制停止，避免烧钱死循环）
```

<callout emoji="⚠️"><p>生产铁律：LoopAgent 必须设 `maxIterations`，绝对不能写"无限循环直到满意"。模型可能永远不满意，那是在拿 API 账单开玩笑。5~10 轮是常见上限，超过就降级交人工。</p></callout>

### 2.2 结束信号的实现方式

ADK 判断"这一轮是否该停"，常见两种方式：

1. **子 Agent 输出约定标记**（如 `[APPROVED]` / `[DONE]`），Flow 解析文本判断；
2. **通过 session state 写状态位**，LoopAgent 读状态决定是否继续。

推荐第二种，更可靠、可程序化：

```java
package com.camp.adk;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.loop.LoopAgent;

import java.util.List;

/**
 * 用 session state 作为结束信号，比文本标记更稳。
 */
public class StateDrivenLoop {

    private LlmAgent worker() {
        return LlmAgent.builder()
                .name("worker")
                .instruction("""
                        处理任务。如果认为已完成，调用 mark_done 工具；
                        否则继续处理并在结尾说明还差什么。
                        """)
                .model("gemini-2.0-flash")
                .build();
    }

    public LoopAgent build() {
        return LoopAgent.builder()
                .name("state_loop")
                .maxIterations(8)
                // LoopAgent 会读取 session state 中的完成标记位决定是否继续
                .shouldContinue(state -> !"true".equals(state.get("task_done")))
                .subAgents(List.of(worker()))
                .build();
    }
}
```

<callout emoji="💡"><p>为什么状态位比文本标记稳？因为模型偶尔会忘记写 `[APPROVED]`，但工具调用 `mark_done` 是结构化动作，要么调了要么没调，没有"写了但写错"的模糊地带。企业系统里，凡是涉及"是否完成"的判断，一律走结构化状态，别信自然语言。</p></callout>

---

## 三、动态编排：根据中间结果选下一步

静态编排（顺序/并行/循环）的下一步是写死的。动态编排的下一步由"运行时数据"决定。ADK 里靠 `LlmAgent` 的委派 + 条件判断实现，也可以用自定义的 `BaseAgent` 子类写硬逻辑路由。

### 3.1 用 LLM 做软路由（意图分类）

最简单也最常用的动态路由：让一个"路由器 Agent"读用户输入，决定下一步去哪个专家。

```java
package com.camp.adk;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.BaseAgent;

import java.util.List;

/**
 * 动态路由：路由器根据意图，把任务转给不同下游。
 * 这里路由逻辑由 LLM 在 instruction 里决定委派谁，属"软路由"。
 */
public class DynamicRouter {

    private LlmAgent router() {
        return LlmAgent.builder()
                .name("router")
                .instruction("""
                        你是路由中心。判断用户意图并 ONLY 委派给一个子 Agent：
                        - 技术问题 → tech_agent
                        - 商务问题 → sales_agent
                        - 投诉     → complaint_agent
                        如果不确定，反问用户澄清。
                        """)
                .model("gemini-2.0-flash")
                .subAgents(List.of(techAgent(), salesAgent(), complaintAgent()))
                .build();
    }

    private LlmAgent techAgent() {
        return LlmAgent.builder().name("tech_agent")
                .instruction("你是技术专家，只回答技术实现问题。").model("gemini-2.0-flash").build();
    }
    private LlmAgent salesAgent() {
        return LlmAgent.builder().name("sales_agent")
                .instruction("你是商务顾问，只回答报价与合作问题。").model("gemini-2.0-flash").build();
    }
    private LlmAgent complaintAgent() {
        return LlmAgent.builder().name("complaint_agent")
                .instruction("你是客诉专员，先共情再给解决方案。").model("gemini-2.0-flash").build();
    }

    public LlmAgent build() { return router(); }
}
```

```text
用户输入
   │
   ▼
[router] ──意图=技术──▶ [tech_agent]
        ──意图=商务──▶ [sales_agent]
        ──意图=投诉──▶ [complaint_agent]
        ──不确定─────▶ 反问用户
```

### 3.2 用代码做硬路由（BaseAgent 子类）

当路由规则是确定性的（比如"金额>10万必须转人工审核"），用 LLM 软路由不稳，应该写代码硬路由。ADK 允许你继承 `BaseAgent` 实现自定义编排逻辑：

```java
package com.camp.adk;

import com.google.adk.agents.BaseAgent;
import com.google.adk.events.Event;
import com.google.adk.flows.agent.AgentFlow;
import com.google.adk.runner.AgentInvocation;
import com.google.genai.types.Content;

import java.util.Optional;

/**
 * 硬路由示例：根据 session state 里的金额，决定走自动还是人工。
 * 继承 BaseAgent，自己实现 runAsync 的编排逻辑。
 */
public class AmountRouterAgent extends BaseAgent {

    private final BaseAgent autoAgent;
    private final BaseAgent humanAgent;

    public AmountRouterAgent(String name, BaseAgent autoAgent, BaseAgent humanAgent) {
        super(name);
        this.autoAgent = autoAgent;
        this.humanAgent = humanAgent;
    }

    @Override
    public AgentFlow runAsync(AgentInvocation invocation) {
        // 读取上下文里的金额
        String amountStr = invocation.context().session().state().get("amount");
        double amount = Optional.ofNullable(amountStr).map(Double::parseDouble).orElse(0.0);

        // 确定性路由：>=10万走人工，否则自动
        BaseAgent target = amount >= 100000 ? humanAgent : autoAgent;
        return target.runAsync(invocation);
    }
}
```

<callout emoji="⚠️"><p>什么时候用硬路由、什么时候用软路由？铁律：**涉及合规、金额、权限的判断，一律硬路由（代码），不交给 LLM 拍板。** LLM 会"好心办坏事"——它可能因为用户撒娇就绕过审核。金额、权限这类，必须代码卡死。</p></callout>

---

## 四、组合编排：Loop + 路由 + 并行

真实系统往往是组合的。举个例子："代码生成 Agent"——并行写多个模块、循环自测、路由到人工 review。

```java
package com.camp.adk;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.loop.LoopAgent;
import com.google.adk.agents.parallel.ParallelAgent;
import com.google.adk.agents.sequential.SequentialAgent;

import java.util.List;

/**
 * 组合编排样板：
 * 1) 并行生成多个模块代码
 * 2) 循环自测-修复（最多 5 轮）
 * 3) 若仍失败，路由到人工
 */
public class CodeGenOrchestration {

    public SequentialAgent build() {
        // 阶段1：并行写模块
        ParallelAgent gen = ParallelAgent.builder()
                .name("gen_modules")
                .subAgents(List.of(moduleAgent("auth"), moduleAgent("payment"), moduleAgent("log")))
                .build();

        // 阶段2：循环自测修复
        LoopAgent selfTest = LoopAgent.builder()
                .name("test_fix_loop")
                .maxIterations(5)
                .subAgents(List.of(tester(), fixer()))
                .build();

        return SequentialAgent.builder()
                .name("codegen_pipeline")
                .subAgents(List.of(gen, selfTest))
                .build();
    }

    private LlmAgent moduleAgent(String name) {
        return LlmAgent.builder().name("mod_" + name)
                .instruction("生成 " + name + " 模块代码，含单元测试。").model("gemini-2.5-pro").build();
    }
    private LlmAgent tester() {
        return LlmAgent.builder().name("tester")
                .instruction("运行测试，输出通过/失败列表。全过则 [PASS]。").model("gemini-2.0-flash").build();
    }
    private LlmAgent fixer() {
        return LlmAgent.builder().name("fixer")
                .instruction("根据失败列表修复代码。").model("gemini-2.5-pro").build();
    }
}
```

```text
[并行生成 3 模块] ─▶ [循环: 测试→修复] ─▶ 产物
                          ↑__________│
                          （最多5轮，全过则退出）
```

<callout emoji="✅"><p>这就是业界常说的"Agentic Coding"骨架：生成-测试-修复闭环。Cursor / Devin 类工具的内核就是这种 Loop。理解了这个结构，你就能自己搭一个"迷你 Devin"。这能力在 JD 里对应"具备 Agent 自主任务执行 / 闭环迭代经验"。</p></callout>

---

## 五、动态编排的事件流与状态机视角

把动态编排抽象成状态机，更容易设计。每个 Agent 是一个状态，路由是迁移条件：

```text
        ┌─────────┐
   start│ router  │──意图A──▶ [A_agent] ─┐
        └─────────┘                      │
              │--意图B──▶ [B_agent] ─────┤
              │--意图C──▶ [C_agent] ─────┤
              │                          ▼
              └────不确定──▶ 反问 ─▶ 回到 router
                                      
        循环状态（LoopAgent 内）：
        [worker] ⇄ [checker]  （条件不满足就回 worker）
```

用状态机思维的好处：**你能画出完整的状态迁移图，提前发现"死状态"（没人能到达）和"死循环"（没有出口）**。企业级编排系统设计时，我建议先画状态机再写代码。

<callout emoji="💡"><p>为什么建议状态机视角？因为 LLM 编排最怕两件事：一是"卡住"（模型不委派也不结束），二是"乱跳"（在两个 Agent 间无限往返）。画清楚状态迁移 + 设好 maxIterations + 写好终止条件，这两类 bug 能灭掉 90%。</p></callout>

---

## 六、Human-in-the-Loop：动态编排里的"人工节点"

动态编排经常要在某个节点停下来问人。ADK 通过"中断事件"实现 Human-in-the-Loop（HITL）。

```java
package com.camp.adk;

import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;

import io.reactivex.rxjava3.core.Flowable;
import java.util.Optional;

/**
 * HITL 示意：当 Agent 触发"需要人工确认"时，暂停并等待人类输入。
 */
public class HitlDemo {

    public void runWithHuman(Runner runner, String sessionId, String userId,
                             com.google.genai.types.Content msg) {
        Flowable<Event> events = runner.runAsync(sessionId, userId, msg);
        events.blockingForEach(event -> {
            if (event.requiresHumanInput()) {
                // 框架发出"等我一下"的信号
                System.out.println("⏸ 需要人工确认: " + event.humanPrompt());
                String humanAnswer = readFromConsole();  // 阻塞等人
                // 把人的回答作为新消息继续推进
                runner.runAsync(sessionId, userId,
                        com.google.genai.types.Content.fromParts(
                                com.google.genai.types.Part.fromText(humanAnswer)))
                        .blockingForEach(e -> printText(e));
            } else {
                printText(event);
            }
        });
    }

    private void printText(Event e) {
        Optional.ofNullable(e.textContent()).ifPresent(System.out::println);
    }
    private String readFromConsole() { /* 省略控制台读取 */ return ""; }
}
```

<callout emoji="⚠️"><p>HITL 不是"锦上添花"，而是企业 Agent 的"安全带"。涉及发邮件、退款、对外承诺的动作，必须有人点一下确认。我们第十二章"Agent 安全与治理"会把这个讲透——这里先记住：凡是"动作不可逆"，就要 HITL。</p></callout>

---

## 七、性能与成本：循环编排的账单陷阱

循环 Agent 最容易"悄悄烧钱"。几个控制手段：

1. **maxIterations 必设**，且评估合理上限；
2. **小模型干粗活，大模型干精细活**：循环里的"测试/质检"用 `gemini-2.0-flash`，只有"生成/修复"用 `gemini-2.5-pro`；
3. **缓存命中**：同一轮里重复上下文走提示词缓存（第十章、第十五章详讲）；
4. **提前退出**：一旦达标立刻停，不要跑满轮数。

```text
成本对比（示意）：
  无上限循环：可能 20 轮 × 长上下文 = 💸💸💸
  有上限+小模型：5 轮 × 小模型粗筛 = 💰
```

<callout emoji="💡"><p>成本意识是架构师和"调包侠"的分水岭。面试被问"你的 Agent 成本怎么控"，能从 maxIterations、模型分级、缓存、提前退出四个角度答，面试官立刻高看一眼。对应 JD 里的"成本治理 / Token 优化"要求。</p></callout>

---

## 八、招聘能力映射

这节课的硬核能力在 JD 里怎么体现：

- "**熟悉 ReAct / Plan-and-Execute / 闭环迭代 Agent**" → LoopAgent 就是闭环迭代的载体。
- "**理解 Human-in-the-Loop 与可控 Agent**" → 第六节 HITL 直接对应。
- "**多 Agent 动态编排经验**" → 软路由 + 硬路由 + 状态机设计。

<callout emoji="✅"><p>面试准备：被问"你怎么防止 Agent 无限循环"，标准答案是"三层防护——maxIterations 硬上限 + 结构化完成信号 + 提前退出"。再补一句"金额/权限类路由用代码硬卡，不交给 LLM"。这套回答几乎满分。</p></callout>

---

## 九、LoopAgent 实战：可运行的"生成-测试-修复"闭环

上节课的循环示例是骨架，这里给一个更完整、能体现"自测修复"的 LoopAgent 思路（省略模型具体调用，聚焦编排结构）。

```java
package com.camp.adk;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.loop.LoopAgent;
import com.google.adk.tools.Annotations.AdkTool;
import com.google.adk.tools.FunctionTool;

import java.util.List;
import java.util.Map;

/**
 * 代码自测修复闭环：
 *  writer 生成/修复代码 → tester 跑测试 → 通过则结束
 */
public class CodeFixLoop {

    // 真实场景：tester 调用真实编译/测试命令，返回通过/失败
    @AdkTool("运行单元测试，返回 PASS 或 FAIL 及错误摘要")
    public Map<String, Object> runTests() {
        // demo：随机模拟一次测试结果
        boolean pass = Math.random() > 0.5;
        return Map.of("result", pass ? "PASS" : "FAIL",
                "detail", pass ? "" : "NullPointer at line 12");
    }

    private LlmAgent writer() {
        return LlmAgent.builder()
                .name("writer")
                .instruction("生成或修复代码。若测试 FAIL，根据错误摘要修改后重试。")
                .model("gemini-2.5-pro")
                .build();
    }

    private LlmAgent tester() {
        return LlmAgent.builder()
                .name("tester")
                .instruction("调用 runTests 检查。PASS 则输出 [DONE]，FAIL 则说明错误。")
                .model("gemini-2.0-flash")
                .tools(new FunctionTool(this::runTests))
                .build();
    }

    public LoopAgent build() {
        return LoopAgent.builder()
                .name("code_fix_loop")
                .maxIterations(5)
                .shouldContinue(state -> !"true".equals(state.get("done")))
                .subAgents(List.of(writer(), tester()))
                .build();
    }
}
```

<callout emoji="💡"><p>注意模型分级：生成/修复用 2.5 Pro（贵但强），测试判断用 2.0 Flash（便宜）。循环里每轮都烧钱，粗活必须用小模型。这是成本治理在编排层的具体体现。</p></callout>

## 十、编排可视化与调试技巧

复杂编排最容易"跑偏"。给你一套调试 checklist：

```text
调试复杂编排的步骤：
  1) 画状态机：列出所有 Agent 状态 + 迁移条件
  2) 单 Agent 先测：每个子 Agent 单独跑通
  3) 加日志：每个 Agent 进入/退出都打点
  4) 查委派：确认 router 真的发生了委派
  5) 查终止：确认 Loop 有出口、maxIterations 生效
  6) 查状态：session state 的字段在预期变化
```

```java
package com.camp.adk;

public class OrchestrationDebug {
    // 简单的进入/退出打点
    public static void trace(String agent, String action) {
        System.out.println("[trace] " + agent + " -> " + action
                + " @ " + System.currentTimeMillis());
    }
    // 用法：在自定义 BaseAgent 的 runAsync 里，进入时 trace(name,"enter")，退出时 trace(name,"exit")
}
```

<callout emoji="✅"><p>调试口诀："先画状态机、再单测、后联调"。90% 的编排 bug 在画状态机时就暴露了——要么有死状态（无人可达），要么有死循环（无出口）。</p></callout>

## 十一、与 Spring AI Alibaba Graph 的对比

国内同学常问：ADK 的编排和 Spring AI Alibaba 的 Graph 有什么区别？给一张对照表。

```text
┌──────────────────┬──────────────────────┬────────────────────────┐
│ 维度             │ ADK (Google)        │ Spring AI Alibaba Graph│
├──────────────────┼──────────────────────┼────────────────────────┤
│ 编排原语         │ Sequential/Parallel/ │ State/Edge/Node 图状    │
│                  │ Loop/Agent          │ 更灵活                   │
│ 云绑定           │ Vertex AI           │ 阿里云 DashScope/Nacos   │
│ 适用生态         │ GCP / Gemini        │ 阿里云 / Spring 体系     │
│ 学习曲线         │ 中等                │ 略陡（图概念多）         │
└──────────────────┴──────────────────────┴────────────────────────┘
```

<callout emoji="⚠️"><p>选型判断：在阿里云体系、用 Spring 全家桶 → Graph 更顺；在 GCP、要深度 Vertex 集成 → ADK 更顺。两者编排思想相通（都是"把控制流声明出来"），学一个就能迁移另一个。</p></callout>

## 十二、避坑清单

```text
动态编排避坑：
✅ 任何 Loop 必设 maxIterations
✅ 终止用结构化状态位，而非文本标记
✅ 合规/金额/权限用硬路由（代码），不交 LLM
✅ HITL 用于不可逆动作（退款/发信/删数据）
✅ 循环内模型分级，省成本
❌ 让 LLM 软路由决定"要不要走审核"（必翻车）
❌ 无限循环直到"满意"
❌ 并行子任务其实有依赖却硬并行（数据错乱）
```

<callout emoji="💡"><p>面试加分：被问"动态编排怎么保证可靠"，答"三层防护——maxIterations 硬上限 + 结构化终止信号 + 合规类硬路由；不可逆动作加 HITL"。这回答直接对标架构师层级。</p></callout>

## 十三、编排模式的性能与成本 Tradeoff

不同编排原语的成本差异巨大，选型时要算账。

```text
编排原语 Tradeoff 表：
┌──────────────┬──────────┬──────────┬──────────────────────────┐
│ 原语         │ 模型调用 │ 延迟     │ 适用                     │
├──────────────┼──────────┼──────────┼──────────────────────────┤
│ 基础 Agent   │ 1 次     │ 低       │ 单职责                   │
│ 子 Agent     │ 1+N 跳   │ 中       │ 意图分发                 │
│ 并行 Agent   │ N 并发   │ 取最慢   │ 无依赖多任务             │
│ LoopAgent    │ N×轮     │ 高       │ 迭代打磨/自测修复        │
│ 硬路由       │ 1 次     │ 极低     │ 合规/金额确定性判断      │
└──────────────┴──────────┴──────────┴──────────────────────────┘
```

<callout emoji="⚠️"><p>选型纪律：能用硬路由（代码）解决的，绝不交 LLM 软路由（省一次模型调用、还更可靠）；能用并行省延迟的，别串行；Loop 轮数必须设上限。每一处都在成本和可靠性上较劲。</p></callout>

## 十四、真实案例：客服多轮处理编排

把这一章的能力串成一个真实客服编排：

```text
用户："我的订单迟迟没发货，我要投诉"
   │
   ▼ [硬路由] 金额/订单类 → 不走 LLM 判断
[Root Agent] 意图=投诉 → 委派 complaint_agent
   │
   ▼ [complaint_agent] 调用查单工具 → 发现"已发货但物流停滞"
   │
   ▼ [LoopAgent] 循环：安抚生成 → 自检话术合规 → 不合规重写
   │   （maxIterations=3，合规检查用规则硬卡）
   │
   ▼ [HITL] 涉及"赔偿" → 暂停等人工确认
   │
   ▼ 人工确认 → 继续 → 给出方案 + 建工单
```

```java
// 关键代码骨架（组合 lesson02/03 的组件）
// Root → subAgents(complaint_agent)
// complaint_agent → 查单工具 + LoopAgent(安抚生成, 合规自检)
// HITL 在"赔偿"分支暂停
```

<callout emoji="💡"><p>这个案例把"硬路由 + 子 Agent + Loop + HITL"全用上了，正是企业客服 Agent 的真实形态。你能独立写出这个编排，就证明动态编排真的学会了。</p></callout>

## 小结

这节课讲透了 ADK 的动态编排：

1. **两种循环要分清**：LlmAgent 内部推理循环（模型自决）vs LoopAgent（框架显式控制轮次）。
2. **LoopAgent 用法**：`maxIterations` 设上限，结束信号优先用 session state 而非文本标记，防止死循环烧钱。
3. **动态路由两种**：软路由（LLM 委派，适合意图分类）和硬路由（继承 BaseAgent 写代码，适合合规/金额/权限等确定性判断）。
4. **组合编排**：并行生成 + 循环自测 + 路由人工，是 Agentic Coding 的骨架。
5. **状态机视角**：先画迁移图，消灭死状态和死循环；HITL 是"动作不可逆"场景的安全带。
6. **成本防护**：上限 + 模型分级 + 缓存 + 提前退出。

记住一句话：**动态编排的本质，是把"下一步去哪"从"写死在代码里"变成"由运行时数据决定"——而安全的可控性，靠的是硬上限和硬路由兜底。**

---

## 延伸阅读 / 引用文档

1. ADK LoopAgent 文档：<https://google.github.io/adk-docs/agents/loop-agents/>
2. ADK 工作流编排（Workflow Agents）：<https://google.github.io/adk-docs/agents/workflow-agents/>
3. Anthropic：Building Effective Agents（编排模式）：<https://www.anthropic.com/research/building-effective-agents>
4. ReAct 论文（推理+行动）：<https://arxiv.org/abs/2210.03629>
5. Plan-and-Solve / Plan-and-Execute 范式：<https://arxiv.org/abs/2305.04091>
6. Human-in-the-Loop 设计模式：<https://arxiv.org/abs/2406.12607>

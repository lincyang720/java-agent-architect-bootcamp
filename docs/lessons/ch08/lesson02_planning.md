# 第八章第二节：规划能力（Planning）：任务分解（Chain of Thought）、策略生成

大家好，我是秋天。

上节课我们说 Agent 有五大认知支柱，第一根要深挖的就是**规划（Planning）**。很多同学以为"让模型直接干就完了"，结果模型一上手就懵——目标太大、路径太模糊，它不知道从哪下嘴。规划就是给 Agent 装一个"先想清楚再动手"的能力。

学完这节课，你能掌握：

1. **Chain of Thought（CoT）为什么有效**，以及 Zero-shot / Few-shot CoT 在 Java 里怎么落地。
2. **任务分解（Task Decomposition）**：把"写一份行业研究报告"这种大目标，拆成可执行、可验证的子任务。
3. **策略生成（Plan-and-Execute）**：先生成完整计划再执行，对比 ReAct 的优劣与适用场景。

<callout emoji="💡"><p>招聘映射：JD 高频"理解 Planning / Plan-and-Execute / 任务分解"。面试官常让你对比 ReAct 和 Plan-and-Execute 的取舍——这节课给你能脱口而出的框架。</p></callout>

---

## 一、为什么 Agent 必须会规划

```
无规划：
  目标: "分析竞品并出一份PPT"
  模型: "好的，竞品分析PPT..."（直接瞎编，缺数据、结构乱）

有规划：
  目标: "分析竞品并出一份PPT"
  规划: 1.明确竞品清单 2.收集各家功能/定价 3.对比矩阵
        4.提炼差异化结论 5.生成PPT大纲 6.填充内容
  执行: 逐步调用工具补齐信息，按结构产出
```

规划解决的是**"目标→路径"的映射问题**。没有它，模型在面对多步骤、需外部信息的任务时会直接崩。

---

## 二、Chain of Thought（CoT）：最朴素的规划

### 2.1 原理

CoT 的核心是**让模型"把思考过程写出来"**，而不是一步到位给答案。研究发现，强迫模型"一步步想"，复杂推理准确率大幅提升。本质是：把"系统2慢思考"逼出来。

两种形态：

- **Few-shot CoT**：在 prompt 里给几个"问题→推理步骤→答案"的范例。
- **Zero-shot CoT**：只加一句"Let's think step by step"（中文"请一步步思考"）。

### 2.2 Java 落地：Zero-shot CoT

```java
// ch08-cognition/src/main/java/demo/planning/CotDemo.java
package demo.planning;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

public class CotDemo {
    public static void main(String[] args) {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o").build();

        String question = "一个仓库有1200件货，每天发出去15%，第几天会低于100件？";
        // Zero-shot CoT：强制一步步思考
        String prompt = question + "\n请一步步思考，再给出最终答案。";
        String ans = model.generate(prompt).content();
        System.out.println(ans);
    }
}
```

### 2.3 Few-shot CoT 模板（结构化推理）

```java
// ch08-cognition/src/main/java/demo/planning/FewShotCot.java
package demo.planning;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

public class FewShotCot {
    static final String TEMPLATE = """
        你是一个严谨的规划助手。面对复杂任务，先输出思考步骤，再输出结果。

        示例：
        任务: 安排一次3天北京出差
        思考:
          1. 确定出行日期与预算
          2. 预订往返交通
          3. 预订酒店(近会场)
          4. 排每日议程
          5. 准备物料与提醒
        结果: 出差计划已生成。

        现在处理：
        任务: %s
        思考:
        """;

    public static void main(String[] args) {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o").build();
        String out = model.generate(String.format(TEMPLATE, "为新产品做竞品分析")).content();
        System.out.println(out);
    }
}
```

<callout emoji="⚠️"><p>CoT 的坑：强制"一步步想"会增加 token 和延迟，对简单任务纯属浪费。判断标准：任务是否需要多步推理？不需要（如"把这句话翻译成英文"）就别上 CoT。工程上是"按任务复杂度动态决定是否走 CoT"，而不是无脑加。</p></callout>

---

## 三、任务分解（Task Decomposition）

### 3.1 两种分解粒度

```
纵向分解（步骤序列）：
  大目标 → [步骤1] → [步骤2] → [步骤3] → 完成
  （适合线性流程，如"生成报告"）

树状分解（子目标树）：
  大目标
   ├ 子目标A → [任务A1][任务A2]
   ├ 子目标B → [任务B1][任务B2][任务B3]
   └ 子目标C
  （适合可并行的复杂任务，如"搭建一个系统"）
```

### 3.2 用 LLM 做任务分解

```java
// ch08-cognition/src/main/java/demo/planning/TaskDecomposer.java
package demo.planning;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;

import java.util.List;

public class TaskDecomposer {

    interface Planner {
        @UserMessage("""
            请把下面的目标分解为 3~7 个有序、可独立执行、可验证的子任务。
            每个子任务说明：要做什么、需要什么工具/信息、完成标准。
            只返回 JSON 数组，不要解释。
            目标：{{goal}}
            """)
        String decompose(String goal);
    }

    public static void main(String[] args) {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o").build();
        Planner planner = AiServices.create(Planner.class, model);

        String plan = planner.decompose("为新产品做一份竞品分析报告");
        System.out.println(plan);
        // [{"task":"明确竞品清单","need":"搜索引擎","done":"清单≥3家"},
        //  {"task":"收集功能对比","need":"网页抓取","done":"对比矩阵"}...]
    }
}
```

### 3.3 把计划变成可执行步骤（Plan 数据结构）

```java
// ch08-cognition/src/main/java/demo/planning/Plan.java
package demo.planning;

import java.util.List;

public record PlanStep(
        String id,
        String description,
        String requiredTool,     // 该步骤需要的工具
        String doneCriterion    // 完成判据
) {}

public record Plan(
        String goal,
        List<PlanStep> steps
) {
    public PlanStep next(int index) {
        return index < steps.size() ? steps.get(index) : null;
    }
}
```

<callout emoji="💡"><p>工程要点：分解出的每个子任务都要带"完成判据（done criterion）"。不然 Agent 永远不知道这一步算不算做完，容易陷入"以为完成其实没完成"的假循环。判据最好是可机器验证的（如"返回了≥3条记录""文件已生成"），而不是模糊的"分析完成"。</p></callout>

---

## 四、策略生成：Plan-and-Execute 模式

### 4.1 和 ReAct 的区别

```
ReAct（边想边做）：
  Thought → Action → Observation → Thought → Action → ...
  优点：灵活，遇错即时调整
  缺点：计划随走随变，可能"走岔了才发现"，长任务易迷失

Plan-and-Execute（先想后做）：
  Plan（一次性生成完整计划） → 逐步 Execute（每步可重规划）
  优点：全局有序，长任务不乱
  缺点：初始计划可能不准，需在执行中局部修正
```

### 4.2 Plan-and-Execute 的 Java 编排

```java
// ch08-cognition/src/main/java/demo/planning/PlanAndExecute.java
package demo.planning;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.util.List;

public class PlanAndExecute {

    /** 阶段一：生成完整计划 */
    static Plan makePlan(ChatLanguageModel model, String goal) {
        String json = model.generate(
            "为以下目标生成有序子任务计划(JSON数组): " + goal).content();
        // 解析为 Plan（生产用 Jackson）
        System.out.println("生成计划: " + json);
        return new Plan(goal, List.of());
    }

    /** 阶段二：逐步骤执行，每步后可局部重规划 */
    static void execute(ChatLanguageModel model, Plan plan) {
        for (int i = 0; i < plan.steps().size(); i++) {
            PlanStep step = plan.steps().get(i);
            String result = model.generate(
                "执行步骤: " + step.description() + " 需要的工具:" + step.requiredTool()).content();
            System.out.println("步骤" + (i+1) + "完成: " + result);

            // 局部重规划：若发现计划偏离，重新生成后续步骤
            if (result.contains("PLAN_INVALID")) {
                System.out.println("检测到计划失效，重规划后续步骤...");
                // replan(plan, i);
            }
        }
    }

    public static void main(String[] args) {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY")).modelName("gpt-4o").build();
        Plan plan = makePlan(model, "为新产品做竞品分析报告");
        execute(model, plan);
    }
}
```

### 4.3 选型决策表

| 任务特征 | 推荐模式 | 理由 |
|----------|----------|------|
| 步骤少、路径清晰 | ReAct | 轻量、快 |
| 长任务、多步骤、需全局有序 | Plan-and-Execute | 不乱、可重规划 |
| 高不确定、常需试错 | ReAct | 灵活调整 |
| 可并行子任务 | 树状分解 + 并行执行 | 省时 |

<callout emoji="✅"><p>企业落地真实做法：**Plan-and-Execute 为主，ReAct 为辅**。先生成计划保证大方向不乱，执行中允许局部重规划保证灵活性。纯 ReAct 在长任务里容易"走丢"，纯 Plan-and-Execute 在初始计划错误时又太死。两者结合的"有计划的执行"是生产首选。</p></callout>

---

## 五、企业落地：客服 Agent 的工单处理规划

某银行工单 Agent 接到"信用卡被盗刷，要求止付并索赔"：

```
规划（Plan-and-Execute）:
  步骤1: 身份核验 (工具: 实名认证API)        判据: 核验通过
  步骤2: 冻结卡片 (工具: 核心 banking API)   判据: 状态=已冻结
  步骤3: 调取交易流水 (工具: 交易查询)        判据: 返回近30天记录
  步骤4: 识别盗刷交易 (模型判断)             判据: 标记可疑交易
  步骤5: 发起索赔工单 (工具: 工单系统)        判据: 工单号已生成
  步骤6: 短信通知用户 (工具: 短信网关)        判据: 发送成功
```

**为什么这样设计？** 金融操作不可逆（冻结、索赔），必须先规划保证顺序正确、每步有判据，绝不能让模型"自由发挥"。这正是规划能力在高风险场景的价值——把"正确性"前置到计划阶段。

---

## 六、踩坑清单

| 坑 | 后果 | 解法 |
|----|------|------|
| 简单任务硬上 CoT | 慢且贵 | 按复杂度动态决定 |
| 子任务无完成判据 | 假完成死循环 | 每步带可验证判据 |
| 纯 ReAct 跑长任务 | 走丢、迷失 | 改 Plan-and-Execute |
| 计划错误不修正 | 一路错到底 | 执行中局部重规划 |
| 规划与执行脱节 | 计划成摆设 | 执行严格对照计划 |

---

## 七、并行规划：树状分解 + 并发执行

当子任务之间没有依赖（如"收集三家竞品资料"），串行太慢。可以用线程池并发执行，用 `CompletableFuture` 聚合结果。

```java
// ch08-cognition/src/main/java/demo/planning/ParallelPlan.java
package demo.planning;

import dev.langchain4j.model.chat.ChatLanguageModel;
import java.util.*;
import java.util.concurrent.*;

public class ParallelPlan {
    /** 并发执行无依赖的子任务，统一聚合 */
    public static List<String> runParallel(ChatLanguageModel model,
                                           List<PlanStep> steps) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(steps.size(), 8));
        List<CompletableFuture<String>> futures = steps.stream()
            .map(s -> CompletableFuture.supplyAsync(() ->
                model.generate("执行:" + s.description()).content(), pool))
            .toList();
        List<String> results = new ArrayList<>();
        for (CompletableFuture<String> f : futures) results.add(f.get(30, TimeUnit.SECONDS));
        pool.shutdown();
        return results;
    }
}
```

---

## 八、规划的可观测性：把"想了什么"留痕

生产里规划的每一步都必须可回溯，否则 badcase 无法定位。用 `PlanStep` 带状态字段 + 日志：

```java
// ch08-cognition/src/main/java/demo/planning/PlanTrace.java
package demo.planning;

public class PlanTrace {
    enum Status { PENDING, RUNNING, DONE, FAILED, REPLANNED }

    public record TraceStep(String id, String desc, Status status, String output) {}

    public static void log(List<TraceStep> trace) {
        trace.forEach(t -> System.out.printf(
            "[%s] %s -> %s%n", t.status(), t.id(), t.desc()));
    }
}
```

```yaml
# 规划模块配置示例
planning:
  mode: plan-and-execute
  max-replan: 2
  step-timeout-seconds: 30
  parallel-threshold: 3      # 子任务数>=3 且无依赖时并行
  trace-enabled: true        # 全量留痕
```

---

## 九、面试追问与常见误区

| 追问 | 回答要点 |
|------|----------|
| CoT 一定有效吗？ | 仅复杂推理有效，简单任务纯浪费 |
| ReAct vs Plan-execute？ | 短/灵活用 ReAct，长/有序用 P&E |
| 子任务怎么定完成判据？ | 可机器验证优先（返回值/文件存在） |
| 计划错了怎么办？ | 执行中局部重规划，限 max-replan |

<callout emoji="⚠️"><p>误区提醒：很多人把"规划"理解成"让模型列个大纲就完事"。真正的规划要有完成判据、要可重规划、要可观测。没有这三样的"规划"只是装饰，Agent 遇到意外照样崩。</p></callout>

## 十、端到端规划示例：智能客服工单 Agent

把规划能力落到一个完整可运行骨架：

```java
// ch08-cognition/src/main/java/demo/planning/TicketAgent.java
package demo.planning;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

public class TicketAgent {
    public static void main(String[] args) {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY")).modelName("gpt-4o").build();
        // 1. 规划：生成步骤（复用 TaskDecomposer）
        Plan plan = makePlan(model, "用户投诉退款未到账且态度激动");
        // 2. 执行：带完成判据逐步跑（复用 PlanAndExecute）
        execute(model, plan);
        // 3. 反思：若某步失败触发重规划（见 lesson04）
    }
    // makePlan / execute 见前文第三节、第四节
    static Plan makePlan(ChatLanguageModel m, String g) { return new Plan(g, java.util.List.of()); }
    static void execute(ChatLanguageModel m, Plan p) {}
}
```

```
工单规划执行流：
  目标 → 规划:
    1.[安抚情绪] 工具:none 判据:回复含共情表述
    2.[核实订单] 工具:queryOrder 判据:返回订单状态
    3.[查退款流水] 工具:queryRefund 判据:返回流水
    4.[定位原因] 模型推理 判据:给出归因
    5.[给出方案] 工具:createTicket 判据:工单号生成
  → 逐步执行 + 每步校验判据
```

---

## 十一、规划能力的评测（接第十六章）

规划好不好，要能量化。给你几个可测指标：

| 指标 | 定义 | 达标参考 |
|------|------|----------|
| 计划完整率 | 关键步骤无遗漏比例 | ≥95% |
| 步骤可执行率 | 每步有对应工具/动作 | ≥90% |
| 重规划准确率 | 计划失效时正确重规划 | ≥85% |
| 端到端达成率 | 任务最终完成比例 | 场景相关 |

<callout emoji="💡"><p>规划质量是 Agent 的"地基质量"。第十六章的评测闭环会要求你对每个认知支柱建评测集。规划支柱的评测集就是一批"目标→标准步骤"的标注对，自动比对模型生成的计划覆盖度。</p></callout>

## 十二、规划能力的反模式

```
反模式1：无规划直接执行
  现象：把大目标丢给模型"去办"
  后果：步骤混乱、漏关键动作
  正解：先 Plan-and-Execute

反模式2：子任务无完成判据
  现象：步骤写"分析一下"
  后果：Agent 以为完成其实没完成，假循环
  正解：每步带可机器验证判据

反模式3：计划错误不修正
  现象：初始计划错就一路错
  后果：白忙活
  正解：执行中局部重规划(限 max-replan)

反模式4：长任务纯 ReAct
  现象：几十步边想边做
  后果：走丢、迷失、超步数
  正解：长任务用 Plan-and-Execute
```

---

## 十三、规划与上下文工程的衔接（接第十五章）

规划产出的步骤本身就是"上下文"的一部分。第十五章会讲 Context Engineering 的 Write/Select/Compress/Isolate：

- **Write**：把计划写入上下文，让模型每步看到全局
- **Select**：只把"当前步相关"的子任务选入上下文
- **Compress**：历史步骤压缩为摘要，避免上下文爆炸
- **Isolate**：多子任务上下文隔离，防互相干扰

<callout emoji="💡"><p>规划不是孤立能力。它的产出（计划、步骤状态）必须妥善管理在上下文里，否则长任务后期模型"忘了前面规划啥"。这是规划与上下文工程的交叉点，学完第十五章你会更通透。</p></callout>

## 十四、Plan-and-Execute 完整参考实现

把第四节的骨架补全为可直接参考的结构：

```java
// ch08-cognition/src/main/java/demo/planning/PlanExecutor.java
package demo.planning;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.util.ArrayList;
import java.util.List;

public class PlanExecutor {
    private final ChatLanguageModel model;

    public PlanExecutor(ChatLanguageModel m) { this.model = m; }

    public Plan makePlan(String goal) {
        String json = model.generate(
            "为以下目标生成有序子任务JSON数组(含description/requiredTool/doneCriterion): "
            + goal).content();
        // 生产用 Jackson 解析为 Plan；此处返回占位
        return new Plan(goal, new ArrayList<>());
    }

    public void execute(Plan plan, int maxReplan) {
        int replan = 0;
        for (int i = 0; i < plan.steps().size(); i++) {
            PlanStep step = plan.steps().get(i);
            String r = model.generate("执行:" + step.description()).content();
            if (r.contains("PLAN_INVALID") && replan < maxReplan) {
                replan++;
                System.out.println("重规划后续步骤...");
                // reassignRemainingSteps(i);
            }
        }
    }
}
```

---

## 十五、规划能力的红蓝对抗测试

要验证规划稳不稳，做红蓝对抗：故意给模糊/矛盾目标，看 Agent 是否识破：

| 测试输入 | 期望行为 |
|----------|----------|
| "帮我做个不可能完成的任务" | 识别不可行并说明 |
| "先A再A的反操作" | 识别冗余/矛盾 |
| 缺关键信息的目标 | 主动追问澄清 |
| 超长多目标 | 正确分解为有序步骤 |

<callout emoji="✅"><p>规划能力的评测不能只测"能分解"，还要测"能识别不可行/矛盾/歧义"。红蓝对抗测试能暴露规划器的边界处理缺陷，是上线前的必做项。这呼应第十六章的评测闭环。</p></callout>

## 小结

这节课深挖了规划能力这根支柱：

- **CoT** 是最朴素的规划（强制一步步想），Zero-shot 加一句"请一步步思考"即可，但简单任务别滥用。
- **任务分解**把大目标拆成带"工具需求 + 完成判据"的子任务，判据可机器验证是防假完成的关键。
- **Plan-and-Execute** 先生成全局计划再执行，长任务不乱；ReAct 边想边做，灵活但易迷失。生产用"有计划的执行"取长补短。
- 高风险场景（金融止付）必须先规划保顺序正确，把正确性前置。

下一节讲工具使用（Tool Use）——规划出步骤后，Agent 怎么真正"动手"（lesson03）。

---

## 延伸阅读 / 引用文档

1. Wei et al., *Chain-of-Thought Prompting* (CoT): https://arxiv.org/abs/2201.11903
2. Kojima et al., *Zero-shot CoT* ("Let's think step by step"): https://arxiv.org/abs/2205.11916
3. Wang et al., *Plan-and-Solve Prompting*: https://arxiv.org/abs/2305.04091
4. Yao et al., *ReAct*: https://arxiv.org/abs/2210.03629
5. LangChain4j Tools 文档: https://docs.langchain4j.dev/tutorials/ai-services-tool-calling
6. Spring AI Alibaba Graph（规划编排）: https://github.com/alibaba/spring-ai-alibaba

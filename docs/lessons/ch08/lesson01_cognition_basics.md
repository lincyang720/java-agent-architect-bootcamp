# 第八章第一节：认知模型基础：自主性、规划、工具使用、反思、学习

大家好，我是秋天。

前面七章我们把"Agent 怎么用知识（RAG）"讲透了。但从第八章开始，我们要钻进 Agent 的"脑子"——它的认知架构。一个真正有用的 Agent 不是"一问一答的 chatbot"，而是具备自主性、会规划、能调用工具、会反思、能从经验里学习的系统。

这节课是第八章的总纲，先把**认知模型的五大支柱**讲清楚：自主性（Autonomy）、规划（Planning）、工具使用（Tool Use）、反思（Reflection）、学习（Learning）。后面六节每一节深挖一根支柱。

学完这节课，你能掌握：

1. **Agent 和人一样，认知由五大能力组成**，少一根都只是"高级填空题机器"。
2. **这五大能力在 Java 工程里分别对应什么模块**，以及它们怎么协同。
3. **为什么"认知架构"是 Agent 和"普通 LLM 应用"的本质区别**。

<callout emoji="💡"><p>招聘映射：JD 里"具备 Agent 认知架构设计能力""理解规划/反思/工具调用"。面试官常问"Agent 和 chatbot 的区别"——这节课的五大支柱就是你能脱口而出的框架。</p></callout>

---

## 一、先给 Agent 一个正经定义

```
普通 LLM 应用（Chatbot）：
   输入 → 模型 → 输出          （一次性，无状态，不动手）

Agent（智能体）：
   目标 → [感知] → [规划] → [行动/工具] → [观察] → [反思] → 循环 → 达成目标
   （有状态、会循环、能动手改世界）
```

Agent 的本质差异：**它能为了一个目标，自主决定"下一步干什么"，并且通过工具去改变外部世界（查数据库、调 API、写文件），再根据结果调整自己**。这就是认知。

---

## 二、五大认知支柱

```
            ┌─────────────────────────────┐
            │       Agent 认知架构          │
            └─────────────────────────────┘
   ┌──────────┬──────────┬──────────┬──────────┬──────────┐
   │ 自主性    │ 规划      │ 工具使用  │ 反思     │ 学习     │
   │Autonomy   │Planning   │Tool Use  │Reflection│Learning  │
   └──────────┴──────────┴──────────┴──────────┴──────────┘
      驱动        分解        执行        纠偏        进化
```

### 2.1 自主性（Autonomy）

自主性 = Agent 在没有人类逐步指令的情况下，自行推进目标的能力。它意味着：

- **自启动**：给定目标就开干，不用你一步步喂指令。
- **自循环**：行动→观察→再行动，直到目标达成或断定无法达成。
- **自停止**：达成目标或发现不可能时主动收手，而不是死循环。

<callout emoji="⚠️"><p>自主性的工程红线：必须设置"最大步数 / 超时 / 预算"上限。没有上限的自主 Agent 等于给了它一张无限刷你 API 额度的信用卡。生产环境里自主性永远是"有界自主"。</p></callout>

### 2.2 规划（Planning）

规划 = 把模糊的大目标，分解成可执行、可验证的小步骤。这是 lesson02 的主题。

### 2.3 工具使用（Tool Use）

工具使用 = Agent 通过调用外部函数（API、数据库、代码执行器、浏览器）来感知和改变世界。这是 lesson03 的主题。

### 2.4 反思（Reflection）

反思 = Agent 对"自己刚才做的事"做评估和纠错，发现走偏了就回头重来。这是 lesson04 的主题。

### 2.5 学习（Learning）

学习 = Agent 从过往经验中提炼模式，下次类似任务做得更好（经验回放、少样本、持续学习）。这是 lesson05 的主题。

---

## 三、一根支柱对应一个 Java 模块

把这五大能力映射到工程实现，一个 Agent 框架大致长这样：

```java
// ch08-cognition/src/main/java/demo/cognition/AgentCore.java
package demo.cognition;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;

import java.util.List;

/**
 * 认知核心：把五大支柱编排进一个循环。
 * 这是一个极简 ReAct 风格骨架（lesson02/03/04 会展开）。
 */
public class AgentCore {

    // 1) 自主性：由这个循环驱动，直到 done 或步数耗尽
    public static void run(ChatLanguageModel model,
                           ChatMemory memory,
                           List<Object> tools,
                           String goal,
                           int maxSteps) {
        memory.add("user", goal);
        for (int step = 0; step < maxSteps; step++) {
            // 2) 规划 + 3) 工具使用：模型决定下一步动作（思考/调用工具）
            String decision = model.generate(memory.messages() + "\n请决定下一步(thought/action)");
            // 4) 反思：评估上一步结果是否推进了目标
            if (decision.contains("Final Answer")) break; // 自主性：自停止
            // 执行 tool 并更新 memory（观察）
            // ... (lesson03 详讲工具执行)
        }
    }

    // 工具示例（对应"工具使用"支柱）
    static class Tools {
        @Tool("查询天气")
        String weather(String city) { return city + " 晴 25℃"; }
    }

    public static void main(String[] args) {
        System.out.println("认知核心骨架：自主循环 + 规划 + 工具 + 反思");
    }
}
```

```text
Agent 一次完整认知循环的 ASCII 示意：

  目标: "帮我订明天北京出差的高铁票并提醒我"
    │
    ▼ [规划] 拆成: 1.查班次 2.选时段 3.下单 4.设提醒
    ▼ [工具] 调用 12306 查询 → 观察: 有 G1/G3 两班
    ▼ [反思] G3 时间更合适 → 选 G3
    ▼ [工具] 下单 + 调用日历API设提醒
    ▼ [反思] 确认下单成功、提醒已设 → [自主停止]
    ▼
  完成 ✅
```

---

## 四、为什么"认知架构"是 Agent 的灵魂

很多同学把 Agent 理解成"prompt + 几个 tool"。但真正的差别在架构：

```
没有认知架构（prompt 拼装）：
  用户 → 固定 prompt → 模型 → 固定工具 → 输出
  问题：目标稍变就失效，不会自己调整

有认知架构（五大支柱协同）：
  目标 → 自主循环
        ├ 规划出步骤
        ├ 遇阻时反思、换策略
        ├ 用工具补齐信息/执行动作
        └ 从经验学习、越用越准
  问题：复杂度高，但能处理真实世界的模糊与变化
```

<callout emoji="✅"><p>一个判断框架给面试用：当任务"步骤固定、输入输出确定"时，普通 LLM 应用就够了，别上 Agent（杀鸡用牛刀，又慢又贵）；当任务"路径不确定、需要外部信息、可能失败需重试"时，才需要真正的认知架构。这叫"按需上 Agent"，是工程成熟度的体现。</p></callout>

---

## 五、五大支柱的成熟度模型

给你一张企业落地时的能力成熟度表，方便你定位自己项目在哪一层：

| 支柱 | L1（玩具） | L2（可用） | L3（企业级） |
|------|-----------|-----------|-------------|
| 自主性 | 单轮问答 | 有界多轮循环 | 有界+异常自恢复 |
| 规划 | 无 | CoT 单链 | Plan-and-Execute |
| 工具使用 | 1-2 个 mock | 真实 API | 多工具+权限+熔断 |
| 反思 | 无 | 生成后自检 | 多轮自我修正 |
| 学习 | 无 | 少样本 | 经验库+持续微调 |

---

## 六、企业落地场景：数字员工

某银行的"对公业务数字员工"，认知架构映射：

```
目标: "为申请授信的客户A生成尽调报告"
  自主性  → 自动拉取A的工商/税务/司法数据，无需人工逐步指令
  规划    → 拆成: 主体核验 → 财务分析 → 风险扫描 → 报告生成
  工具使用 → 调工商API、税务库、裁判文书网、内部评级模型
  反思    → 发现税务数据缺失 → 标记缺口、提示补充而非编造
  学习    → 历史相似客户的尽调要点沉淀为经验，复用
```

**为什么这样设计？** 尽调是高风险、多步骤、强合规的任务，任何一步编造或遗漏都可能放款失误。五大支柱协同，既保证"自主跑完"又保证"出错可追责、经验可复用"。

<callout emoji="💡"><p>招聘映射升级：头部厂 JD 已从"会调 tool"升级到"理解 Agent 认知架构、能设计反思/学习机制"。这节课的成熟度模型就是你和面试官对话的脚手架——你能说出"我们项目在规划维度是 L2，下一步上 Plan-and-Execute"，对方立刻知道你是真做过而不是背概念。</p></callout>

---

## 七、踩坑清单

| 坑 | 后果 | 解法 |
|----|------|------|
| 无步数上限的自主循环 | 无限调 API 烧钱 | 设 maxSteps+超时 |
| 把 Agent 当固定 pipeline | 遇变即失效 | 真认知架构+反思 |
| 只堆工具不规划 | 工具越多越乱 | 先规划再调工具 |
| 无学习机制 | 重复犯同样错 | 经验回放(lesson05) |
| 过度上 Agent | 慢且贵 | 按需选用 |

---

## 八、把五大支柱映射成一次真实的代码协作

光讲概念不够，给你看一个把五大支柱串起来的可运行骨架（简化但结构真实），你就能理解它们怎么在代码里咬合：

```java
// ch08-cognition/src/main/java/demo/cognition/CognitiveAgent.java
package demo.cognition;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.agent.tool.Tool;

import java.util.*;

/**
 * 一个最小但完整体现五大支柱的认知 Agent 骨架。
 * 真实项目会在每个支柱上做深（见后续六节及第四章多 Agent）。
 */
public class CognitiveAgent {

    private final ChatLanguageModel model;
    private final ChatMemory memory;
    private final List<Object> tools;
    private final List<String> reflections = new ArrayList<>();

    public CognitiveAgent() {
        this.model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY")).modelName("gpt-4o").build();
        this.memory = MessageWindowChatMemory.withMaxMessages(30); // 短期记忆
        this.tools = List.of(new Tools());
    }

    /** 自主性 + 规划 + 工具 + 反思 的主循环 */
    public String run(String goal, int maxSteps) {
        memory.add("user", goal);
        for (int step = 0; step < maxSteps; step++) {
            // 规划：让模型输出"下一步思考 + 动作"
            String decision = model.generate(
                memory.messages() + "\n[反思经验]" + reflections
                + "\n请决定下一步(thought/action)，如需工具请调用。").content();
            memory.add("ai", decision);

            // 自主停止判定
            if (decision.contains("Final Answer")) break;

            // 工具执行（Tool Use 支柱）—— 这里由框架自动拦截 @Tool 调用
            // 反思：评估上一步是否推进目标
            if (step > 0 && decision.toLowerCase().contains("重复")) {
                reflections.add("第" + step + "步出现重复，应换策略");
            }
        }
        // 学习：把本次反思沉淀（接入 lesson05 经验库）
        return memory.messages().get(memory.messages().size() - 1).text();
    }

    static class Tools {
        @Tool("查询当前时间")
        String now() { return new Date().toString(); }
    }

    public static void main(String[] args) {
        System.out.println(new CognitiveAgent().run("帮我安排今天的工作计划", 6));
    }
}
```

<callout emoji="💡"><p>注意这个骨架的"反思经验"被注入到了每一步的决策 prompt 里——这正是 lesson04/05 的雏形：让 Agent 带着历史教训做新决策。先有这根线，后面六节都是把它在某一根支柱上做厚。</p></callout>

---

## 九、认知架构的常见反模式（踩坑实录）

我带过不少团队做 Agent，下面这些反模式最常见，提前告诉你避坑：

```
反模式1：把 LLM 当数据库
  现象：让模型"记住"所有业务规则，不接工具/知识库
  后果：幻觉频发、更新靠改 prompt、不可追溯
  正解：规则进 RAG/知识库（第七章），模型只做推理

反模式2：无界自主循环
  现象：while(true) 让 Agent 一直跑
  后果：API 账单爆炸、陷入死循环
  正解：maxSteps + 超时 + 预算三重上限

反模式3：规划缺失
  现象：直接把大目标丢给模型"去办"
  后果：步骤混乱、漏关键动作
  正解：先 Plan-and-Execute（lesson02）

反模式4：工具即信任
  现象：把所有系统权限一把梭给 Agent
  后果：误删/误发/越权
  正解：最小权限 + 写操作 Human-in-the-Loop（lesson03）
```

---

## 十、面试高频追问清单

把下面这些问题能答顺，第八章第一节的考点就稳了：

| 追问 | 期望回答要点 |
|------|--------------|
| Agent 和 chatbot 区别？ | 自主性/规划/工具/反思/学习五支柱；能改世界 |
| 为什么要 maxSteps？ | 有界自主，防成本与死循环 |
| 五大支柱怎么落地？ | 各对应模块，见代码骨架 |
| 何时不该用 Agent？ | 任务固定确定时，普通 LLM 应用更省 |
| 反思和学习区别？ | 反思是单次内修正，学习是跨任务沉淀 |
| 认知架构成熟度？ | 用 L1-L3 表定位项目（见第五节） |

## 十一、一个贯穿五支柱的端到端示例

把前面所有概念落到一个"智能运维 Agent"的完整流程，你就明白五大支柱怎么串：

```
目标: "排查订单服务昨天开始的 5xx 告警并给出修复建议"
│
├─[自主性] 收到告警即自启动，无需人工逐步指令
├─[规划] 拆: 1.查告警时间窗 2.拉日志 3.定位异常服务
│          4.查依赖健康度 5.生成修复建议
├─[工具] 调 APM API / 日志系统 / 拓扑服务 (lesson03)
├─[反思] 发现"误判为网络问题"→ 回看日志纠正为"DB连接池耗尽" (lesson04)
└─[学习] 沉淀"DB连接池耗尽→先查连接数配置"经验，下次直接规避 (lesson05)
```

```java
// ch08-cognition/src/main/java/demo/cognition/OpsAgentDemo.java
package demo.cognition;

public class OpsAgentDemo {
    public static void main(String[] args) {
        System.out.println("端到端认知闭环示意:");
        System.out.println("自主启动 → 规划5步 → 调3个工具 → 反思纠错 → 学习沉淀");
        // 实际实现复用 lesson02~05 的模块组合
    }
}
```

---

## 十二、认知架构的"度"：不要过度设计

很多团队一上来就搞 Plan-and-Execute + 反思 + 多专家，结果系统复杂到没人维护。给一张"该上多复杂"的判断表：

| 任务特征 | 推荐复杂度 | 理由 |
|----------|------------|------|
| 单轮问答/分类 | 无 Agent | 普通 LLM 应用 |
| 固定多步流程 | 规划即可 | 不需要反思 |
| 路径不确定需试错 | 规划+工具+反思 | 标准 Agent |
| 高风险跨域决策 | 全支柱+多专家 | 数字员工级 |

<callout emoji="💡"><p>工程成熟度标志之一，就是"懂得不上 Agent"。当任务确定、路径固定，一个写死的 prompt + 几个工具就够，硬上认知架构只会增加延迟、成本和故障面。把复杂的认知能力留给真正需要它的场景。</p></callout>

## 十三、认知架构 vs 传统软件架构

理解 Agent 为什么"难工程化"，要先看清它和传统软件的区别：

```
维度            传统软件                  认知架构 Agent
────────────────────────────────────────────────────────
确定性          输入→固定输出             输入→概率性输出
控制流          代码写死                  模型动态决策
可调试性        断点/日志清晰             决策路径不透明
失败模式        异常可枚举                幻觉/偏离难枚举
测试            单元测试覆盖             评测集+LLM-as-Judge
```

<callout emoji="⚠️"><p>正因为有这些差异，Agent 的工程化（第十章可观测性、第十六章评测、第十七章部署）比传统服务难一个量级。认知架构设计得好，只是"能做对"；要"能稳定上线"还得靠后面几章的工程化闭环。第八章解决"智能"，后面章节解决"可靠"。</p></callout>

---

## 十四、如何向面试官讲清认知架构

给你一个 90 秒叙述框架：

```
1. 定义：Agent = 目标驱动的自主系统，五大支柱协同
2. 对比：与普通LLM应用区别在自主性/规划/工具/反思/学习
3. 落地：用有界自主+规划先行+最小权限工具+反思闭环
4. 演进：用经验库让Agent越用越聪明
5. 边界：按需上Agent，不过度设计
```

| 高频追问 | 加分答法 |
|----------|----------|
| 自主性怎么控？ | maxSteps+超时+预算三重上限 |
| 反思会不会死循环？ | maxIters+无改善即终止 |
| 怎么防工具越权？ | 最小权限+写操作Human确认 |
| 学习会学坏吗？ | 质量门禁+仅存已验证经验 |

## 十五、一个最小但完整的认知 Agent 参考实现

把五大支柱真正落到一个可编译的整体（关键方法示意，集成前几节模块）：

```java
// ch08-cognition/src/main/java/demo/cognition/CognitiveAgentFull.java
package demo.cognition;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

import java.util.ArrayList;
import java.util.List;

public class CognitiveAgentFull {
    private final ChatLanguageModel model;
    private final List<String> reflections = new ArrayList<>();
    private final int maxSteps;

    public CognitiveAgentFull(ChatLanguageModel m, int maxSteps) {
        this.model = m; this.maxSteps = maxSteps;
    }

    public String solve(String goal) {
        var memory = MessageWindowChatMemory.withMaxMessages(40);
        memory.add("user", goal);
        for (int i = 0; i < maxSteps; i++) {
            // [规划] 让模型产出 next step（lesson02）
            // [自我提示] 依子任务选思维模式（lesson06）
            String decision = model.generate(
                memory.messages() + "\n反思:" + reflections + "\n下一步?").content();
            memory.add("ai", decision);
            // [自主停止]
            if (decision.contains("Final Answer")) return decision;
            // [工具] 由框架拦截 @Tool 调用（lesson03）
            // [反思] 诊断是否偏离（lesson04）
            if (decision.contains("重复") || decision.contains("错误")) {
                reflections.add("第" + i + "步异常，应换策略"); // [学习] 沉淀(lesson05)
            }
        }
        return "达上限未完成";
    }
}
```

---

## 十六、认知架构成熟度自测表

给你一张给自己项目打分表（1-5 分）：

| 支柱 | 你项目现状 | 提升动作 |
|------|-----------|----------|
| 自主性 | ☐ | 加 maxSteps/超时 |
| 规划 | ☐ | 上 Plan-and-Execute |
| 工具 | ☐ | 加权限+熔断 |
| 反思 | ☐ | 加 Reflexion 循环 |
| 学习 | ☐ | 建经验库 |

<callout emoji="💡"><p>把这张表贴在你团队 wiki 上。每季度的目标是让每个支柱从 L1 往 L3 爬。认知架构不是"上一次就完美"，是"持续演进"。这也是面试时能讲出"我们 Agent 当前在规划维度是 L2"这种实话的底气来源。</p></callout>

## 小结

这节课立起了第八章的总纲——Agent 认知模型的五大支柱：

- **自主性**：自启动、自循环、自停止，但必须是"有界自主"。
- **规划**：大目标拆小步骤（lesson02 专讲）。
- **工具使用**：通过外部函数感知和改变世界（lesson03 专讲）。
- **反思**：评估并纠偏，走偏了回头（lesson04 专讲）。
- **学习**：从经验进化，越用越准（lesson05 专讲）。
- 五大支柱协同，才是 Agent 区别于普通 LLM 应用的本质；工程上按需"上 Agent"，不过度设计。

接下来六节我们逐一深挖每根支柱。下一节先讲规划能力（lesson02）。

---

## 延伸阅读 / 引用文档

1. Yao et al., *ReAct: Reasoning and Acting in LLMs*: https://arxiv.org/abs/2210.03629
2. Wang et al., *Plan-and-Solve Prompting*: https://arxiv.org/abs/2305.04091
3. Xi et al., *The Rise and Potential of Large Language Model Based Agents* (综述): https://arxiv.org/abs/2309.07864
4. LangChain4j Agent 文档: https://docs.langchain4j.dev/tutorials/agents
5. Google ADK for Java（认知循环实现）: https://github.com/google/adk-java
6. Spring AI Alibaba（Graph 编排规划）: https://github.com/alibaba/spring-ai-alibaba

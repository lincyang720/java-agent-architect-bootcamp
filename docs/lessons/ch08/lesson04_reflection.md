# 第八章第四节：反思机制（Reflection）：错误诊断、策略优化、自我修正

大家好，我是秋天。

上节课我们让 Agent 学会了"动手"（Tool Use）。但动手不代表做对——它可能查错库、理解错需求、生成一堆废话。真正让 Agent 从"会做"进化到"做对"的，是**反思机制（Reflection）**：它得能回过头看自己刚才干得怎么样，发现不对就改。

学完这节课，你能掌握：

1. **反思的本质**：Agent 对自己的"产出 + 环境反馈"做评估，产出"批判性反馈"再迭代。
2. **错误诊断**：怎么判断"这一步到底错在哪"——是检索错、规划错、还是执行错。
3. **自我修正（Reflexion 框架）**：把反思写进记忆，下一轮带着批评重做，直到通过。

<callout emoji="💡"><p>招聘映射：JD 里"具备反思/自我修正能力""理解 Reflexion 框架"。这是 Agent 质量从 60 分到 90 分的关键能力，面试时能讲清反思闭环的，已经是中高级水平。</p></callout>

---

## 一、没有反思的 Agent 是什么样

```
无反思 Agent：
  目标 → 规划 → 执行 → 输出（无论对错）
  问题：第一次就错，错到底，且下次还犯同样的错

有反思 Agent：
  目标 → 规划 → 执行 →【反思:哪里错了】→ 修正策略 → 再执行 → 输出
  且把"反思经验"存下来，下次遇到类似问题直接规避
```

反思解决的是**"错误不会自动消失"**这个根本问题。LLM 不会因为你上次答错就天然改好，必须显式地把"错在哪"喂回去。

---

## 二、反思的三种层次

```
层次          做什么                  例子
─────────────────────────────────────────────────────
L1 结果校验    检查产出是否满足硬约束   答案是否引用了来源？代码能否编译？
L2 过程诊断    分析哪一步走偏          检索召回错了？还是规划拆错了？
L3 策略优化    调整方法而非只改答案    下次先查XX库、换种提问方式
```

### 2.1 L1 结果校验（最简单、性价比最高）

```java
// ch08-cognition/src/main/java/demo/reflection/ResultValidator.java
package demo.reflection;

import java.util.regex.Pattern;

public class ResultValidator {

    /** 校验 RAG 答案是否带了引用（呼应第七章 lesson07） */
    public static String validateCitation(String answer) {
        if (!Pattern.compile("\\[来源:.*?\\]").matcher(answer).find()) {
            return "FAIL: 答案缺少引用溯源，不可信";
        }
        return "PASS";
    }

    /** 校验生成的 SQL 是否只读 */
    public static String validateSql(String sql) {
        String lower = sql.toLowerCase();
        if (lower.matches(".*(insert|update|delete|drop|truncate).*")) {
            return "FAIL: 检测到写操作，违反只读约束";
        }
        return "PASS";
    }

    public static void main(String[] args) {
        System.out.println(validateCitation("退款24小时内到账"));
        System.out.println(validateCitation("退款24小时内到账[来源:c1]"));
    }
}
```

### 2.2 L2 过程诊断（定位错因）

```java
// ch08-cognition/src/main/java/demo/reflection/Diagnoser.java
package demo.reflection;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;

public class Diagnoser {

    interface Critic {
        @UserMessage("""
            分析下面 Agent 的【轨迹】为什么没达成【目标】。
            请判断错误类型(检索错误/规划错误/工具调用错误/推理错误)，
            并给出具体修正建议。只输出 JSON。
            【目标】{{goal}}
            【轨迹】{{trajectory}}
            """)
        String diagnose(String goal, String trajectory);
    }

    public static void main(String[] args) {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY")).modelName("gpt-4o").build();
        Critic critic = AiServices.create(Critic.class, model);

        String diag = critic.diagnose(
            "查北京天气",
            "调用getWeather(上海)→返回晴→用户说不对");
        System.out.println(diag);  // {"errorType":"工具调用错误","fix":"参数应为北京"}
    }
}
```

---

## 三、Reflexion 框架：把反思写进记忆

Reflexion（Shinn et al., 2023）是反思机制的奠基性框架。核心循环：

```
        ┌──────────────── 反思循环 ────────────────┐
        │                                          │
   Actor ──► 执行任务 ──► 产生轨迹(trajectory)      │
        │                  │                       │
        │                  ▼                       │
        │            Evaluator 评估                  │
        │                  │                       │
        │                  ▼                       │
        │            Reflector 生成批评(feedback) ──┘ (反馈进入记忆)
        │                  │
        ▼                  ▼
   带记忆重新执行（这次"看得到"上次的批评）
```

### 3.1 Java 实现 Reflexion 循环

```java
// ch08-cognition/src/main/java/demo/reflection/Reflexion.java
package demo.reflection;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.util.ArrayList;
import java.util.List;

public class Reflexion {

    // 反思记忆：累积的"批评反馈"
    private final List<String> reflections = new ArrayList<>();

    /** 一轮：执行 + 评估 + 反思，返回是否通过 */
    public String runLoop(ChatLanguageModel model, String goal, int maxIters) {
        List<ChatMessage> memory = new ArrayList<>();
        memory.add(dev.langchain4j.data.message.SystemMessage.from(
            "你是严谨的Agent。历史反思:" + String.join("\n", reflections)));

        for (int i = 0; i < maxIters; i++) {
            // 1) Actor 执行（带历史反思）
            String attempt = model.generate(memory + "\n目标:" + goal + "\n请执行").content();
            memory.add(dev.langchain4j.data.message.AiMessage.from(attempt));

            // 2) Evaluator 评估
            String eval = model.generate("评估下面结果是否达成目标[" + goal + "]:\n" + attempt
                    + "\n只回 PASS 或 FAIL 及原因").content();

            if (eval.contains("PASS")) {
                return attempt;   // 通过，返回最终答案
            }

            // 3) Reflector 生成批评并写入记忆
            String feedback = model.generate(
                "上一步失败:" + eval + "\n请给出改进建议(批评)").content();
            reflections.add("迭代" + i + "失败原因:" + feedback);
            memory.add(dev.langchain4j.data.message.SystemMessage.from(
                "反思反馈:" + feedback));
        }
        return "❌ 达到最大迭代仍未通过";
    }

    public static void main(String[] args) {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY")).modelName("gpt-4o").build();
        String result = new Reflexion().runLoop(model, "生成可编译的Java快排", 3);
        System.out.println(result);
    }
}
```

<callout emoji="⚠️"><p>Reflexion 的代价：每多一轮反思就多一次（甚至多次）LLM 调用。生产必须设 maxIters（通常 2~3），且只在"任务重要、一次难做对"时启用（如代码生成、复杂报告）。对账、客服这类高吞吐场景不该每轮都反思，否则成本和延迟爆炸。</p></callout>

---

## 四、错误诊断清单：错在哪一类

把常见错误归类，才能对症修正：

| 错误类型 | 表现 | 修正策略 |
|----------|------|----------|
| 检索错误 | 召回内容不相关 | 改写 query / 换库路由 |
| 规划错误 | 步骤顺序错/漏步骤 | 重规划、补子任务 |
| 工具调用错误 | 参数错/调错工具 | 参数校验 + 工具描述优化 |
| 推理错误 | 逻辑矛盾/幻觉 | 强制引用 + 结果校验 |
| 执行错误 | API 超时/异常 | 重试 + 降级 |

```java
// ch08-cognition/src/main/java/demo/reflection/ErrorClassifier.java
package demo.reflection;

public class ErrorClassifier {
    enum ErrorType { RETRIEVAL, PLANNING, TOOL, REASONING, EXECUTION }

    public static ErrorType classify(String symptom) {
        if (symptom.contains("召回") || symptom.contains("检索"))
            return ErrorType.RETRIEVAL;
        if (symptom.contains("步骤") || symptom.contains("顺序"))
            return ErrorType.PLANNING;
        if (symptom.contains("参数") || symptom.contains("工具"))
            return ErrorType.TOOL;
        if (symptom.contains("矛盾") || symptom.contains("编造"))
            return ErrorType.REASONING;
        return ErrorType.EXECUTION;
    }
}
```

---

## 五、策略优化：从"改答案"到"改方法"

L3 反思不止改这一次的答案，而是**调整以后的策略**。比如 Agent 发现"用户问合同类问题，第一次总搜错库"，就形成一条策略："合同类问题优先路由 legal 库"。这正是 lesson05 学习机制的雏形。

```java
// ch08-cognition/src/main/java/demo/reflection/StrategyOptimizer.java
package demo.reflection;

import java.util.*;

public class StrategyOptimizer {
    // 策略库：从反思中沉淀的"经验规则"
    private final Map<String, String> strategies = new HashMap<>();

    /** 把一次反思转化为可复用策略 */
    public void learnFromReflection(String errorPattern, String betterApproach) {
        strategies.put(errorPattern, betterApproach);
    }

    /** 执行前先查策略，规避已知错误 */
    public String applyStrategy(String context) {
        for (var e : strategies.entrySet()) {
            if (context.contains(e.getKey())) return e.getValue();
        }
        return "无已知策略，正常执行";
    }

    public static void main(String[] args) {
        StrategyOptimizer opt = new StrategyOptimizer();
        opt.learnFromReflection("合同", "优先路由 legal 库并强制引用");
        System.out.println(opt.applyStrategy("用户问合同条款"));
        // 输出: 优先路由 legal 库并强制引用
    }
}
```

<callout emoji="✅"><p>反思的最高形态不是"这次改对"，而是"以后再也不犯"。把每次反思的"错因→对策"沉淀成策略库（或经验回放，见 lesson05），Agent 就完成了从"被动纠错"到"主动规避"的跃迁。这是 Agent 质量分水岭。</p></callout>

---

## 六、企业落地：代码生成 Agent 的反思闭环

某内部"代码生成 Agent"的反思设计：

```
目标: "生成查询订单的 Spring Boot Controller"
  │
  ▼ Actor 生成代码
  ▼ Evaluator: 编译检查(调用 javac/测试) → FAIL: 缺少 @RequestBody
  ▼ Reflector: 批评"参数绑定错误，需加 @RequestBody"
  ▼ 记忆写入反思 → 重新生成（这次带上"加 @RequestBody"）
  ▼ Evaluator: 编译通过 + 单测通过 → PASS ✅
  ▼ 沉淀策略: "生成 Controller 时默认加 @RequestBody"
```

**为什么这样设计？** 代码生成"一次写对"概率低，但"编译+单测"是客观、可机器验证的评估器——比让模型自评靠谱得多。反思闭环让 Agent 借助确定性测试反馈自我修正，是当下最实用的 Agent 质量提升手段。

---

## 七、踩坑清单

| 坑 | 后果 | 解法 |
|----|------|------|
| 无反思直接输出 | 错到底 | 加 Evaluator |
| 让模型自评（无客观标准） | 自评放水 | 用编译/测试/规则校验 |
| 反思无迭代上限 | 成本爆炸 | maxIters=2~3 |
| 反思不沉淀 | 重复犯同错 | 写策略库/经验回放 |
| 高吞吐场景每轮反思 | 延迟成本双高 | 仅重要任务启用 |

---

## 八、用确定性测试做 Evaluator（替代模型自评）

最靠谱的评估器不是让模型"自己打分"，而是**用确定性测试验证产出**。尤其代码生成场景，直接编译+单测：

```java
// ch08-cognition/src/main/java/demo/reflection/CompilationEvaluator.java
package demo.reflection;

import javax.tools.*;
import java.io.*;
import java.nio.file.*;

public class CompilationEvaluator {
    /** 把生成的 Java 代码写到临时文件并编译，返回是否通过 */
    public static boolean compiles(String code, String className) {
        try {
            Path dir = Files.createTempDirectory("gen");
            Path file = dir.resolve(className + ".java");
            Files.writeString(file, code);
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            int r = compiler.run(null, null, null, file.toString());
            return r == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static void main(String[] args) {
        String badCode = "public class A { void f() { int x = ; } }"; // 语法错
        System.out.println("编译通过? " + compiles(badCode, "A")); // false
    }
}
```

<callout emoji="✅"><p>这条"编译即评估"的思路是当下代码 Agent 最实用的反思闭环：生成 → 编译 → 失败就把错误回灌 → 重新生成，直到编译通过。比让模型"你觉得自己写对了吗"靠谱一万倍。规则类、数值类任务同理——能用代码/规则验证的，绝不用模型自评。</p></callout>

---

## 九、反思的存储与检索（接 lesson05 学习）

反思产出的"批评"若不持久化，下次任务又得重蹈覆辙。把它存进经验库：

```java
// ch08-cognition/src/main/java/demo/reflection/ReflectionStore.java
package demo.reflection;

import demo.learning.ExperienceStore;

public class ReflectionStore {
    private final ExperienceStore exp;
    public ReflectionStore(ExperienceStore exp) { this.exp = exp; }

    /** 把一次反思的教训固化成可复用经验 */
    public void persist(String errorSignature, String lesson) {
        // 失败经验加权：用前缀标记以便检索时优先
        exp.record("[避坑]" + errorSignature, lesson, false);
    }
}
```

---

## 十、反思的成本治理

反思不是免费的。给你一张成本决策表：

| 场景 | 是否启用反思 | 理由 |
|------|--------------|------|
| 代码生成 | 强烈建议 | 编译测试客观、价值高 |
| 复杂报告 | 建议 | 质量敏感 |
| 高吞吐客服 | 谨慎 | 每轮反思拖慢体验 |
| 简单问答 | 不启用 | 浪费 token |

```yaml
reflection:
  enabled: true
  max-iterations: 3
  evaluator: compilation      # compilation | test | rule | llm
  persist-on-failure: true    # 失败经验入库(接lesson05)
  cost-guard:
    max-tokens-per-loop: 2000
```

<callout emoji="⚠️"><p>反思循环最容易失控的就是"无限自我否定"——模型改完觉得还不行又改，来回空转。max-iterations 是硬保险，且建议每轮对比"是否比上轮更优"，无改善立即终止，别陪它空转烧钱。</p></callout>

## 十一、端到端反思示例：代码修复 Agent

完整串起"生成→评估→反思→重做"：

```java
// ch08-cognition/src/main/java/demo/reflection/CodeFixAgent.java
package demo.reflection;

import demo.reflection.CompilationEvaluator;

public class CodeFixAgent {
    public static String fix(String broken, int maxIter) {
        String current = broken;
        for (int i = 0; i < maxIter; i++) {
            if (CompilationEvaluator.compiles(current, "Solution")) {
                return current; // 通过即返回
            }
            // 把编译错误作为"批评"回灌，重新生成（示意）
            current = regenerateWithFeedback(current, lastError(current));
        }
        return "❌ 未通过";
    }
    static String regenerateWithFeedback(String code, String err) { return code; }
    static String lastError(String code) { return "编译错误"; }
}
```

```
反思循环可视化：
  ┌─────────┐   编译失败    ┌──────────┐
  │ 生成代码 │ ──────────► │ 编译评估 │
  └─────────┘              └────┬─────┘
       ▲                        │ 错误回灌
       │ 带批评重生成            ▼
       └─────────────────── [通过?] ──yes──► 返回
```

---

## 十二、反思与学习的衔接表

| 阶段 | 产物 | 流向 |
|------|------|------|
| 单次反思(L4) | 本轮批评 | 注入下一轮决策 |
| 失败经验(L5) | 避坑规则 | 经验库持久化 |
| 模式沉淀 | 通用策略 | few-shot 模板 |

<callout emoji="✅"><p>反思和学习是"一体两面"：反思解决"这次怎么改对"，学习解决"以后怎么不再错"。工程上务必把反思的产出接到 lesson05 的经验库，否则反思只是一次性消耗，价值大打折扣。</p></callout>

## 十三、反思与上下文工程（接第十五章）

反思产出的"批评"要妥善放进上下文，才能在下轮生效：

```
Write   : 把反思批评写入上下文（"上次因参数错失败"）
Select  : 只选与当前步相关的反思
Compress: 历史反思压缩为要点，防爆炸
Isolate : 不同任务的反思隔离，防串味
```

---

## 十四、反思机制的招聘话术

面试官问"你的 Agent 怎么保证质量"，可以这样答：

```
"我们在三层做质量保证：
 1. 结果校验层：硬约束检查（引用/编译/格式），零成本拦大部分错
 2. 反思层：Reflexion 式循环，失败→批评→重做，限 maxIters
 3. 学习层：反思经验沉淀经验库，跨任务规避（lesson05）
 且用客观评估器（编译/测试/规则）替代模型自评，避免自评放水。"
```

| 追问 | 加分答法 |
|------|----------|
| 反思会不会死循环？ | maxIters+无改善终止 |
| 评估器用啥？ | 编译/单测/规则优先，模型自评兜底 |
| 反思经验去哪了？ | 接经验库持久化复用 |

## 十五、反思机制完整参考实现

整合前文，给出一个可参考的 Reflexion 闭环：

```java
// ch08-cognition/src/main/java/demo/reflection/ReflexionFull.java
package demo.reflection;

import dev.langchain4j.model.chat.ChatLanguageModel;

import java.util.ArrayList;
import java.util.List;

public class ReflexionFull {
    private final ChatLanguageModel model;
    private final List<String> reflections = new ArrayList<>();

    public ReflexionFull(ChatLanguageModel m) { this.model = m; }

    public String loop(String goal, int maxIter) {
        StringBuilder ctx = new StringBuilder("目标:" + goal);
        for (int i = 0; i < maxIter; i++) {
            ctx.append("\n历史反思:").append(reflections);
            String attempt = model.generate(ctx + "\n请执行").content();
            String eval = model.generate("评估是否达成[" + goal + "]:\n" + attempt
                    + "\n只回PASS/FAIL及原因").content();
            if (eval.contains("PASS")) return attempt;
            String fb = model.generate("失败原因:" + eval + "\n给改进批评").content();
            reflections.add("迭代" + i + ":" + fb);  // 写入记忆
        }
        return "未通过";
    }
}
```

---

## 十六、反思机制检查清单

| 能力 | 是否具备 |
|------|----------|
| 结果校验(L1) | ☐ |
| 过程诊断(L2) | ☐ |
| 策略优化(L3) | ☐ |
| 客观评估器 | ☐ |
| 反思经验持久化 | ☐ |
| maxIters 上限 | ☐ |

<callout emoji="⚠️"><p>反思是"双刃剑"：用得好质量飙升，用不好成本和延迟飙升。上线前务必确认"评估器是客观的""有迭代上限""失败经验进了学习库"。三者缺任一，反思就只是烧钱。</p></callout>

## 小结

这节课把"反思机制"这根支柱讲透了：

- 反思解决"错误不会自动消失"，必须显式把"错在哪"喂回 Agent。
- 三层：L1 结果校验（硬约束，性价比最高）、L2 过程诊断（定位错因）、L3 策略优化（改方法而非改答案）。
- **Reflexion 框架**：Actor→Evaluator→Reflector 循环，批评写入记忆再重做，需设 maxIters。
- 用客观评估器（编译/测试/规则）替代模型自评，反思才靠谱。
- 最高形态是把反思沉淀成策略库，从"被动纠错"到"主动规避"，为 lesson05 学习机制铺路。

下一节讲学习机制（Learning）——Agent 怎么从经验里"越用越聪明"（lesson05）。

---

## 延伸阅读 / 引用文档

1. Shinn et al., *Reflexion: Language Agents with Verbal Reinforcement Learning*: https://arxiv.org/abs/2303.11366
2. Madaan et al., *Self-Refine: Iterative Refinement* (自我精炼): https://arxiv.org/abs/2303.17651
3. LangChain4j 反思模式示例: https://docs.langchain4j.dev/tutorials/agents
4. AgentScope ReMe（记忆与反思项目，第六章提及）: https://github.com/modelscope/agentscope
5. Reflexion 官方实现: https://github.com/noahshinn/reflexion
6. Google ADK for Java（评估器集成）: https://github.com/google/adk-java

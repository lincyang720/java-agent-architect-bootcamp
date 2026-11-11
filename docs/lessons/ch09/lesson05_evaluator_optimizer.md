# 第九章第五节：评估-优化（Evaluator-Optimizer）：自我反思循环（生成-评估-迭代）

大家好，我是秋天。

前面几节讲的是"任务怎么拆、怎么走"。但这节课讲一个更深层的能力：**让 Agent 自己评判自己干得好不好，不好就改**。这就是**评估-优化（Evaluator-Optimizer）**：一个生成器（Generator）产出结果，一个评估器（Evaluator）挑毛病，两者反复迭代直到通过。它是第八章"反思机制"在工程模式层面的正式形态。

学完这节课，你能掌握：

1. **评估-优化的闭环结构**：Generate → Evaluate → Feedback → Regenerate，直到满足停止条件。
2. **Evaluator 怎么写**：规则校验、LLM-as-Judge、多维度评分三种实现。
3. **Java 落地**：LangChain4j / Spring AI 实现生成-评估双 Agent 循环。
4. **何时该用、何时别用**：迭代的成本与收益边界。
5. **企业场景**：代码生成自测循环、文案多轮打磨、RAG 答案质量自审。

<callout emoji="💡"><p>招聘映射：JD 里"理解 Reflexion / 自我修正""设计 Agent 质量闭环""熟悉 LLM-as-Judge"。这直接对应第十六章"评测与质量闭环"的工程入口，是 Agent 从 Demo 到生产的关键分水岭。</p></callout>

---

## 一、为什么单趟生成不够

```
单趟生成：
   输入 ─▶ [生成] ─▶ 输出
   问题：第一次就错，错到底；质量全凭模型一次性发挥，不可控

评估-优化：
   输入 ─▶ [生成] ─▶ [评估] ── PASS ─▶ 输出
                       │
                      FAIL
                       │ 反馈"哪里错"
                       ▼
                    [生成](带反馈) ─▶ 再评估 ─▶ ...
```

本质：**把"质量判定"从一次性的"靠运气"变成"可迭代的工程过程"**。Evaluator 就是一道质检闸门。

```
            ┌──────────── 反馈(批评) ────────┐
            ▼                                │
   [Generator] ──▶ 产出 ──▶ [Evaluator] ──┐  │
                                  │        │  │
                                PASS     FAIL│ │
                                  │        │  │
                                  ▼        └──┘
                                输出      (带批评重生成)
```

---

## 二、Evaluator 的三种实现

```
类型              怎么做                  适用
──────────────────────────────────────────────────────────────
规则校验          正则/结构/断言          格式、必含字段、可编译
LLM-as-Judge      另一个模型打分评判      语义质量、流畅度、合规
多维评分          多个维度分别打分        综合质量(如代码:可编译+安全+风格)
```

<callout emoji="✅"><p>判断框架：能上规则的绝不调模型（快且稳）；规则覆盖不到的语义质量才用 LLM-as-Judge；关键产出用"规则+Judje"双闸。</p></callout>

---

## 三、规则校验 Evaluator（最快最稳）

```java
// ch09-patterns/src/main/java/demo/evaluator/RuleEvaluator.java
package demo.evaluator;

import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;

public class RuleEvaluator {

    record Check(String name, boolean pass, String reason) {}

    /** 校验 RAG 答案：必须含引用 + 不含编造承诺 */
    static List<Check> evaluate(String answer) {
        List<Check> checks = new ArrayList<>();
        boolean hasCite = Pattern.compile("\\[来源:.*?\\]").matcher(answer).find();
        checks.add(new Check("含引用溯源",
                hasCite, hasCite ? "ok" : "缺少 [来源:x]"));

        boolean noPromise = !answer.matches(".*(保证|100%|绝对).*");
        checks.add(new Check("无绝对化承诺",
                noPromise, noPromise ? "ok" : "含违规承诺词"));

        boolean notEmpty = answer.trim().length() > 10;
        checks.add(new Check("非空且足够详细",
                notEmpty, notEmpty ? "ok" : "内容过短"));
        return checks;
    }

    public static void main(String[] args) {
        String ans = "退款将在3个工作日内到账[来源:政策P1]";
        evaluate(ans).forEach(c ->
            System.out.println((c.pass() ? "✅" : "❌") + c.name() + " - " + c.reason()));
    }
}
```

规则 Evaluator 不调模型，毫秒级，适合做"硬约束闸门"——任何产出先过规则，不过直接打回。

---

## 四、LLM-as-Judge Evaluator

用另一个（通常更强的）模型当裁判，对产出做语义评判。这是评估-优化的核心。

```java
// ch09-patterns/src/main/java/demo/evaluator/JudgeEvaluator.java
package demo.evaluator;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public class JudgeEvaluator {

    interface Judge {
        @SystemMessage("""
            你是严格的质量评审。针对【任务】和【产出】，判断产出是否满足要求。
            输出 JSON: {"score":1-5, "pass":true/false, "feedback":"具体改进建议"}
            只输出 JSON，不要其他内容。
            【任务】{{task}}
            【产出】{{output}}
            """)
        String judge(String task, String output);
    }

    public static void main(String[] args) {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY")).modelName("gpt-4o").build();
        Judge judge = AiServices.create(Judge.class, model);

        String task = "写一段客服道歉文案，需含订单号与补偿方案";
        String output = "非常抱歉给您带来不便。";
        String verdict = judge.judge(task, output);
        System.out.println("评审结果=" + verdict);
        // {"score":2,"pass":false,"feedback":"缺少订单号与补偿方案，需补充"}
    }
}
```

<callout emoji="⚠️"><p>LLM-as-Judge 有偏差（偏好长文本、跟风向等），生产环境要与"规则校验"和"人工抽检"结合，并持续校准（第十六章详述）。别把 Judge 当唯一真理。</p></callout>

---

## 五、完整闭环：Generator + Evaluator 迭代

把生成器和评估器串成循环，带反馈重生成。

```java
// ch09-patterns/src/main/java/demo/evaluator/OptimizeLoop.java
package demo.evaluator;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public class OptimizeLoop {

    interface Generator {
        @SystemMessage("根据任务生成内容。{{feedback}}")
        String generate(@UserMessage String task);
    }

    interface Judge {
        @SystemMessage("""
            评审产出是否满足任务要求。输出 JSON:
            {"pass":true/false,"feedback":"若未通过，给出具体改进点"}
            【任务】{{task}} 【产出】{{output}}
            """)
        String judge(String task, String output);
    }

    public static void main(String[] args) {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o").temperature(0.3).build();
        Generator gen = AiServices.create(Generator.class, model);
        Judge judge = AiServices.create(Judge.class, model);

        String task = "写客服道歉文案，含订单号A100与补偿:补偿券50元";
        String feedback = "";
        String output = "";
        int maxIter = 3;
        for (int i = 0; i < maxIter; i++) {
            output = gen.generate(task + feedback);
            String verdict = judge.judge(task, output);
            System.out.println("第" + (i + 1) + "轮:\n" + output + "\n评审:" + verdict);
            if (verdict.contains("\"pass\":true")) {
                System.out.println("✅ 通过，停止迭代");
                break;
            }
            // 把评审反馈注入下一轮生成
            feedback = "\n[上轮未通过，请改进]: " + verdict;
        }
    }
}
```

```
迭代循环示意：
  轮1: 生成(无反馈) ─▶ 评审 FAIL ─▶ 反馈"缺订单号"
  轮2: 生成(带反馈) ─▶ 评审 FAIL ─▶ 反馈"缺补偿方案"
  轮3: 生成(带反馈) ─▶ 评审 PASS ─▶ 输出
```

<callout emoji="💡"><p>停止条件设计：除了"PASS"，还必须设**最大迭代数**（防死循环烧钱）和**早期退出**（连续两轮分数无提升就停）。这是成本治理（第十章第六节）在循环里的体现。</p></callout>

---

## 六、代码生成的自测循环（企业高频场景）

代码生成最容易"看着对实则编译不过"。评估-优化在这里价值巨大：Generator 写代码，Evaluator 实际编译/运行测试。

```java
// ch09-patterns/src/main/java/demo/evaluator/CodeGenLoop.java
package demo.evaluator;

import java.io.*;
import java.nio.file.*;

public class CodeGenLoop {

    /** Evaluator：把生成的 Java 代码写到临时文件并尝试编译 */
    static String compileCheck(String code) {
        try {
            Path dir = Files.createTempDirectory("codegen");
            Path file = dir.resolve("Gen.java");
            Files.writeString(file, code);
            Process p = new ProcessBuilder("javac", file.toString())
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            int exit = p.waitFor();
            return exit == 0 ? "PASS: 编译通过" : "FAIL: " + out;
        } catch (Exception e) {
            return "FAIL: " + e.getMessage();
        }
    }

    public static void main(String[] args) {
        String code = "public class Gen { public static void main(String[] a){System.out.println(1);} }";
        System.out.println(compileCheck(code));
    }
}
```

```
代码自测闭环：
   Generator 写代码 ─▶ Evaluator(javac/mvn test)
        │                    │
       FAIL ◀── 编译错误 ◀───┘
        │ 把报错反馈给 Generator
        ▼
   重新生成(带报错上下文) ─▶ 再编译 ─▶ PASS 才交付
```

这就是"Agent 写代码 + 真实编译器当 Evaluator"的工业级玩法，比纯语义 Judge 可靠得多。

---

## 七、多维评分 Evaluator

对关键产出（如对外公告），用多个维度分别打分，加权汇总决定是否通过。

```java
// ch09-patterns/src/main/java/demo/evaluator/MultiDimEvaluator.java
package demo.evaluator;

import java.util.Map;

public class MultiDimEvaluator {

    // 维度 -> 权重
    static final Map<String, Double> WEIGHTS = Map.of(
            "准确性", 0.4, "合规性", 0.3, "友好度", 0.3);

    /** 各维度分数(0-5)，返回加权总分与是否通过 */
    static String evaluate(Map<String, Integer> scores) {
        double total = 0;
        for (var e : scores.entrySet()) {
            total += e.getValue() * WEIGHTS.getOrDefault(e.getKey(), 0.0);
        }
        double normalized = total / 5; // 归一到 0-1
        boolean pass = normalized >= 0.7;
        return String.format("加权分=%.2f 通过=%s", normalized, pass);
    }

    public static void main(String[] args) {
        System.out.println(evaluate(Map.of(
                "准确性", 5, "合规性", 4, "友好度", 3)));
    }
}
```

---

## 八、什么时候别用评估-优化

迭代有成本，别滥用：

```
适合用：
   - 产出质量波动大、单趟不可靠（代码、长文、复杂推理）
   - 有客观 Evaluator（编译、测试、规则）
   - 产出价值高、值得多花几倍成本（对外公告、核心代码）

不适合用：
   - 简单查询/分类（单趟足矣，迭代纯浪费）
   - 无可靠 Evaluator（"好不好"无法判定，迭代无意义）
   - 实时性要求极高（迭代增加延迟）
```

<callout emoji="⚠️"><p>经验法则：如果 Evaluator 本身不可靠，评估-优化会"用错误标准反复打磨错误答案"，比单趟还糟。先保证 Evaluator 质量，再上迭代。</p></callout>

---

## 九、与第八章反思、第十六章评测的关系

```
第八章 反思(Reflexion)：   认知层面的"自我修正"理论
第九章 评估-优化(本节)：    把反思工程化为"生成-评估"双 Agent 模式
第十六章 评测闭环：         把 Evaluator 标准化、离线/在线化、CI 卡点化

三者一脉相承：
   反思(思想) → 评估-优化(模式) → 评测闭环(工程体系)
```

企业落地时，评估-优化循环里的 Evaluator 应复用第十六章的评测指标（RAGAS、LLM-as-Judge 校准），形成"运行时自审 + 离线评测"双保险。

---

## 十、企业场景：RAG 答案质量自审

```
用户问 → RAG 检索 → 生成答案 → [Evaluator]
                                ├─ 含引用? 相关? 无幻觉?
                                ├─ PASS → 返回用户
                                └─ FAIL → 带"问题诊断"重生成(可换检索策略)
```

```yaml
# ch09-patterns/src/main/resources/evaluator-config.yml
evaluator-optimizer:
  max-iterations: 3
  early-stop:
    enabled: true
    no-improve-rounds: 2   # 连续2轮分数无提升则停
  judge:
    model: gpt-4o          # 评审用强模型
    dimensions: [准确性, 合规性, 相关性]
  generator:
    model: gpt-4o-mini     # 生成用性价比模型
```

注意"生成用便宜、评审用贵"的模型路由思路——又一次印证链/编排里"按步选模型"的成本治理逻辑。

---

## 十一、Evaluator 的校准与偏差控制

LLM-as-Judge 不是"客观真理"，它有自己的偏差。直接拿 Judge 结果当质量准绳，会系统性误判。

```
常见 Judge 偏差：
   长度偏差     ─ 偏好更长的答案, 哪怕啰嗦
   位置偏差     ─ 偏好排在前面的选项
   自我偏好     ─ 偏好"和自己风格相似的"生成结果
   跟风偏差     ─ 多个 Judge 互相影响, 趋同
   领域偏差     ─ 通用模型评专业领域(医疗/法律)不准
```

```java
// ch09-patterns/src/main/java/demo/evaluator/JudgeCalibration.java
package demo.evaluator;

import java.util.List;

public class JudgeCalibration {

    /** 校准: 用已知"金标准"样本检验 Judge 是否靠谱 */
    record Gold(String output, boolean expectedPass) {}

    static double judgeAccuracy(List<Gold> golds, java.util.function.Predicate<String> judge) {
        long correct = golds.stream()
                .filter(g -> judge.test(g.output) == g.expectedPass)
                .count();
        return (double) correct / golds.size();
    }

    public static void main(String[] args) {
        List<Gold> golds = List.of(
            new Gold("含订单号A100与补偿券50元", true),
            new Gold("抱歉给您带来不便", false), // 应判 FAIL 但弱 Judge 可能误判 PASS
            new Gold("订单A100补偿50元", true));
        // 假设 Judge 实现
        java.util.function.Predicate<String> judge =
            (o) -> o.contains("订单") && o.contains("补偿");
        System.out.println("Judge 校准准确率=" +
            String.format("%.2f", judgeAccuracy(golds, judge)));
    }
}
```

```
校准闭环(对接第十六章评测):
   1. 人工标注一批"金标准"(输出+应得判定)
   2. 跑 Judge, 算准确率/召回率
   3. 准确率<阈值 → 换更强 Judge / 改 Judge prompt / 多 Judge 投票
   4. 定期重新校准(模型/任务漂移)
```

<callout emoji="⚠️"><p>关键认知：Judge 本身也是个模型，需要被"评测"。把未校准的 Judge 直接接进生产闭环，等于"用可能错的尺子量对错"，会放大错误。校准是 LLM-as-Judge 的工程底线。</p></callout>

---

## 十二、评估-优化的成本护栏

评估-优化循环会反复调模型，是 token 消耗大户。必须上护栏（呼应第十章第六节成本治理）。

```
护栏设计：
   - 最大迭代次数(硬上限, 如 3 次)       → 防死循环
   - 早期退出(连续2轮分数无提升则停)    → 防无效迭代
   - 生成用便宜模型, 评审用贵模型        → 模型路由降本
   - 单次迭代 token 上限                 → 防单轮爆炸
```

```yaml
# 评估-优化成本护栏配置
evaluator-optimizer:
  budget:
    max-iterations: 3
    early-stop-rounds: 2
    max-tokens-per-iteration: 4000
  model-routing:
    generator: gpt-4o-mini
    judge: gpt-4o
```

---

## 小结

- 评估-优化 = Generator 产出 + Evaluator 评判 + 反馈迭代的闭环，是反思机制的工程形态。
- Evaluator 三实现：规则校验（快稳）、LLM-as-Judge（语义）、多维评分（综合），生产常组合。
- 闭环必须设停止条件：PASS、最大迭代数、早期退出，避免死循环烧钱。
- 代码场景用真实编译器/测试当 Evaluator 最可靠；无可靠 Evaluator 时迭代无意义。
- 与第八章反思、第十六章评测一脉相承：思想→模式→工程体系；运行时自审 + 离线评测双保险。

## 延伸阅读 / 引用文档

1. Reflexion 论文（Shinn et al., 2023）：https://arxiv.org/abs/2303.11366
2. Anthropic《Building Effective Agents》Evaluator-Optimizer：https://www.anthropic.com/research/building-effective-agents
3. LangChain4j 文档：https://docs.langchain4j.dev
4. 第十六章相关：RAGAS 评测框架：https://docs.ragas.io
5. LLM-as-Judge 研究（Zheng et al.）：https://arxiv.org/abs/2306.05685
6. Spring AI 文档：https://docs.spring.io/spring-ai/reference/index.html

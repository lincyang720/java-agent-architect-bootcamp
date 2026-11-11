# 第九章第二节：路由（Routing）：基于输入动态选择执行路径（分类器、LLM 决策）

大家好，我是秋天。

提示链讲的是"一条路走到底"。但现实业务里，输入千奇百怪：有人问退款，有人问技术 bug，有人只是来骂街。如果你用同一条链硬扛所有请求，结果就是"什么都做不好"。这节课讲**路由（Routing）**：先判断"这条输入该走哪条路"，再分派给对应的下游处理器。

学完这节课，你能掌握：

1. **路由的本质**：用轻量决策层（分类器/规则/LLM）把输入映射到某个处理分支。
2. **三种路由实现**：规则路由、ML/LLM 分类器路由、语义路由（embedding 相似度）。
3. **在 Java 里落地**：Spring AI 的 `Router` 思路、LangChain4j 的 `Classifier`、以及不依赖框架的 `Map<String, Handler>` 写法。
4. **路由失败怎么办**：置信度阈值、兜底分支、人工接管。
5. **企业场景**：智能客服的多技能路由、工单系统按业务域分派、网关层的 Agent 流量分发（Higress）。

<callout emoji="💡"><p>招聘映射：JD 里常见"设计多 Agent 路由/调度""理解 Supervisor 与 Router 模式"。路由是"一个入口、多个专家"架构的第一道闸门，面试能讲清"分类器选型 + 置信度兜底"的人，基本都能过架构初面。</p></callout>

---

## 一、为什么需要路由

```
无路由（一链通吃）：
   所有输入 ─▶ 同一个大模型 ─▶ 输出
   问题：退款问题被技术专家模型答、技术 bug 被退款话术答，统统翻车

有路由（分而治之）：
   输入 ─▶ [路由决策] ─┬─ 退款类 ─▶ 退款 Agent
                      ├─ 技术类 ─▶ 技术 Agent(RAG)
                      ├─ 投诉类 ─▶ 投诉安抚 Agent
                      └─ 未知类 ─▶ 兜底/人工
```

路由解决的是**"让专业的人干专业的事"**。好处：

- 每个分支可以用**最适合的模型/提示/知识库**，质量和成本都更优。
- 分支可以**独立迭代**，改退款逻辑不影响技术分支。
- 入口统一，对调用方透明。

---

## 二、路由的三种实现方式

```
方式            决策依据              优点                 缺点
──────────────────────────────────────────────────────────────
规则路由        关键词/正则/字段       极快、零成本、可解释  覆盖不全、怕长尾
分类器路由      LLM/小模型分类        语义理解强、易扩展    多一次调用开销
语义路由       embedding 余弦相似     无需标注、长尾友好    需向量库、阈值难调
```

<callout emoji="✅"><p>判断框架：先上规则路由覆盖 80% 高频意图；规则命中不了的再丢给 LLM 分类器；长尾语义模糊的用 embedding 相似度兜底。三层叠加是工程里的标配。</p></callout>

---

## 三、规则路由（Java 裸写）

最快、最稳、零成本。适合意图边界清晰的场景（如指令里带明确关键词）。

```java
// ch09-patterns/src/main/java/demo/routing/RuleRouter.java
package demo.routing;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class RuleRouter {

    record Route(String name, Function<String, String> handler) {}

    private final List<Route> routes = new java.util.ArrayList<>();
    private Function<String, String> fallback = (s) -> "抱歉，我不太懂，转人工";

    public RuleRouter when(List<String> keywords, String name, Function<String, String> h) {
        routes.add(new Route(name, (input) -> {
            for (String k : keywords) {
                if (input.contains(k)) return "[" + name + "] " + h.apply(input);
            }
            return null; // 不匹配
        }));
        return this;
    }

    public RuleRouter fallback(Function<String, String> f) { this.fallback = f; return this; }

    public String route(String input) {
        for (Route r : routes) {
            String out = r.handler().apply(input);
            if (out != null) return out;
        }
        return fallback.apply(input);
    }

    public static void main(String[] args) {
        RuleRouter r = new RuleRouter();
        r.when(List.of("退款", "退货"), "refund", s -> "已为您发起退款流程")
         .when(List.of("物流", "发货", "快递"), "logistics", s -> "已查询物流，预计明天送达")
         .when(List.of("bug", "报错", "异常"), "tech", s -> "请贴出报错堆栈，技术同学马上看")
         .fallback(s -> "转人工客服");
        System.out.println(r.route("我要退款"));        // [refund] ...
        System.out.println(r.route("APP 一直报错"));    // [tech] ...
        System.out.println(r.route("你好"));            // 转人工客服
    }
}
```

规则路由的本质就是 `List<Route>` 顺序匹配。注意它**不支持多意图**（一句里同时含"退款"和"物流"只会命中第一个），真实场景常配合分类器路由。

---

## 四、LLM 分类器路由（LangChain4j）

用一个小模型做"意图分类"，输出标准化标签，再 `switch` 分派。这是生产里最常用的路由。

```java
// ch09-patterns/src/main/java/demo/routing/LlmRouter.java
package demo.routing;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

import java.util.EnumSet;
import java.util.function.Function;

public class LlmRouter {

    enum Intent { REFUND, LOGISTICS, TECH, COMPLAINT, UNKNOWN }

    interface Classifier {
        @SystemMessage("""
            你是意图分类器。把用户消息分类为以下之一，只输出英文标签：
            REFUND / LOGISTICS / TECH / COMPLAINT / UNKNOWN
            不要解释，不要标点以外的任何内容。
            """)
        Intent classify(@UserMessage String msg);
    }

    public static void main(String[] args) {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini")  // 分类用便宜小模型
                .temperature(0)
                .build();
        Classifier clf = AiServices.create(Classifier.class, model);

        // 路由表：意图 -> 处理器
        java.util.Map<Intent, Function<String, String>> handlers = java.util.Map.of(
                Intent.REFUND, s -> "执行退款工作流",
                Intent.LOGISTICS, s -> "查询物流工作流",
                Intent.TECH, s -> "转技术 RAG Agent",
                Intent.COMPLAINT, s -> "启动投诉安抚流程",
                Intent.UNKNOWN, s -> "兜底：转人工"
        );

        String[] inputs = {"我要退款", "快递到哪了", "APP 崩溃了", "你们服务太差了"};
        for (String in : inputs) {
            Intent it = clf.classify(in);
            String out = handlers.getOrDefault(it, handlers.get(Intent.UNKNOWN)).apply(in);
            System.out.println(in + " => " + it + " => " + out);
        }
    }
}
```

这里分类器用 `gpt-4o-mini` + `temperature=0`，单次调用成本极低。它只干"贴标签"一件事，下游才用强模型处理。这就是路由带来的**成本与质量双赢**。

<callout emoji="⚠️"><p>分类器必须约束输出格式（只输出枚举标签），否则下游 `switch` 会匹配不到。用枚举 + 强系统提示是最稳的。</p></callout>

---

## 五、语义路由（Embedding 相似度）

当意图边界模糊、长尾多时，用 embedding 把"输入"和"各分支的代表语料"做余弦相似度，取最高分分支。

```java
// ch09-patterns/src/main/java/demo/routing/SemanticRouter.java
package demo.routing;

import java.util.List;
import java.util.Map;

public class SemanticRouter {

    // 简化版：真实项目用向量库(OSS/Jina/OpenAI embedding)+余弦相似度
    record Branch(String name, List<String> samples) {}

    private final List<Branch> branches;

    public SemanticRouter(List<Branch> branches) { this.branches = branches; }

    /** 这里用"关键词重合度"模拟语义相似度，真实用 embedding 余弦 */
    public String route(String input) {
        String[] words = input.split("\\s+|,|，");
        double best = -1; String bestName = "fallback";
        for (Branch b : branches) {
            long hit = b.samples().stream().filter(input::contains).count();
            double score = (double) hit / b.samples().size();
            if (score > best) { best = score; bestName = b.name(); }
        }
        return bestName + "(score=" + String.format("%.2f", best) + ")";
    }

    public static void main(String[] args) {
        List<Branch> bs = List.of(
            new Branch("退款", List.of("退款", "退货", "钱")),
            new Branch("物流", List.of("物流", "快递", "发货", "到哪")),
            new Branch("技术", List.of("报错", "bug", "崩溃", "异常"))
        );
        SemanticRouter r = new SemanticRouter(bs);
        System.out.println(r.route("我的钱什么时候退"));   // 退款
        System.out.println(r.route("货到哪了"));           // 物流
    }
}
```

真实语义路由代码（Spring AI 的 `QuestionAnswerAdvisor` 或专门 `Router`）会调用 embedding 模型并把分支样本预计算成向量存库。流程：

```
输入 ─▶ embed ─▶ 与各分支中心向量算余弦 ─▶ 超阈值取最高分分支，否则 fallback
```

<callout emoji="💡"><p>语义路由非常适合"知识库路由"——用户问 A 业务就查 A 库，问 B 业务查 B 库（呼应第七章 lesson08 多知识库路由）。路由到正确的知识库，RAG 质量直接翻倍。</p></callout>

---

## 六、路由 + 下游 Agent（Supervisor 雏形）

路由不只是分派到一个函数，更常见是分派到一个**完整 Agent**（带工具、带记忆）。这是第四章 Supervisor 模式的简化版。

```java
// ch09-patterns/src/main/java/demo/routing/AgentRouter.java
package demo.routing;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;

public class AgentRouter {

    // 退款 Agent：有查订单工具
    static class RefundAgent {
        interface A {
            @SystemMessage("你是退款专员，可调用工具查询订单")
            String chat(String u);
        }
        @Tool("查询订单状态")
        String queryOrder(String id) { return "订单" + id + "已退款"; }

        static A build(ChatLanguageModel m) {
            return AiServices.create(A.class, m,
                s -> s.chatMemory(MessageWindowChatMemory.withMaxMessages(10)));
        }
    }

    // 技术 Agent：接 RAG（示意）
    static class TechAgent {
        interface A { @SystemMessage("你是技术支持，用知识库回答") String chat(String u); }
        static A build(ChatLanguageModel m) { return AiServices.create(A.class, m); }
    }

    public static void main(String[] args) {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY")).modelName("gpt-4o").build();
        var refund = RefundAgent.build(model);
        var tech = TechAgent.build(model);

        // 路由决策（这里简化，真实用第四节的分类器）
        String intent = "REFUND";
        String userMsg = "查一下订单 A100 的退款";
        String ans = switch (intent) {
            case "REFUND" -> refund.chat(userMsg);
            case "TECH" -> tech.chat(userMsg);
            default -> "转人工";
        };
        System.out.println(ans);
    }
}
```

---

## 七、置信度兜底与人工接管

路由最怕"分错"。工程上必须给分类器加**置信度**和**兜底分支**。

```java
// ch09-patterns/src/main/java/demo/routing/ConfidenceRouter.java
package demo.routing;

import java.util.List;

public class ConfidenceRouter {

    record Scored(String intent, double score) {}

    /** 返回各意图得分，调用方决定：最高分>阈值才采用，否则兜底 */
    static List<Scored> score(String input) {
        // 真实用 LLM 输出概率或多次采样投票；这里演示结构
        return List.of(
            new Scored("REFUND", 0.91),
            new Scored("LOGISTICS", 0.05),
            new Scored("TECH", 0.02),
            new Scored("UNKNOWN", 0.02));
    }

    public static void main(String[] args) {
        double threshold = 0.7;
        List<Scored> scores = score("我要退款");
        Scored top = scores.stream().max(java.util.Comparator.comparingDouble(Scored::score)).get();
        String decision = top.score() >= threshold ? top.intent() : "UNKNOWN(转人工)";
        System.out.println("路由决策=" + decision + " 置信度=" + top.score());
    }
}
```

```
路由决策树：
   分类器输出 top1 意图 + 置信度
        │
        ├─ 置信度 ≥ 阈值 ─▶ 走对应分支
        ├─ 阈值以下但 ≥ 低水位 ─▶ 走分支但标记"低置信"，记录 badcase
        └─ 极低 ─▶ 直接转人工，并落库用于优化分类器
```

<callout emoji="⚠️"><p>千万别让低置信度的请求"盲走"分支——那会比不分路由还糟。兜底转人工永远要在路由表里占一个位置。</p></callout>

---

## 八、企业场景：网关层 Agent 路由（Higress）

在微服务架构里，路由可以前置到**网关**。阿里 Higress + Spring AI Alibaba 支持把不同 `/agent/*` 路径路由到不同 Agent 后端，甚至做 A/B 与灰度。

```yaml
# ch09-patterns/src/main/resources/higress-route.yml
# Higress 网关将不同意图路由到不同 Agent 服务
http:
  - match:
      - uri:
          prefix: /agent/refund
    route:
      - destination:
          host: refund-agent.default.svc.cluster.local
          port: 8080
  - match:
      - uri:
          prefix: /agent/tech
    route:
      - destination:
          host: tech-agent.default.svc.cluster.local
          port: 8080
  - match:
      - uri:
          prefix: /agent/*
    route:
      - destination:
          host: fallback-agent.default.svc.cluster.local
          port: 8080
```

```
                 ┌────────────┐
   用户 ─▶ Higress│ 网关路由层 │──▶ refund-agent
                 │ (意图分流) │──▶ tech-agent (RAG)
                 └────────────┘──▶ fallback-agent (人工/兜底)
```

这把"路由"从应用内逻辑提升为**基础设施能力**，具备限流、熔断、灰度（见第十章第四节）的天然挂载点。

---

## 九、路由与提示链的组合

路由和提示链不是二选一，而是组合：

```
输入 ─▶ [路由] ─┬─ 退款 ─▶ [提示链A: 分类→查单→生成→质检]
                ├─ 技术 ─▶ [提示链B: 改写→检索→重排→生成→引用溯源]
                └─ 投诉 ─▶ [提示链C: 情绪识别→安抚→升级判断]
```

每条分支内部是一条提示链，路由负责"选哪条链"。这就是很多企业 Agent 系统的真实骨架：

- **外层路由**决定业务域（粗粒度）。
- **内层链**决定处理步骤（细粒度）。
- 两层都可以独立配置、独立观测、独立降级。

---

## 十、路由的评估与持续优化

路由不是配完就完事，它是 Agent 质量的"第一道闸门"——分错了，后面再强也白搭。所以要像模型一样**持续评估**。

```
路由评估指标：
   准确率(Accuracy)   ─ 分类对了几成
   混淆矩阵          ─ 哪些意图容易互相误判(如 退款↔物流)
   兜底率            ─ 转人工占比, 太高说明分类器覆盖不足
   置信度分布        ─ 低置信请求占比, 反映边界模糊程度
```

```java
// ch09-patterns/src/main/java/demo/routing/RouterEval.java
package demo.routing;

import java.util.List;
import java.util.Map;

public class RouterEval {

    // 评估集: 输入 + 标准意图
    record Case(String input, String goldIntent) {}

    static double accuracy(List<Case> cases, java.util.function.Function<String, String> classify) {
        long correct = cases.stream()
                .filter(c -> classify.apply(c.input).equals(c.goldIntent))
                .count();
        return (double) correct / cases.size();
    }

    public static void main(String[] args) {
        List<Case> evalSet = List.of(
            new Case("我要退款", "REFUND"),
            new Case("快递到哪了", "LOGISTICS"),
            new Case("APP崩溃了", "TECH"));
        double acc = accuracy(evalSet, (in) -> in.contains("退款") ? "REFUND"
                : in.contains("快递") ? "LOGISTICS" : "TECH");
        System.out.println("路由准确率=" + String.format("%.2f", acc));
    }
}
```

```
优化闭环：
   评估集跑分类器 → 看混淆矩阵(退款↔物流常混)
   → 给分类 prompt 加"退款必有钱相关, 物流必有快递/发货"区分规则
   → Prompt as Code 提交(第十章第三节) → 重新评估 → 准确率提升
```

<callout emoji="💡"><p>路由的评估集要"长尾覆盖"：别只放典型句子，要把真实日志里那些"说不清意图"的难样本也收进去。这些难样本才是路由准确率的天花板。</p></callout>

---

## 十一、路由在网关与 Agent 内部的双层部署

```
双层路由架构(企业常见)：
   外层(网关 Higress):  粗粒度按 /agent/{domain} 分流到不同 Agent 服务
   内层(Agent 内):      细粒度按意图选链/选工具

   例:
      /agent/refund ─▶ 退款 Agent ─▶ 内层再分: 未发货退款 / 已收货退款
      /agent/tech   ─▶ 技术 Agent ─▶ 内层再分: 前端 bug / 后端异常
```

外层路由解决"扩缩容与隔离"（退款服务挂了不影响技术），内层路由解决"处理策略差异"。两层职责不同，别混在一起。

---

## 小结

- 路由 = 用轻量决策层把输入映射到处理分支，实现"专业的人干专业的事"。
- 三种实现：规则路由（快/零成本）、LLM 分类器（语义强）、语义路由（长尾友好），生产里三层叠加。
- 分类器必须约束输出格式并带**置信度阈值 + 兜底分支**，低置信请求转人工。
- 路由下游可以是一个完整 Agent，也是 Supervisor 模式的雏形；网关层（Higress）可把路由做成基础设施。
- 路由选分支、链走步骤，二者组合是企业 Agent 的通用骨架。

## 延伸阅读 / 引用文档

1. Anthropic《Building Effective Agents》Routing 模式：https://www.anthropic.com/research/building-effective-agents
2. Spring AI Alibaba 文档（Higress 集成）：https://java2ai.com/docs/overview
3. LangChain4j 文档（AiServices / Tool）：https://docs.langchain4j.dev
4. Higress 官方文档：https://higress.cn/
5. OpenAI 路由与分类器指南：https://platform.openai.com/docs/guides/prompt-engineering
6. Google ADK for Java（Routing 概念）：https://google.github.io/adk-docs/

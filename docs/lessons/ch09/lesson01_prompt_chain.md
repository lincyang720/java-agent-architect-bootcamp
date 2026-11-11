# 第九章第一节：提示链（Prompt Chain）：结构化提示词的序列执行与数据传递

大家好，我是秋天。

很多同学一上来写 Agent，就把所有要求塞进一个超级 prompt："你是某某专家，请读这个文档、做摘要、提取要素、生成 SQL、再写一封邮件"。结果模型经常顾头不顾尾——要么漏步骤，要么中间一步错了后面全崩。这节课我们讲一个最朴素、但工程里用得最凶的模式：**提示链（Prompt Chain）**。

学完这节课，你能掌握：

1. **提示链的本质**：把一个复杂任务拆成若干"单职责"子任务，用结构化提示词串成流水线，上一步的输出是下一步的输入。
2. **三种串法**：线性串行、带分支的链、可回退的校验链。
3. **数据怎么在链里传递**：纯文本、JSON 契约、对象映射，以及什么时候该用哪种。
4. **在 Java 里怎么落地**：用 LangChain4j 的 `Chain`、Spring AI 的 `ChatClient` 串联，以及不依赖框架的"裸写"实现。
5. **企业场景**：客服工单的"分类→摘要→回复→质检"四段链、对账文件的"解析→比对→差异说明→工单"链。

<callout emoji="💡"><p>招聘映射：JD 里常写"熟悉 Agent 工作流编排""能把复杂任务拆解为可组合的步骤"。提示链是工作流编排的最小单元，面试时能画出"任务拆分 + 数据契约 + 失败回退"三件套，就已经比只会调一个 chat 接口的人强一个段位。</p></callout>

---

## 一、为什么一个超长 prompt 不行

先讲清楚"为什么这样设计"，别上来就抄代码。LLM 的本质是一个"记忆有限、容易走神"的推理器。你塞得越多，它越容易：

```
一个超长 prompt 的风险：
┌─────────────────────────────────────────────┐
│ 你：读文档+摘要+抽实体+写SQL+发邮件，一步到位 │
│ 模型：做了前两步，第三步开始丢字段……          │
│ 结果：输出格式混乱，且你无法定位"哪步错了"    │
└─────────────────────────────────────────────┘
```

提示链的核心思想：**每一步只干一件事，并且把"这一步的产出"变成"下一步能校验的输入"**。这样三件事同时解决：

- **可控**：每一步都有明确产出，错了能单独重试那一步。
- **可观测**：哪一步慢、哪一步失败，链路清清楚楚（第十章会接 OpenTelemetry）。
- **可替换**：某一步换个模型/换个 prompt 不影响其他步。

```
提示链 vs 单 prompt：
单 prompt：  [ 大杂烩 ] ──▶ 输出（黑盒，难调）
提示链：     [A]──▶[B]──▶[C]──▶[D]  每步可测、可重试、可替换
```

---

## 二、提示链的最小骨架（Java 裸写版）

很多公司还没上框架，或者链很轻。先用"不依赖任何框架"的方式写一个链，让你看清数据怎么流。

```java
// ch09-patterns/src/main/java/demo/chain/RawPromptChain.java
package demo.chain;

import java.util.function.Function;

/**
 * 极简提示链：每个 Step 是一个 Function<String,String>，
 * 上一步的输出字符串作为下一步的输入。
 */
public class RawPromptChain {

    public record Step(String name, Function<String, String> action) {}

    private final java.util.List<Step> steps = new java.util.ArrayList<>();

    public RawPromptChain add(String name, Function<String, String> action) {
        steps.add(new Step(name, action));
        return this;
    }

    public String run(String input) {
        String acc = input;
        for (Step s : steps) {
            long t0 = System.nanoTime();
            acc = s.action().apply(acc);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.printf("[chain] step=%s 耗时=%dms%n", s.name(), ms);
        }
        return acc;
    }

    public static void main(String[] args) {
        RawPromptChain chain = new RawPromptChain();
        // 第一步：摘要
        chain.add("summarize", text -> "【摘要】" + text.substring(0, Math.min(20, text.length())) + "...");
        // 第二步：提取关键词
        chain.add("keywords", text -> text + " 关键词: Agent, 提示链");
        // 第三步：生成回复
        chain.add("reply", text -> text + " => 建议人工复核");

        String out = chain.run("用户投诉订单未退款，已等待三天");
        System.out.println(out);
    }
}
```

这个骨架本身不调模型，但它把"链式执行 + 耗时埋点"的结构搭好了。真实项目里，把 `Function` 换成"调用 ChatModel 并解析输出"即可。

<callout emoji="✅"><p>判断框架：链里的每一步应该是"单职责 + 可重放"。如果你发现某一步既做摘要又做决策，说明该拆了。</p></callout>

---

## 三、用 LangChain4j 的 Chain 实现

LangChain4j 自带 `Chain` 抽象（`Chain<T, R>`），并提供了 `Map`/`Reduce` 等组合。下面我们用 `AiServices` + 手动串联实现"客服四段链"。

```java
// ch09-patterns/src/main/java/demo/chain/CustomerServiceChain.java
package demo.chain;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public class CustomerServiceChain {

    interface Classifier {
        @SystemMessage("你是工单分类器，只输出一个词：退款/物流/质量/其他")
        String classify(String ticket);
    }

    interface Summarizer {
        @SystemMessage("用一句话摘要下面的工单，不超过30字")
        String summarize(String ticket);
    }

    interface Replier {
        @SystemMessage("你是客服，针对【分类:{{category}}】的工单生成礼貌回复")
        String reply(@UserMessage String summary, String category);
    }

    interface Qa {
        @SystemMessage("你是质检，判断回复是否含'人工客服'兜底入口，输出 PASS/FAIL+原因")
        String check(String reply);
    }

    public static void main(String[] args) {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini")
                .build();

        Classifier classifier = AiServices.create(Classifier.class, model);
        Summarizer summarizer = AiServices.create(Summarizer.class, model);
        Replier replier = AiServices.create(Replier.class, model);
        Qa qa = AiServices.create(Qa.class, model);

        String ticket = "我买的鞋一周了还没发货，要求退款并投诉";
        String category = classifier.classify(ticket);   // 物流/退款
        String summary = summarizer.summarize(ticket);    // 一句话摘要
        String reply = replier.reply(summary, category);  // 针对性回复
        String qaResult = qa.check(reply);                // 质检

        System.out.println("分类=" + category);
        System.out.println("摘要=" + summary);
        System.out.println("回复=" + reply);
        System.out.println("质检=" + qaResult);
    }
}
```

这里每一步都是一次独立模型调用，但**数据通过字符串显式传递**。注意 `Replier.reply` 用模板把 `category` 注入系统提示——这就是"上一步的输出成为下一步的输入"的标准写法。

<callout emoji="⚠️"><p>真实项目里不要把 API Key 写死，一律走环境变量。上面的 `System.getenv("OPENAI_API_KEY")` 是规范写法。</p></callout>

---

## 四、用 Spring AI 的 ChatClient 串联

Spring AI 没有叫 "Chain" 的类，但 `ChatClient` 的流式 API 天然适合串步骤。下面用 Spring Boot 3.3 + Spring AI 1.x 写一个"对账差异说明链"。

```java
// ch09-patterns/src/main/java/demo/chain/ReconcileChain.java
package demo.chain;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
public class ReconcileChain {

    private final ChatClient client;

    public ReconcileChain(ChatClient.Builder builder) {
        this.client = builder.build();
    }

    /** 第一步：解析对账文件，输出结构化差异 */
    public String parseDiff(String raw) {
        return client.prompt()
                .system("你是财务对账专家，提取两系统金额差异，输出 JSON")
                .user(raw)
                .call()
                .content();
    }

    /** 第二步：把差异 JSON 转成业务可懂的说明 */
    public String explain(String diffJson) {
        return client.prompt()
                .system("把下列对账差异 JSON 翻译成一段给业务看的说明，含可能原因")
                .user(diffJson)
                .call()
                .content();
    }

    /** 第三步：生成工单标题与优先级 */
    public String toTicket(String explanation) {
        return client.prompt()
                .system("根据说明生成工单标题(一行)和优先级(高/中/低)，用 --- 分隔")
                .user(explanation)
                .call()
                .content();
    }

    public String run(String raw) {
        String diff = parseDiff(raw);
        String explanation = explain(diff);
        return toTicket(explanation);
    }
}
```

对应的 `application.yml`：

```yaml
# ch09-patterns/src/main/resources/application.yml
spring:
  application:
    name: ch09-patterns
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: gpt-4o-mini
          temperature: 0
```

`temperature: 0` 在链式场景很重要——链里每一步都要**确定性**，否则上一步的随机性会放大到后面。

---

## 五、数据契约：别用纯文本，用 JSON

很多同学用纯文本在链里传数据，结果下一步要"从一段话里抠字段"，极易解析失败。工程上的正解是：**链内每一步之间用 JSON 契约**。

```java
// ch09-patterns/src/main/java/demo/chain/JsonContract.java
package demo.chain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

public class JsonContract {

    public record DiffItem(
            @JsonProperty("order_id") String orderId,
            @JsonProperty("expected") java.math.BigDecimal expected,
            @JsonProperty("actual") java.math.BigDecimal actual,
            @JsonProperty("gap") java.math.BigDecimal gap) {}

    public static void main(String[] args) throws Exception {
        ObjectMapper om = new ObjectMapper();
        // 上一步（解析）产出结构化对象
        DiffItem item = new DiffItem("A1001",
                new java.math.BigDecimal("100.00"),
                new java.math.BigDecimal("90.00"),
                new java.math.BigDecimal("10.00"));
        String json = om.writeValueAsString(item);   // 作为下一步输入

        // 下一步直接用对象消费，无需文本解析
        DiffItem parsed = om.readValue(json, DiffItem.class);
        System.out.println("差异订单=" + parsed.orderId()
                + " 缺口=" + parsed.gap());
    }
}
```

<callout emoji="💡"><p>经验法则：链内"机器到机器"的步骤用 JSON/对象；只有"最后面向人"的那一步（生成回复/邮件）才转成自然语言。这样整条链既稳又可测。</p></callout>

Spring AI 的"结构化输出"能力（第七章/第三章已讲）可以一步到位让模型直接吐 POJO，强烈建议配合提示链使用。

---

## 六、带分支与回退的链

线性链最常见，但真实业务里常有分支与重试。比如"质检 FAIL 就回退重生成"。

```
          ┌─ PASS ─▶ 输出
输入─▶生成─┤
          └─ FAIL ─▶ 带"批评"重生成（最多 N 次）─▶ 再质检
```

```java
// ch09-patterns/src/main/java/demo/chain/RetryChain.java
package demo.chain;

import java.util.function.Function;
import java.util.function.Predicate;

public class RetryChain {

    /** 生成函数：输入文本+可选批评，产出结果 */
    private final Function<String, String> generator;
    /** 校验函数：返回是否通过 */
    private final Predicate<String> validator;
    private final int maxRetry;

    public RetryChain(Function<String, String> generator,
                      Predicate<String> validator, int maxRetry) {
        this.generator = generator;
        this.validator = validator;
        this.maxRetry = maxRetry;
    }

    public String run(String input) {
        String criticism = "";
        String result = generator.apply(input + criticism);
        int attempt = 0;
        while (!validator.test(result) && attempt < maxRetry) {
            criticism = "\n[上一次未通过校验，请修正以下问题]" + result;
            result = generator.apply(input + criticism);
            attempt++;
        }
        return result + "\n(尝试次数=" + (attempt + 1) + ")";
    }

    public static void main(String[] args) {
        // 演示：生成必须含"人工客服"四字，否则重来
        RetryChain chain = new RetryChain(
                (in) -> in.contains("人工客服") ? in : in + " 自动回复完毕",
                (out) -> out.contains("人工客服"),
                3);
        System.out.println(chain.run("您好，建议您联系人工客服处理。"));
    }
}
```

这个"生成-校验-回退"的循环，其实是第九章第五节"评估-优化"的雏形。提示链 + 回退 = 最简单的自我纠错。

---

## 七、企业场景：客服工单四段链

把前面串起来，一个真实客服系统的链长这样：

```
用户消息
   │
   ▼
[1 分类]  退款/物流/质量/其他
   │
   ▼
[2 摘要]  一句话 + 关键实体(订单号/金额)
   │
   ▼
[3 策略路由]  简单→标准话术；复杂→调用知识库(RAG,见第七章)
   │
   ▼
[4 生成回复]
   │
   ▼
[5 质检]  含兜底入口? 情绪是否安抚? → FAIL 回退
   │
   ▼
回复用户 + 写工单库
```

为什么拆这么细？因为每一步都能**单独配模型**：分类用便宜的 `gpt-4o-mini`，生成回复用强的 `gpt-4o`，质检再用小模型。这就是第十章"成本治理"里"模型路由"的由来——链给了你"按步选模型"的天然抓手。

```yaml
# ch09-patterns/src/main/resources/chain-config.yml
chain:
  customer-service:
    steps:
      - name: classify
        model: gpt-4o-mini
        system: "你是工单分类器，只输出一个词"
      - name: summarize
        model: gpt-4o-mini
        system: "一句话摘要工单"
      - name: generate
        model: gpt-4o
        system: "生成礼貌且专业的客服回复"
      - name: qa
        model: gpt-4o-mini
        system: "质检：是否含人工兜底入口"
    retry:
      max: 2
```

把链配置外置成 YAML，是"提示词即代码（Prompt as Code，第十章第三节）"的前置实践——链结构也能进 Git 版本管理。

---

## 八、提示链的常见坑

1. **错误累积**：上一步的小错被下一步放大。对策：每一步加校验（第六节），失败即停或回退。
2. **幻觉传播**：链越长，幻觉越可能"被当成事实"传下去。对策：关键字段用工具/RAG 取真实数据，链只做"加工"不做"编造"。
3. **成本随步数线性增长**：N 步就是 N 次调用。对策：能用规则/正则搞定的前置步骤（如抽取订单号）就不要调模型。
4. **串行阻塞**：纯串行链在长任务上 RT 很高。对策：能并行的步骤（如"同时做摘要和分类"）拆出去用第九章第三节的并行化。

<callout emoji="⚠️"><p>记住一个判断框架：当任务满足"可拆、步骤间有数据依赖、单步可独立验证"三条件时，用提示链；否则考虑路由（第二节）或编排-执行（第四节）。</p></callout>

---

## 九、与 Spring AI Alibaba Graph 的衔接

提示链本质上是"有向无环图（DAG）的一条直线"。Spring AI Alibaba 的 `Graph`（第四章已铺垫）可以把这种链声明成图节点：

```java
// ch09-patterns/src/main/java/demo/chain/GraphChain.java
package demo.chain;

import com.alibaba.cloud.ai.graph.Graph;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.node.LlmNode;

public class GraphChain {
    // 示意：把 classify/summarize/generate 声明为三个 Node，
    // 用 edge 串成直线。好处是节点可观测、可持久化(见第十七章 Durable Execution)。
    public static void main(String[] args) throws Exception {
        StateGraph graph = new StateGraph("cs-chain");
        graph.addNode("classify", LlmNode.builder().system("分类器").build());
        graph.addNode("summarize", LlmNode.builder().system("摘要器").build());
        graph.addEdge("classify", "summarize");
        Graph compiled = graph.compile();
        System.out.println("图编译成功，节点数=" + compiled.nodes().size());
    }
}
```

把链升级为图，你就拿到了"可视化、可打断、可重放"的工作流能力——这是从 Demo 到生产的必经之路。

---

## 十、提示链 + RAG：检索增强链（结合第七章）

提示链和 RAG（第七章）是天作之合。很多"生成"步骤前需要先"检索"，把检索结果作为下一步的输入，这就是最常见的"检索增强链"。

```
用户问题
   │
   ▼
[1 改写查询]   把口语化问题改写成检索友好 query
   │
   ▼
[2 向量检索]   从知识库取 TopK 片段(第七章)
   │
   ▼
[3 重排]       对片段重排序, 取最相关(第七章重排序)
   │
   ▼
[4 生成]       把"问题+片段"喂给模型生成带引用的答案
   │
   ▼
[5 引用溯源]   校验答案是否基于检索内容(防幻觉)
```

```java
// ch09-patterns/src/main/java/demo/chain/RagChain.java
package demo.chain;

import java.util.List;

public class RagChain {

    // 简化版：真实检索用 Milvus/pgvector/RedisSearch(第七章)
    interface Retriever {
        List<String> retrieve(String query);
    }

    static String rewrite(String q) {
        // 真实用模型改写; 这里示意: 去掉口语词
        return q.replace("我想知道", "").replace("请问", "").trim();
    }

    static String rerank(List<String> chunks) {
        // 真实用 Rerank 模型; 这里示意取前2
        return String.join("\n", chunks.stream().limit(2).toList());
    }

    static String generate(String q, String context) {
        // 真实调模型, 要求"仅基于 context 回答并标注[来源]"
        return "根据资料: " + context.substring(0, Math.min(20, context.length()))
                + " [来源:doc1]";
    }

    public static void main(String[] args) {
        Retriever retriever = (q) -> List.of("退款政策:3个工作日", "运费规则:买家承担");
        String q = "我想知道退款要多久";
        String rq = rewrite(q);
        List<String> hits = retriever.retrieve(rq);
        String ctx = rerank(hits);
        String ans = generate(q, ctx);
        System.out.println(ans);
    }
}
```

<callout emoji="💡"><p>这条"改写→检索→重排→生成→溯源"链是 RAG 系统的标准骨架。每一步都可独立替换检索器/重排模型/生成模型，也是评估-优化（第五节）最容易发力的地方——生成步骤可以加 Evaluator 检查"答案是否真基于 context"。</p></callout>

---

## 十一、提示链的性能与成本基线

上线前必须给链"算账"：N 步就是 N 次模型调用，每步的输入 token = 之前所有步的输出累积。长链容易出现"上下文雪球"。

```
成本陷阱(链越长越明显)：
   步1输出 500tok ─▶ 步2输入含步1(500) + 自身prompt(300)
                    ─▶ 步3输入含步1+步2(900) + 自身(300)
   累积输入 token 指数膨胀 → 后期每步都贵

对策：
   1. 每步只传递"下游真正需要的字段"(用 JSON 契约裁剪, 第五节)
   2. 长上下文步骤用缓存(第十章第六节 prompt caching)
   3. 非必要不回传全量历史, 只回传摘要
```

```yaml
# 链的成本基线配置(对接成本治理)
chain:
  cost-baseline:
    max-total-steps: 6
    max-cumulative-input-tokens: 8000
    cache-stable-prefix: true
```

---

## 小结

- 提示链 = 把复杂任务拆成单职责子步骤，用结构化数据串联，上一步输出是下一步输入。
- 链内传数据优先用 **JSON/对象契约**，只有面向人的最后一步才转自然语言。
- 每一步都应**可独立校验与重试**，错误累积和幻觉传播是两大天敌。
- 链给了你"**按步选模型**"的抓手，是成本治理的天然入口。
- 链是 DAG 的特例，可平滑升级为 Spring AI Alibaba Graph 做可视化与持久化。

## 延伸阅读 / 引用文档

1. LangChain4j 官方文档（Chain 与 AiServices）：https://docs.langchain4j.dev
2. Spring AI 官方文档（ChatClient）：https://docs.spring.io/spring-ai/reference/api/chatclient.html
3. Spring AI Alibaba Graph 文档：https://java2ai.com/docs/graph
4. Anthropic《Building Effective Agents》提示链模式：https://www.anthropic.com/research/building-effective-agents
5. OpenAI Prompt Chaining 指南：https://platform.openai.com/docs/guides/prompt-chaining
6. Google ADK for Java（SequentialAgent 概念）：https://google.github.io/adk-docs/

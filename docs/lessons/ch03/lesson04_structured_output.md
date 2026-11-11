# 第三章第4节：结构化输出（Structured Output）：将 AI 响应映射到 Java POJO

大家好，我是秋天。

前面两节，模型给你的都是 `String`——一段文本。可是在企业系统里，文本能干嘛？你要的是对象：一个 `Order`、`Invoice`、`RiskResult`，能直接塞进数据库、送进下游服务、写进 Kafka。如果每次都拿到一段自然语言，再去正则抠字段，那代码迟早变成一坨没人敢动的字符串解析地狱。

这一节就是来解决这件事：**让大模型直接吐出可映射成 Java POJO 的结构化结果**。学完你能掌握 Spring AI 的三种结构化输出手段，并理解背后的工程取舍。

这节课讲什么、学完掌握什么：

- 讲什么：`ChatClient` 的 `entity()` 映射、`@JsonClassDescription` / `@JsonPropertyDescription` 描述驱动、`ParameterizedTypeReference` 处理泛型集合、以及与裸 JSON 解析的区别。
- 学完掌握：你能把"抽取发票字段""分类客诉并给置信度""批量抽取演员作品"这类任务，直接映射成强类型 Java 对象；你能在面试里讲清楚"为什么不让模型自由输出再由我解析，而是让框架替我约束格式"。

<callout emoji="💡"><p>招聘映射：JD 里常见"能基于 LLM 做信息抽取 / 文档结构化 / 知识图谱填充"。这背后就是结构化输出能力。会这一节，你就能把"非结构化文本"变成"企业数据资产"，这是 Agent 接进 ERP / 财务 / 风控系统的关键一跃。</p></callout>

---

## 一、为什么不能直接解析文本：问题全景

假设你让模型从一段客服录音转写里抽取"客户情绪、问题类型、是否需回访"，朴素做法是：

```java
// ❌ 让模型自由发挥，然后自己正则抠
String text = chatClient.prompt().user("抽取情绪/类型/回访：" + raw).call().content();
// text = "客户情绪：愤怒；问题类型：物流；需回访：是"
// 然后你写正则…… 模型哪天改成 "情绪=愤怒" 你就崩
```

痛点在三处：
1. **格式不稳定**：模型措辞、顺序、标点随时变，正则兜底不全。
2. **类型不安全**：抽出来全是字符串，数字、布尔、枚举得自己转，容易 NPE。
3. **不可校验**：错一个字段你不知道，直到下游炸。

结构化输出的思路是反过来的——**你先定义 POJO，框架把 POJO 的"形状"翻译成 JSON Schema 告诉模型，模型照着填，框架再把结果反序列化回你的对象**。格式约束在"协议层"完成，不靠运气。

```
┌──────────────┐   JSON Schema    ┌──────────────┐
│  你的 POJO    │ ───────────────▶ │   大模型      │
│ (ActorFilms) │                  │ (按 schema 填)│
└──────────────┘ ◀─────────────── └──────────────┘
       ▲               JSON            │
       │           反序列化             ▼
  强类型对象 ◀──── Spring AI ────── 合规 JSON
```

---

## 二、最常用：`entity(Class)` 映射到单个 POJO

Spring AI 在 `ChatClient` 的 `CallResponseSpec` 上提供了 `entity(Class<T>)`（以及响应式 `stream().entity()`）。你给个类，它替你搞定 schema 生成 + 反序列化。

先定义 POJO，加上描述注解（描述越清楚，模型填得越准）：

```java
package com.example.ch03.struct;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("一部电影里某演员出演的作品列表")
public record ActorFilms(

        @JsonPropertyDescription("演员姓名")
        String actor,

        @JsonPropertyDescription("该演员出演的电影名称列表")
        java.util.List<String> movies
) {}
```

调用：

```java
package com.example.ch03.struct;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class FilmService {

    private final ChatClient chatClient;

    public FilmService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public ActorFilms getActorFilms(String actor) {
        return chatClient.prompt()
                .user(u -> u.text("请列出演员 {actor} 出演过的 5 部电影，只输出结构化结果。")
                             .param("actor", actor))
                .call()
                .entity(ActorFilms.class);   // ← 关键：直接映射
    }
}
```

就这么一行 `.entity(ActorFilms.class)`，Spring AI 内部做了三件事：
1. 用 Jackson + 描述注解生成 JSON Schema；
2. 把 schema 作为 `response_format` / 结构化指令发给模型；
3. 把模型返回的 JSON 反序列化成 `ActorFilms`。

<callout emoji="✅"><p>注解价值：`@JsonClassDescription` 和 `@JsonPropertyDescription` 不是装饰，它们会写进 JSON Schema 的 `description` 字段，模型读得到。描述写得具体（比如"该演员出演的电影名称列表"而非"电影"），抽取准确率明显更高。这是提示词工程与结构化输出的交汇点。</p></callout>

---

## 三、泛型集合：`ParameterizedTypeReference`

单个对象好办，但很多时候你要的是**列表**，比如"从这段文本里抽取所有发票""列出所有演员及其作品"。`entity(Class)` 没法表达 `List<X>`，要用 `ParameterizedTypeReference`。

```java
package com.example.ch03.struct;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class BatchFilmService {

    private final ChatClient chatClient;

    public BatchFilmService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public List<ActorFilms> getAll(List<String> actors) {
        String joined = String.join("、", actors);
        return chatClient.prompt()
                .user("请分别列出以下演员的代表作：" + joined)
                .call()
                .entity(new ParameterizedTypeReference<List<ActorFilms>>() {});
    }
}
```

<callout emoji="⚠️"><p>`ParameterizedTypeReference` 必须写成匿名内部类 `new ParameterizedTypeReference<List<ActorFilms>>() {}`，不能直接 `List.class`——后者会丢失泛型信息，反序列化出来是 `List<LinkedHashMap>` 而不是 `List<ActorFilms>`。这是 Jackson 老坑了。</p></callout>

---

## 四、不用 record 也行：普通 class + Jackson

`record` 只是简洁，普通 class 一样能映射，只要你保证有可访问的字段 / getter：

```java
package com.example.ch03.struct;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("客诉分类结果")
public class ComplaintResult {

    @JsonPropertyDescription("分类：物流问题/商品质量/退换货/其他")
    private String category;

    @JsonPropertyDescription("置信度，0到1之间的小数")
    private Double confidence;

    @JsonPropertyDescription("是否需要人工回访")
    private Boolean needCallback;

    // getter / setter 必须有，否则 Jackson 反序列化失败
    public String getCategory() { return category; }
    public void setCategory(String c) { this.category = c; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double c) { this.confidence = c; }
    public Boolean getNeedCallback() { return needCallback; }
    public void setNeedCallback(Boolean n) { this.needCallback = n; }
}
```

```java
ComplaintResult r = chatClient.prompt()
        .user("分类这条客诉：" + complaintText)
        .call()
        .entity(ComplaintResult.class);
System.out.println(r.getCategory() + " / " + r.getConfidence());
```

对企业而言，`ComplaintResult` 拿到后可以直接落库、驱动工单路由——`needCallback=true` 就自动建回访任务。这就是"LLM 输出 → 业务对象 → 自动动作"的闭环。

---

## 五、枚举与约束：让模型只能选你给的值

模型自由填字符串容易出脏值（"物流"写成"物流配送问题"）。用枚举把取值锁死：

```java
package com.example.ch03.struct;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("风险评级结果")
public record RiskRating(

        @JsonPropertyDescription("风险等级，只能取 LOW / MEDIUM / HIGH")
        Level level,

        @JsonPropertyDescription("触发的风险点说明")
        String reason
) {
    public enum Level { LOW, MEDIUM, HIGH }
}
```

模型会被 JSON Schema 里的 `enum: [LOW, MEDIUM, HIGH]` 约束，基本不会瞎填。这比事后 `if (level.equals("物流配送问题"))` 健壮得多。

<callout emoji="💡"><p>工程经验：凡是取值有限的字段，一律上枚举。Schema 里的 `enum` 约束是"前置护栏"，比任何后处理都省心。风控、合规、工单分类这类场景尤其重要。</p></callout>

---

## 六、流式结构化输出

如果结果很长（比如一次抽取 50 张发票），用流式边收边解析，避免前端干等：

```java
package com.example.ch03.struct;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

@Service
public class StreamExtractService {

    private final ChatClient chatClient;

    public StreamExtractService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public Flux<List<Invoice>> streamInvoices(String doc) {
        return chatClient.prompt()
                .user("从以下文档抽取所有发票：" + doc)
                .stream()
                .entity(new org.springframework.core.ParameterizedTypeReference<List<Invoice>>() {});
    }

    public record Invoice(String buyer, Double amount, String date) {}
}
```

<callout emoji="⚠️"><p>注意：流式实体映射依赖模型支持"增量合法 JSON"（SSE + JSON 增量解析）。OpenAI / DashScope 等主流模型支持；个别小模型流式吐 JSON 会解析失败，遇到就退回流式文本 + 末端一次性 `entity()`。</p></callout>

---

## 七、企业场景：发票/合同结构化与客诉自动路由

落到两个真实场景。

**场景 A：财务发票批量录入。** 员工拍一张发票照片，OCR 出文本，LLM 结构化抽取成 `Invoice`，直接进财务系统：

```java
package com.example.ch03.struct;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class InvoiceExtractor {

    private final ChatClient chatClient;

    public InvoiceExtractor(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public Invoice extract(String ocrText) {
        return chatClient.prompt()
                .system("你是发票信息抽取引擎，严格按字段输出，金额转为数字，"
                        + "日期统一为 yyyy-MM-dd。缺失字段填空字符串。")
                .user("抽取发票字段：\n" + ocrText)
                .call()
                .entity(Invoice.class);
    }

    public record Invoice(
            @com.fasterxml.jackson.annotation.JsonPropertyDescription("购买方名称") String buyer,
            @com.fasterxml.jackson.annotation.JsonPropertyDescription("价税合计，数字") Double total,
            @com.fasterxml.jackson.annotation.JsonPropertyDescription("开票日期 yyyy-MM-dd") String date,
            @com.fasterxml.jackson.annotation.JsonPropertyDescription("发票号码") String invoiceNo
    ) {}
}
```

**场景 B：客诉自动路由。** 抽取出 `ComplaintResult` 后，业务代码按 `category` 和 `needCallback` 路由工单：

```java
ComplaintResult r = classifier.classify(rawComplaint);
switch (r.getCategory()) {
    case "物流问题" -> logisticsQueue.offer(r);
    case "退换货"   -> returnQueue.offer(r);
    default         -> generalQueue.offer(r);
}
if (Boolean.TRUE.equals(r.getNeedCallback())) {
    callbackService.schedule(r);   // 高置信度需回访 → 自动建任务
}
```

这一步把"AI 判断"转成了"确定性业务动作"，是 Agent 真正产生业务价值的地方。

<callout emoji="💡"><p>提示：结构化输出 ≠ 完全可靠。模型偶尔仍会漏字段或给非法值。生产里务必加一层校验（用 Jakarta Bean Validation 的 `@NotNull` / `@DecimalMin`，或在实体类里做防御性检查），失败则走重试或降级人工。这是第十章"异常处理与降级"要系统讲的。</p></callout>

---

## 八、与裸 JSON 解析的对比（面试常问）

| 维度 | 手动解析文本 | `entity()` 结构化输出 |
|------|-------------|----------------------|
| 格式稳定性 | 依赖模型措辞，易碎 | JSON Schema 约束，稳定 |
| 类型安全 | 全 String，手动转 | 直接强类型对象 |
| 校验 | 自己写正则/判空 | 可叠加 Bean Validation |
| 维护成本 | 模型一变就改正则 | POJO 一改即变 |
| 适用 | 一次性脚本 | 生产系统 |

结论很明确：生产系统里，结构化输出是默认选项，文本解析是例外。

---

## 十、校验与降级：结构化不等于可靠

lesson04 提过"结构化输出不是 100% 可靠"。生产必须加一层校验。用 Jakarta Bean Validation 在实体上标约束，拿到对象后校验，失败走重试或降级。

```java
package com.example.ch03.struct;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record Invoice(
        @NotBlank String buyer,
        @NotNull @DecimalMin("0.0") Double total,
        @NotBlank String date
) {}

// 调用处校验
import jakarta.validation.Validator;
import jakarta.validation.ConstraintViolation;

public Invoice safeExtract(String ocr, Validator validator, ChatClient client) {
    Invoice inv = client.prompt().user("抽取发票：" + ocr).call().entity(Invoice.class);
    var violations = validator.validate(inv);
    if (!violations.isEmpty()) {
        // 降级：记录 badcase + 转人工，而不是用残缺数据继续
        log.warn("发票结构化校验失败: {}", violations);
        throw new StructuredExtractException("需人工复核");
    }
    return inv;
}
```

<callout emoji="⚠️"><p>关键认知：模型可能返回合法 JSON 但语义错误（如 `total=-5` 金额）。Bean Validation 只能拦"格式/范围"，拦不了"业务语义"。业务级校验（如"退款额不能超过订单额"）要在服务层另写。第十六章评测闭环专门治这类 badcase。</p></callout>

---

## 十一、嵌套与复杂结构：POJO 套 POJO

真实业务对象常常嵌套。Spring AI 的 `entity()` 支持嵌套 POJO 和 `List<POJO>`，只要每层都有描述注解。

```java
package com.example.ch03.struct;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("一份完整的报销单")
public record ExpenseReport(
        @JsonPropertyDescription("申请人姓名") String applicant,
        @JsonPropertyDescription("明细列表") List<LineItem> items,
        @JsonPropertyDescription("合计金额") Double total
) {}

@JsonClassDescription("报销明细项")
public record LineItem(
        @JsonPropertyDescription("项目") String category,
        @JsonPropertyDescription("金额") Double amount,
        @JsonPropertyDescription("发生日期 yyyy-MM-dd") String date
) {}

// 一步映射到嵌套结构
ExpenseReport report = chatClient.prompt()
        .user("从这段报销文本抽取结构化报销单：" + text)
        .call()
        .entity(ExpenseReport.class);
```

模型会根据 schema 的嵌套关系，正确填 `items` 数组里的每个 `LineItem`。这比"让模型吐一个扁平 JSON 再自己拼"强太多。

---

## 十二、实战：结构化输出 + 工具调用（对账）

把 lesson04 和 lesson05 组合——模型先调 `queryRefund` 工具拿真实数据，再按 `ComplaintResult` 结构输出，业务代码直接路由：

```java
package com.example.ch03.struct;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class ReconcileRouter {

    private final ChatClient chatClient;
    private final ReconcileTools tools;

    public ReconcileRouter(ChatClient chatClient, ReconcileTools tools) {
        this.chatClient = chatClient;
        this.tools = tools;
    }

    public void handle(String question) {
        ComplaintResult r = chatClient.prompt()
                .user(question)
                .tools(tools)                 // 先取真实数据
                .call()
                .entity(ComplaintResult.class);  // 再收口成结构
        if (Boolean.TRUE.equals(r.getNeedCallback())) {
            callbackService.schedule(r);     // 直接驱动业务动作
        }
    }
}
```

"工具取数 + POJO 收口 + 业务驱动"就是生产 Agent 的标准范式——模型负责理解和编排，工具负责真实交互，POJO 负责确定性输出。

---

## 十三、面试自测

1. **entity() 底层做了什么？** —— POJO→JSON Schema→发给模型→反序列化回对象，格式约束在协议层。
2. **泛型集合怎么映射？** —— `ParameterizedTypeReference<List<T>>(){}`，不能 `List.class`。
3. **结构化输出就可靠吗？** —— 不，需 Bean Validation + 业务校验 + 降级重试兜底。
4. **嵌套 POJO 支持吗？** —— 支持，每层加描述注解即可。

---

## 十五、结构化输出的边界处理

lesson04 提过校验，这里补"模型输出不合法 JSON"时的边界处理。即使框架要求结构化，模型仍可能：
- 输出带 Markdown 代码围栏（```json ... ```）。
- 输出前后有解释文字。
- 返回空或截断。

处理策略：

```java
public <T> T safeEntity(String raw, Class<T> type) {
    // 1. 去 Markdown 围栏
    String json = raw.replaceAll("```json", "").replaceAll("```", "").trim();
    // 2. 截取首个 { 到末个 }
    int s = json.indexOf('{'), e = json.lastIndexOf('}');
    if (s >= 0 && e > s) json = json.substring(s, e + 1);
    try {
        return objectMapper.readValue(json, type);
    } catch (Exception ex) {
        throw new StructuredExtractException("JSON 解析失败，转人工", ex);
    }
}
```

<callout emoji="⚠️️<p>用 `entity()` 时框架通常已处理这些，但"裸 ChatModel + 自己解析"的场景必须加这段容错。特别是从模型直接拿文本再 parse 时，围栏和解释文字是头号解析错误来源。</p></callout>

---

## 十六、结构化输出与流式的一致性

lesson04 提了流式实体，补充一个一致性问题：流式 JSON 是"增量到达"的，中间态不是合法 JSON。若用 `stream().entity()`，框架需做"增量 JSON 解析"（边收边校验括号匹配），否则会抛异常。

```java
// 流式结构化：框架内部累积 token，完成时一次性反序列化
Flux<List<Invoice>> flux = chatClient.prompt()
        .user("抽取所有发票：" + doc)
        .stream()
        .entity(new ParameterizedTypeReference<List<Invoice>>() {});
// 注意：若模型流式吐 JSON 不规范，框架可能收尾才解析，失去流式体验
```

生产建议：流式结构化仅用于"输出已完整、只需流式展示"的场景；若模型流式吐 JSON 不稳，退回流式文本 + 末端 `entity()` 一次性解析，保稳。

---

## 十七、面试自测补充

1. **模型输出不合法 JSON 怎么办？** —— 去围栏/截取括号/容错解析，失败转人工。
2. **流式结构化注意什么？** —— 增量 JSON 解析不稳时，退回流式文本+末端 entity 保稳。
3. **枚举约束？** —— schema 的 enum 做前置护栏，比后处理稳。
4. **校验链条？** —— Bean Validation + 业务校验 + 降级重试。

---

## 十八、小结

这一节我们把模型的"自然语输出"焊进了 Java 类型系统：

1. 结构化输出核心思路：POJO → JSON Schema → 模型填 → 反序列化回对象，格式约束在协议层完成。
2. 单对象用 `.call().entity(Class)`；泛型集合用 `ParameterizedTypeReference<List<T>>(){}`。
3. 描述注解（`@JsonClassDescription` / `@JsonPropertyDescription`）直接提升抽取准确率，写进 schema 的 `description`。
4. 取值有限的字段用枚举，靠 schema 的 `enum` 做前置护栏。
5. 流式也有 `stream().entity(...)`，长文档抽取优先用。
6. 企业落地：发票抽取进财务、客诉分类驱动工单路由——把"AI 判断"转成"确定性业务动作"。
7. 生产必加校验与降级，结构化输出不是 100% 可靠。

下一节（lesson05）我们让模型不止"说话"，还能"动手"——函数调用与工具集成，让它查数据库、调 API、算账，真正成为能办事的 Agent。

---

## 延伸阅读 / 引用文档

1. Spring AI 结构化输出文档：https://docs.spring.io/spring-ai/reference/api/structured-output.html
2. Spring AI ChatClient entity 方法说明：https://docs.spring.io/spring-ai/reference/api/chatclient.html
3. JSON Schema 规范（模型约束格式依据）：https://json-schema.org/understanding-json-schema/
4. OpenAI Structured Outputs 官方指南：https://platform.openai.com/docs/guides/structured-outputs
5. Jackson 注解参考（@JsonClassDescription 等）：https://github.com/FasterXML/jackson-docs
6. Spring AI Alibaba 结构化输出示例：https://java2ai.com/docs/1.0.0.2/tutorials/quick-start/

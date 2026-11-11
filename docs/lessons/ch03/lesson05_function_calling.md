# 第三章第5节：函数调用与工具集成：声明式工具注册与自动调用

大家好，我是秋天。

前面四节，模型再怎么牛，也只是在"说话"——它不知道今天的天气、查不了你的订单、算不了汇率。一个只能聊天的模型，在企业里价值有限。真正的 Agent，**必须能调用外部工具**：查数据库、调 API、执行代码、发消息。

这一节我们讲 Spring AI 1.0 的"工具调用（Tool Calling）"机制——注意，1.0 把旧版的"Function Calling"统一叫"Tool Calling"了，API 也换了。学完你能让模型自动决定"什么时候该调你的 Java 方法"，并拿到结果继续推理。

这节课讲什么、学完掌握什么：

- 讲什么：Spring AI 1.0 的 `@Tool` / `@ToolParam` 声明式工具、`ChatClient.tools()` 注册、框架自动完成"模型请求→执行 Java 方法→回传结果→继续生成"的闭环、`ToolContext` 注入上下文。
- 学完掌握：你能写一个带业务语义的工具类（查天气、查订单、算汇率），标 `@Tool`，注册给 `ChatClient`，然后让模型在对话中自动调用它；你理解"模型不执行代码，只发调用请求，真正执行的是你的 JVM"这一关键事实。

<callout emoji="💡"><p>招聘映射：几乎所有 Agent 岗位 JD 都写"熟悉 Function Calling / Tool Use / MCP"。这一节是 Agent "动手能力"的地基。讲清楚"声明式注册 + 自动调用 + 上下文注入"，面试直接加分。</p></callout>

---

## 一、先把心智模型摆正：模型不执行你的代码

这是最多人误解的点。工具调用**不是**模型在你服务器上跑了段代码。真实流程是：

```
┌─────────┐   1.带工具schema的请求   ┌─────────┐
│  你的App │ ───────────────────────▶ │  大模型  │
│(JVM)    │                          │         │
│         │   2."请调用 getWeather(北京)" │         │
│         │ ◀─────────────────────── │         │
│         │   3.本地执行 getWeather()  │         │
│         │      → "晴 25℃"           │         │
│         │   4.把结果回传模型          │         │
│         │ ───────────────────────▶ │         │
│         │   5.模型用结果组织回答      │         │
└─────────┘                          └─────────┘
```

关键点：
- 步骤 1：你把"有哪些工具、参数长啥样"以 JSON Schema 形式告诉模型。
- 步骤 2：模型**决定**调哪个、传什么参（它自己判断，你不管）。
- 步骤 3：**你的 JVM 执行**真实的 Java 方法，拿到真实结果。
- 步骤 4/5：结果回传模型，模型组织成自然语言回答。

所以工具调用的安全性核心是：模型只能"请求"，真执行的是你——你完全掌控参数校验、权限、审计。这跟"让模型直接执行 SQL"有本质区别，安全得多。

---

## 二、Spring AI 1.0 的声明式工具：`@Tool`

1.0 之后，旧版的 `@Bean @Description Function` 那套废弃了。现在推荐 `@Tool` 注解在普通方法上，框架自动生成 schema。

先写一个天气工具：

```java
package com.example.ch03.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

@Service
public class WeatherTools {

    @Tool(description = "查询指定城市的当前天气，返回温度和天气状况")
    public String getWeather(
            @ToolParam(description = "城市名称，如 北京、Shanghai") String city) {
        // 真实场景调天气 API；这里用确定性返回演示
        return switch (city) {
            case "北京"   -> "北京：晴，25℃";
            case "上海"   -> "上海：多云，22℃";
            default       -> city + "：天气数据暂不可用";
        };
    }

    @Tool(description = "查询两个城市之间的温度差，单位摄氏度")
    public String temperatureDiff(
            @ToolParam(description = "城市A") String a,
            @ToolParam(description = "城市B") String b) {
        // 仅为演示：真实应解析 getWeather 的结构化结果
        return "计算 " + a + " 与 " + b + " 的温差需要结构化数据，此处略。";
    }
}
```

注意两点：
- `@Tool(description = ...)` 的 description 会被写进 schema，模型**靠它判断要不要调这个工具**。描述要像给同事写接口文档一样清楚。
- `@ToolParam(description = ...)` 描述每个参数，模型靠它知道传什么值、什么格式。

<callout emoji="⚠️"><p>描述写得太模糊是大忌。写过 `@Tool(description = "处理数据")` 的人，模型几乎不会调它。描述要讲清"这个工具干啥、什么时候该用"。这是提示词工程与工具调用的接口——description 就是给模型的"工具说明书"。</p></callout>

---

## 三、把工具注册给 ChatClient

工具写好了，要让 `ChatClient` 知道它的存在。两种方式：

### 方式一：`defaultTools`（全局默认，推荐）

在 Builder 里注册，所有 prompt 自动带上：

```java
package com.example.ch03.tool;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ToolChatConfig {

    @Bean
    public ChatClient toolChatClient(ChatClient.Builder builder,
                                     WeatherTools weatherTools) {
        return builder
                .defaultSystem("你是一个智能助手，能查询天气。需要时主动调用工具。")
                .defaultTools(weatherTools)   // ← 全局注册
                .build();
    }
}
```

### 方式二：每次 `prompt().tools(...)`（单次覆盖）

```java
String ans = chatClient.prompt()
        .user("北京现在天气怎么样？")
        .tools(weatherTools)          // 本次请求带这个工具
        .call()
        .content();
```

你可以传单个对象、多个对象、甚至 Spring Bean 名（用 `ToolCallbackProvider`）。框架会扫描对象里所有 `@Tool` 方法，自动生成 schema。

<callout emoji="✅"><p>经验：通用工具（天气、时间、知识库检索）放 `defaultTools`；业务敏感工具（发短信、改数据库、转账）必须按请求粒度用 `prompt().tools()` 显式传入，并且加权限校验。绝不能把"能改数据的工具"默认暴露给所有对话。</p></callout>

---

## 四、完整可运行示例：对话中自动调用

把上面的拼起来，一个能自动查天气的 REST 接口：

```java
package com.example.ch03.tool;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WeatherController {

    private final ChatClient chatClient;

    public WeatherController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @GetMapping("/weather")
    public String ask(@RequestParam String q) {
        return chatClient.prompt()
                .user(q)
                .call()
                .content();
    }
}
```

调用 `curl "http://localhost:8080/weather?q=北京天气如何"`，你会看到日志里 `getWeather("北京")` 被执行，最终回答是"北京：晴，25℃"这种带真实数据的回答，而不是模型瞎编。

这就是"自动调用"——你**没有写任何 if/else 判断模型要不要查天气**，是模型自己根据对话和工具 schema 决定的。这才是 Agent 的精髓：把"决策"交给模型，"执行"留在你手里。

---

## 五、ToolContext：给工具注入运行时上下文

真实企业场景，工具往往需要"当前用户是谁、当前会话 ID、租户 ID"这些上下文，而模型不可能凭空知道。Spring AI 提供 `ToolContext`，在调用时塞进去：

```java
package com.example.ch03.tool;

import org.springframework.ai.tool.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

@Service
public class OrderTools {

    @Tool(description = "按订单号查询订单状态，仅能查当前用户的订单")
    public String queryOrder(
            @ToolParam(description = "订单号") String orderId,
            ToolContext ctx) {                       // ← 注入上下文

        String userId = (String) ctx.getContext().get("userId");
        // 权限校验：只能查自己的订单
        if (!orderService.belongsTo(orderId, userId)) {
            return "无权查询该订单";
        }
        return orderService.getStatus(orderId);
    }
}
```

调用时传入上下文：

```java
import org.springframework.ai.tool.ToolContext;

Map<String, Object> ctxMap = Map.of("userId", "u_10086");
ToolContext toolContext = new ToolContext(ctxMap);

String ans = chatClient.prompt()
        .user("我的订单 ORD-123 到哪了？")
        .tools(new OrderTools())          // 真实场景应是注入了 userId 感知的 Bean
        .toolContext(toolContext)         // ← 传入运行时上下文
        .call()
        .content();
```

<callout emoji="💡"><p>安全要点：`ToolContext` 是做"多租户隔离"和"权限最小化"的关键。模型传来的 `orderId` 不可信，但你从 `ToolContext` 拿到的 `userId` 是你自己注入的可信值。永远用可信上下文做权限判断，别信模型填的"我是管理员"。</p></callout>

---

## 六、把工具结果接进结构化输出（进阶组合）

工具调用可以和上一节的结构化输出组合：让模型调工具拿到原始数据，再让你定义的对象收口。

```java
package com.example.ch03.tool;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import com.example.ch03.struct.ComplaintResult;  // lesson04 定义的类

@Service
public class SmartRouter {

    private final ChatClient chatClient;

    public SmartRouter(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    // 模型先调 lookupOrder 工具拿数据，再按 ComplaintResult 结构输出
    public ComplaintResult route(String question, OrderTools orderTools) {
        return chatClient.prompt()
                .user(question)
                .tools(orderTools)
                .call()
                .entity(ComplaintResult.class);
    }
}
```

这种"工具取数 + 结构收口"是生产 Agent 的标准范式：工具负责"真实世界交互"，POJO 负责"确定性输出"，模型在中间做"理解+编排"。

---

## 七、企业场景：智能对账中的工具编排

回到"对账助手"。运营问"上个月华东区退款异常订单有哪些"，Agent 需要：调 `queryRefund` 工具拿金额 → 调 `listAbnormalOrders` 工具拿明细 → 综合回答。模型自己编排这两步：

```java
package com.example.ch03.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

@Service
public class ReconcileTools {

    @Tool(description = "按区域和月份查询退款总额，返回金额字符串")
    public String queryRefund(
            @ToolParam(description = "区域，如 华东/华北") String region,
            @ToolParam(description = "月份，如 2026-10") String month) {
        return reconcileDao.refundTotal(region, month); // 查数仓
    }

    @Tool(description = "列出指定区域月份的异常退款订单，返回订单号与异常原因")
    public String listAbnormalOrders(
            @ToolParam(description = "区域") String region,
            @ToolParam(description = "月份") String month) {
        return reconcileDao.abnormalOrders(region, month); // 查规则引擎
    }
}
```

运营一句话，模型自动决定先查总额还是先列明细，甚至多轮交叉验证。这就是"自然语言驱动的财务分析"——传统 BI 要写 SQL，现在运营自己用话就能查。

<callout emoji="⚠️"><p>工具不是越多越好。给模型的工具超过 15~20 个，它会"选择困难"，调用准确率下降，token 也贵。企业里常用"工具路由"（先让用户/分类器选领域，再只暴露该领域 3~5 个工具），这跟 RAG 的"多知识库路由"是同一个思想。第十章工程化会展开。</p></callout>

---

## 八、调试工具调用

想知道模型到底调了什么？打开日志：

```yaml
logging:
  level:
    org.springframework.ai.chat.client: DEBUG
    org.springframework.ai.tool: DEBUG
```

你会看到模型发出的 tool_calls、你方法执行的入参返回值、回传内容。工具不触发时，第一反应检查：① description 是否清晰；② 参数 schema 模型能否理解；③ 是否真的 `tools()` 注册了。

另外，Spring AI 提供 `ToolCallbackProvider` 从多个 Bean 聚合工具，以及 `FunctionToolCallback.builder()` 用函数式方式注册（适合把老 `Function` 迁移过来）。生产里工具多了就用 `ToolCallbackProvider` 统一管理。

---

## 十、聚合多工具：ToolCallbackProvider

工具多了，一个个 `tools(a, b, c)` 传太笨。Spring AI 提供 `ToolCallbackProvider` 从多个 Bean 聚合并统一暴露。

```java
package com.example.ch03.tool;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ToolConfig {

    // 把多个 @Tool 对象聚合成一个 Provider
    @Bean
    public ToolCallbackProvider toolProvider(WeatherTools w, OrderTools o, ReconcileTools r) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(w, o, r)
                .build();
    }
}
```

```java
// 使用时一次性注入全部工具
String ans = chatClient.prompt()
        .user(q)
        .tools(toolCallbackProvider)   // 传入 Provider 即可
        .call()
        .content();
```

<callout emoji="✅"><p>经验：把工具按"领域"分组到不同 Provider（客服工具 Provider、财务工具 Provider），按对话场景只注入相关 Provider，避免"给模型 50 个工具它选择困难"（lesson05 已提过数量上限）。这也是微服务化思想的体现。</p></callout>

---

## 十一、函数式注册：把老 Function 迁进来

如果你有遗留的 `java.util.function.Function`（Spring AI 旧的 Function 风格），用 `FunctionToolCallback` 包装，不必改写：

```java
package com.example.ch03.tool;

import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.context.annotation.Bean;

import java.util.function.Function;

public class LegacyFunction {

    // 老的 Function 实现
    record RateReq(String from, String to) {}
    record RateRes(String from, String to, Double rate) {}

    @Bean
    public FunctionToolCallback<String, String> exchangeRate() {
        Function<RateReq, RateRes> fn = req ->
                new RateRes(req.from(), req.to(), 7.2);
        return FunctionToolCallback.builder("exchangeRate", fn)
                .description("查询两种货币的汇率")
                .inputType(RateReq.class)
                .build();
    }
}
```

`FunctionToolCallback.builder()` 让你把任意 `Function` 声明为工具，平滑迁移旧代码，不必为适配 `@Tool` 而重构。

---

## 十二、工具调用的失败处理与超时

工具是真实代码，会抛异常、会超时。生产里必须兜住：

```java
package com.example.ch03.tool;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class SafeToolCall {

    private final ChatClient chatClient;

    public SafeToolCall(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public String ask(String q) {
        try {
            return chatClient.prompt().user(q).tools(new OrderTools()).call().content();
        } catch (Exception e) {
            // 工具异常/模型超时：降级话术，不抛 500
            return "【系统提示】订单查询暂不可用，请稍后重试或联系客服。";
        }
    }
}
```

另外，工具里若调外部 API，自身也要设超时（如 `RestClient` 的 `connectTimeout`），否则一个慢接口拖垮整个 Agent 调用链。

<callout emoji="⚠️"><p>模型调工具失败时，有的模型会"假装"工具返回了结果继续答——这是幻觉来源之一。生产里要记录每次 tool_call 的真实入参/出参/异常，badcase 复盘时一眼看出是工具挂了还是模型用错了参数。第十二章审计专门讲这个。</p></callout>

---

## 十三、面试自测

1. **模型真的执行我的代码吗？** —— 不，模型只发调用请求，JVM 执行，你掌控安全。
2. **@Tool 的 description 重要吗？** —— 极重要，模型靠它判断何时调用；模糊描述导致不调用或误调用。
3. **ToolContext 干嘛用？** —— 注入可信运行时上下文（userId/租户），做权限最小化与多租户隔离。
4. **工具多了怎么办？** —— 分组 Provider + 按场景注入 + 控制单次数量。

---

## 十五、工具调用的 JSON Schema 生成机制

lesson05 说框架"自动生成 schema"，这里看它怎么生成的——靠方法签名 + 注解反射：

```
@Tool(description="查订单")
public String query(@ToolParam(description="订单号") String orderId)
        ↓ 反射
{
  "name": "query",
  "description": "查订单",
  "parameters": {
    "type": "object",
    "properties": { "orderId": { "type":"string", "description":"订单号" } },
    "required": ["orderId"]
  }
}
```

模型拿到的就是这个 schema，据此决定调不调、传什么。所以 **`@Tool`/`@ToolParam` 的描述质量 = schema 质量 = 模型调用准确率**。改描述就是在改模型看到的"工具说明书"。

<callout emoji="💡"><p>调试技巧：把生成的 JSON Schema 打印出来看一眼。若 description 是中文乱码或为空，模型就瞎调。很多"工具不调用"的 bug，根因是 schema 描述缺失——直接在 `MethodToolCallbackProvider` 构建时检查。</p></callout>

---

## 十六、工具调用的流式与并行

两个进阶点：

1. **并行工具调用**：新模型支持一次返回多个 tool_call，框架并行执行再汇总。适合"一次要查多个独立信息"（如同时查天气和汇率）。Spring AI 1.0 的 ChatClient 已支持多 tool_call 并行执行。
2. **流式工具**：工具本身可流式返回（如代码执行进度），通过 `ChatClient` 的流式链路透传。

```java
// 并行工具：模型一次请求多个 tool_call，框架自动并行执行后回传
String ans = chatClient.prompt()
        .user("北京天气和美元汇率各是多少？")   // 触发两个工具并行
        .tools(weatherTools, fxTools)
        .call()
        .content();
```

<callout emoji="⚠️️<p>并行工具调用要注意"副作用叠加"：两个并行工具若都写同一外部系统，可能产生竞态。只读工具（查天气/查汇率）适合并行；写操作（发短信/改数据）要串行或加锁。这是分布式系统老问题在 Agent 里的新表现。</p></callout>

---

## 十七、企业案例：对账助手的工具编排复盘

lesson05 的对账助手，真实落地时工具编排是这样设计的：

```
用户:"上月华东退款异常有哪些"
  → 模型调 queryRefund(华东, 2026-10) 拿总额
  → 模型调 listAbnormalOrders(华东, 2026-10) 拿明细
  → 模型综合两类真实数据，结构化输出异常清单
```

关键设计：① 工具只负责"取真实数据"，不负责"判断"；② 判断交给模型基于真实数据做（避免工具里写死业务逻辑）；③ 输出用 `entity()` 收口成 `AbnormalReport` POJO，直接进风控系统。这种"工具取数 + 模型判断 + POJO 收口"范式，是对账类场景的标准答案。

---

## 十八、小结

这一节模型从"只会说"变成"能动手"：

1. 核心事实：模型**不执行**你的代码，只发"调用请求"，真正执行在你 JVM——你掌控安全与权限。
2. Spring AI 1.0 用 `@Tool` + `@ToolParam` 声明式定义工具，框架自动生成 JSON Schema。
3. 注册靠 `defaultTools()`（全局）或 `prompt().tools()`（单次）；描述写清"何时用、参数含义"是调用准确率的关键。
4. `ToolContext` 注入可信运行时上下文（userId/租户），做权限最小化与多租户隔离。
5. 工具调用 × 结构化输出 = 生产标准范式：工具取数、POJO 收口、模型编排。
6. 企业落地：对账助手自动编排多工具查询；工具数量要控制，避免"选择困难"。

至此，Spring AI 单智能体的四大支柱——ChatClient、提示词、结构化输出、工具调用——你都拿到了。下一节（lesson06）我们升级到 Spring AI Alibaba，看它如何用 **Graph** 把这些能力编排成工作流，并接入通义千问 DashScope。

---

## 延伸阅读 / 引用文档

1. Spring AI 工具调用（Tool Calling）官方文档：https://docs.spring.io/spring-ai/reference/api/tools.html
2. Spring AI 1.0 M6 工具机制发布说明：https://spring.io/blog/2025/02/14/spring-ai-1-0-0-m6-released
3. Spring AI `@Tool` 注解参考：https://docs.spring.io/spring-ai/reference/api/tools/tool-annotation.html
4. Spring AI Alibaba 工具调用示例：https://java2ai.com/docs/1.0.0.2/tutorials/tools/
5. OpenAI Function Calling 指南：https://platform.openai.com/docs/guides/function-calling
6. Anthropic Tool Use 文档：https://docs.anthropic.com/en/docs/build-with-claude/tool-use

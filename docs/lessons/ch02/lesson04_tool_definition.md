# 第2章第4节：Tool（工具）的定义与注册：函数调用（Function Calling）的底层实现

大家好，我是秋天。

上一节课我们讲了 Chain 机制，知道了如何把多个处理步骤串联成管道。

这节课，我们深入 LangChain4j 里最关键的能力之一：**Tool（工具）的定义与注册机制，以及 Function Calling 在底层是如何实现的。**

工具是 Agent 连接外部世界的桥梁。没有工具，模型只能在语言空间里打转；有了工具，Agent 才能真正执行动作、获取实时数据、操作外部系统。

学完这节课，你会掌握：

1. Function Calling 的底层原理
2. LangChain4j 中工具的定义方式（@Tool、@P 注解）
3. 工具的注册与绑定机制
4. 工具执行的完整生命周期
5. 复杂工具场景的处理：并行调用、嵌套调用、异常处理
6. 工具设计的最佳实践

---

## 一、Function Calling 的底层原理

在讲 LangChain4j 的工具 API 之前，我们先把底层原理搞清楚。

### 1. 模型本身不能执行代码

大语言模型本质上是一个文本生成系统，它不能直接调用函数、查数据库、发 HTTP 请求。

那工具调用是怎么实现的？

答案是：**模型生成一段"我想调用这个函数，参数是这些"的结构化文本，由框架解析并执行，再把结果返回给模型。**

### 2. Function Calling 的完整流程

```Plain Text
① 开发者把工具描述（函数名、参数、功能说明）注入到请求里
        ↓
② 模型分析用户意图，决定是否需要调用工具
        ↓
③ 如果需要，模型返回一个结构化的"工具调用请求"（不是最终回复）
        ↓
④ 框架解析工具调用请求，找到对应的 Java 方法并执行
        ↓
⑤ 把执行结果作为 ToolResultMessage 返回给模型
        ↓
⑥ 模型根据工具结果生成最终回复
        ↓
⑦ 如果模型认为还需要更多工具，重复 ③-⑥

```

### 3. 工具描述长什么样

当你用 `@Tool` 注解定义一个工具时，LangChain4j 会自动把它转换成模型能理解的 JSON Schema 格式：

```JSON
{
  "type": "function",
  "function": {
    "name": "queryOrderStatus",
    "description": "根据订单号查询订单的当前状态和物流信息",
    "parameters": {
      "type": "object",
      "properties": {
        "orderId": {
          "type": "string",
          "description": "订单号，格式如 ORD-12345"
        }
      },
      "required": ["orderId"]
    }
  }
}

```

这个 JSON Schema 会被注入到发给模型的请求里，模型据此知道有哪些工具可以调用。

---

## 二、用 @Tool 定义工具

LangChain4j 用注解方式定义工具，非常符合 Java 开发者的习惯。

### 基础用法

```Java
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;

public class WeatherTools {

    @Tool("获取指定城市的当前天气信息")
    public String getCurrentWeather(@P("城市名称，如：北京、上海") String city) {
        // 调用天气 API
        WeatherData data = weatherApiClient.fetch(city);
        return String.format("%s 当前天气：%s，温度：%d°C，湿度：%d%%",
                city, data.condition(), data.temperature(), data.humidity());
    }

    @Tool("获取指定城市未来 N 天的天气预报")
    public String getWeatherForecast(
            @P("城市名称") String city,
            @P("预报天数，1-7 之间的整数") int days) {
        List<DailyForecast> forecast = weatherApiClient.fetchForecast(city, days);
        return forecast.stream()
                .map(f -> f.date() + "：" + f.condition() + "，" + f.minTemp() + "-" + f.maxTemp() + "°C")
                .collect(Collectors.joining("\n"));
    }
}

```

### @Tool 注解详解

`@Tool` 注解有两个属性：

```Java
@Tool(value = "工具的功能描述", name = "工具名称（可选，默认用方法名）")

```

- `value`：工具的功能描述，这是最重要的部分，模型根据这段描述决定何时调用该工具。**描述要清晰、具体，说明工具能做什么、适合什么场景。**
- `name`：工具名称，默认使用方法名，通常不需要手动指定。

### @P 注解详解

`@P` 注解用于描述参数：

```Java
@Tool("查询商品库存")
public int checkInventory(
        @P("商品 SKU 编号，格式如 SKU-001") String sku,
        @P(value = "仓库编号，不传则查询所有仓库", required = false) String warehouseId) {
    return inventoryService.getStock(sku, warehouseId);
}

```

- `value`：参数描述，帮助模型理解该传什么值
- `required`：是否必填，默认 `true`

---

## 三、工具的注册与绑定

定义好工具类之后，需要把它绑定到 Agent 上。

### 通过 AiServices 绑定

```Java
// 单个工具类
CustomerServiceAgent agent = AiServices.builder(CustomerServiceAgent.class)
        .chatLanguageModel(model)
        .tools(new OrderTools())
        .build();

// 多个工具类
CustomerServiceAgent agent = AiServices.builder(CustomerServiceAgent.class)
        .chatLanguageModel(model)
        .tools(new OrderTools(), new PaymentTools(), new InventoryTools())
        .build();

// 工具类列表
List<Object> toolList = List.of(new OrderTools(), new PaymentTools());
CustomerServiceAgent agent = AiServices.builder(CustomerServiceAgent.class)
        .chatLanguageModel(model)
        .tools(toolList)
        .build();

```

### Spring Boot 中的工具注册

在 Spring Boot 项目里，工具类通常是 Spring Bean，可以直接注入：

```Java
@Component
public class OrderTools {

    @Autowired
    private OrderService orderService;

    @Tool("根据订单号查询订单状态")
    public String queryOrder(@P("订单号") String orderId) {
        return orderService.getStatus(orderId);
    }
}

```

```Java
@Configuration
public class AgentConfig {

    @Bean
    public CustomerServiceAgent customerServiceAgent(
            ChatLanguageModel model,
            OrderTools orderTools,
            PaymentTools paymentTools) {

        return AiServices.builder(CustomerServiceAgent.class)
                .chatLanguageModel(model)
                .tools(orderTools, paymentTools)   // 直接注入 Spring Bean
                .build();
    }
}

```

---

## 四、工具执行的完整生命周期

理解工具执行的生命周期，对调试和优化非常重要。

### 一次完整的工具调用过程

```Java
// 假设用户问："我的订单 ORD-12345 到哪里了？"

// 第一轮：模型决定调用工具
// 请求消息列表：
// [SystemMessage("你是客服助手"), UserMessage("我的订单 ORD-12345 到哪里了？")]
// 模型返回：AiMessage(toolExecutionRequests=[{name="queryOrder", arguments={"orderId":"ORD-12345"}}])

// 框架自动执行：
String toolResult = orderTools.queryOrder("ORD-12345");
// toolResult = "订单 ORD-12345 状态：已发货，预计明天下午送达"

// 第二轮：把工具结果返回给模型
// 请求消息列表：
// [SystemMessage, UserMessage, AiMessage(工具调用请求), ToolExecutionResultMessage("订单 ORD-12345 状态：已发货...")]
// 模型返回：AiMessage("您的订单 ORD-12345 已经发货，预计明天下午送达，请注意查收。")

```

### 用日志观察工具调用过程

在开发阶段，可以开启详细日志来观察工具调用：

```Java
// 在 application.yml 中开启 LangChain4j 日志
logging:
  level:
    dev.langchain4j: DEBUG

```

或者通过自定义监听器：

```Java
// 实现 ChatModelListener 监听模型请求和响应
public class ToolCallLogger implements ChatModelListener {

    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        log.info("发送给模型的消息数量：{}", requestContext.messages().size());
        requestContext.messages().forEach(msg ->
                log.debug("消息类型：{}，内容：{}", msg.type(), msg));
    }

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        AiMessage response = responseContext.response().content();
        if (response.hasToolExecutionRequests()) {
            response.toolExecutionRequests().forEach(req ->
                    log.info("模型请求调用工具：{}，参数：{}", req.name(), req.arguments()));
        }
    }
}

// 注册监听器
ChatLanguageModel model = OpenAiChatModel.builder()
        .apiKey(apiKey)
        .modelName("gpt-4o")
        .listeners(List.of(new ToolCallLogger()))
        .build();

```

---

## 五、并行工具调用

现代模型（如 GPT-4o、Claude 3.5）支持在一次响应中请求调用多个工具，LangChain4j 会自动并行执行：

```Java
public class DataAnalysisTools {

    @Tool("查询指定时间段的销售数据")
    public String querySalesData(
            @P("开始日期，格式 yyyy-MM-dd") String startDate,
            @P("结束日期，格式 yyyy-MM-dd") String endDate) {
        return salesService.query(startDate, endDate).toSummary();
    }

    @Tool("查询指定时间段的用户增长数据")
    public String queryUserGrowth(
            @P("开始日期") String startDate,
            @P("结束日期") String endDate) {
        return userService.queryGrowth(startDate, endDate).toSummary();
    }

    @Tool("查询指定时间段的客诉数据")
    public String queryComplaints(
            @P("开始日期") String startDate,
            @P("结束日期") String endDate) {
        return complaintService.query(startDate, endDate).toSummary();
    }
}

```

当用户问"帮我分析上个月的整体业务情况"时，模型可能会同时请求调用这三个工具，LangChain4j 会并行执行它们，然后把三个结果一起返回给模型做综合分析。

---

## 六、工具返回值的设计

工具的返回值会直接影响模型的最终回复质量。

### 返回结构化文本

```Java
// 不好：返回原始对象的 toString()
@Tool("查询订单")
public Order queryOrder(String orderId) {
    return orderService.findById(orderId);
    // 返回：Order{id='ORD-123', status=SHIPPED, ...} ← 模型难以理解
}

// 好：返回清晰的自然语言描述
@Tool("查询订单状态和物流信息")
public String queryOrder(@P("订单号") String orderId) {
    Order order = orderService.findById(orderId);
    return String.format(
            "订单号：%s\n状态：%s\n下单时间：%s\n预计送达：%s\n物流单号：%s",
            order.getId(),
            order.getStatus().getDisplayName(),
            order.getCreatedAt(),
            order.getEstimatedDelivery(),
            order.getTrackingNumber()
    );
}

```

### 支持的返回类型

LangChain4j 工具支持多种返回类型，框架会自动转换成字符串传给模型：

```Java
// String：直接传递
@Tool("获取当前时间")
public String getCurrentTime() {
    return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
}

// 基本类型：自动转 String
@Tool("查询积分余额")
public int queryPoints(@P("用户ID") String userId) {
    return pointsService.getBalance(userId);
}

// 对象：自动序列化为 JSON
@Tool("查询商品详情")
public ProductInfo queryProduct(@P("商品ID") String productId) {
    return productService.findById(productId);
    // 框架会把 ProductInfo 序列化成 JSON 字符串传给模型
}

// void：工具执行完毕，返回 "Done"
@Tool("发送通知邮件")
public void sendNotification(@P("用户ID") String userId, @P("消息内容") String message) {
    notificationService.send(userId, message);
}

```

---

## 七、工具的异常处理

工具方法里的异常处理非常重要，未捕获的异常会导致整个 Agent 调用失败。

### 推荐的异常处理模式

```Java
@Tool("根据订单号查询物流轨迹")
public String queryLogistics(@P("订单号") String orderId) {
    try {
        LogisticsInfo info = logisticsService.query(orderId);
        if (info == null) {
            return "未找到订单 " + orderId + " 的物流信息，请确认订单号是否正确。";
        }
        return formatLogisticsInfo(info);
    } catch (OrderNotFoundException e) {
        // 业务异常：返回友好提示，让模型告知用户
        return "订单 " + orderId + " 不存在，请检查订单号。";
    } catch (LogisticsServiceException e) {
        // 外部服务异常：返回降级信息
        log.error("物流服务查询失败，orderId={}", orderId, e);
        return "物流信息暂时无法查询，请稍后重试或联系客服。";
    } catch (Exception e) {
        // 未知异常：记录日志，返回通用错误信息
        log.error("查询物流异常，orderId={}", orderId, e);
        return "查询失败，请联系客服处理。";
    }
}

```

**关键原则：工具方法不应该抛出异常，而应该返回描述性的错误信息，让模型决定如何告知用户。**

---

## 八、动态工具注册

某些场景下，需要根据运行时条件动态决定提供哪些工具：

```Java
// 根据用户权限动态提供工具
public CustomerServiceAgent buildAgentForUser(User user) {
    List<Object> tools = new ArrayList<>();
    tools.add(new BasicQueryTools());  // 所有用户都有

    if (user.hasPermission("REFUND")) {
        tools.add(new RefundTools());
    }
    if (user.hasPermission("ADMIN")) {
        tools.add(new AdminTools());
    }

    return AiServices.builder(CustomerServiceAgent.class)
            .chatLanguageModel(model)
            .tools(tools)
            .build();
}

```

---

## 九、工具规范说明（ToolSpecification）

如果你需要在不使用注解的情况下定义工具（比如动态生成工具描述），可以直接使用 `ToolSpecification`：

```Java
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolParameters;

// 手动构建工具规范
ToolSpecification queryOrderSpec = ToolSpecification.builder()
        .name("queryOrder")
        .description("根据订单号查询订单状态和物流信息")
        .parameters(ToolParameters.builder()
                .properties(Map.of(
                        "orderId", Map.of(
                                "type", "string",
                                "description", "订单号，格式如 ORD-12345"
                        )
                ))
                .required(List.of("orderId"))
                .build())
        .build();

// 直接调用底层模型 API（不经过 AiServices）
Response<AiMessage> response = model.generate(
        messages,
        List.of(queryOrderSpec)
);

// 检查模型是否请求调用工具
if (response.content().hasToolExecutionRequests()) {
    ToolExecutionRequest request = response.content().toolExecutionRequests().get(0);
    String toolName = request.name();       // "queryOrder"
    String arguments = request.arguments(); // {"orderId": "ORD-12345"}
    // 手动路由执行
}

```

这种方式适合需要完全控制工具调用流程的高级场景，比如构建自定义 Agent 框架。

---

## 十、完整示例：一个带多工具的智能助手

```Java
// 工具类定义
@Component
public class SmartAssistantTools {

    @Autowired private WeatherService weatherService;
    @Autowired private CalendarService calendarService;
    @Autowired private EmailService emailService;
    @Autowired private SearchService searchService;

    @Tool("查询指定城市的实时天气")
    public String getWeather(@P("城市名称") String city) {
        try {
            WeatherData data = weatherService.getCurrent(city);
            return String.format("%s：%s，%d°C，%s", city, data.condition(), data.temp(), data.advice());
        } catch (Exception e) {
            return "天气查询失败：" + e.getMessage();
        }
    }

    @Tool("查询今天的日程安排")
    public String getTodaySchedule() {
        List<Event> events = calendarService.getTodayEvents();
        if (events.isEmpty()) return "今天没有日程安排。";
        return events.stream()
                .map(e -> e.time() + " " + e.title())
                .collect(Collectors.joining("\n"));
    }

    @Tool("发送邮件")
    public String sendEmail(
            @P("收件人邮箱") String to,
            @P("邮件主题") String subject,
            @P("邮件正文") String body) {
        try {
            emailService.send(to, subject, body);
            return "邮件已成功发送给 " + to;
        } catch (Exception e) {
            return "邮件发送失败：" + e.getMessage();
        }
    }

    @Tool("搜索互联网上的最新信息")
    public String search(@P("搜索关键词") String query) {
        List<SearchResult> results = searchService.search(query, 3);
        return results.stream()
                .map(r -> "- " + r.title() + "\n  " + r.snippet())
                .collect(Collectors.joining("\n"));
    }
}

```

```Java
// Agent 接口
interface SmartAssistant {
    @SystemMessage("""
            你是一个智能个人助手，可以帮助用户查天气、管理日程、发邮件、搜索信息。
            回答要简洁实用，必要时主动调用工具获取最新信息。
            """)
    String chat(@MemoryId String userId, @UserMessage String message);
}

```

```Java
// 组装
@Bean
public SmartAssistant smartAssistant(ChatLanguageModel model, SmartAssistantTools tools) {
    return AiServices.builder(SmartAssistant.class)
            .chatLanguageModel(model)
            .tools(tools)
            .chatMemoryProvider(id -> MessageWindowChatMemory.withMaxMessages(20))
            .build();
}

```

---

## 十一、工具设计最佳实践

### 1. 工具描述要精准

```Java
// 不好：描述模糊
@Tool("查询数据")
public String query(String id) { ... }

// 好：描述清晰，说明输入输出和适用场景
@Tool("根据用户ID查询用户的基本信息，包括姓名、邮箱、注册时间和账户状态")
public String queryUserInfo(@P("用户ID，格式为 UUID") String userId) { ... }

```

### 2. 一个工具只做一件事

```Java
// 不好：一个工具做太多事
@Tool("查询订单并发送通知")
public String queryAndNotify(String orderId) { ... }

// 好：职责单一
@Tool("查询订单状态")
public String queryOrder(String orderId) { ... }

@Tool("发送订单状态通知给用户")
public String notifyUser(String userId, String message) { ... }

```

### 3. 参数数量控制在 5 个以内

参数过多会让模型难以正确填写，建议超过 5 个参数时考虑用对象封装，或拆分成多个工具。

### 4. 返回值要对模型友好

工具返回的内容会直接进入模型的上下文，要用自然语言描述，避免返回原始数据结构或过长的 JSON。

---

## 十二、本节小结

这节课我们把 LangChain4j 的工具机制从底层到实践全部讲透了。核心要点：

- **Function Calling 原理**：模型生成工具调用请求 → 框架执行 → 结果返回模型 → 模型生成最终回复
- **@Tool 注解**：定义工具功能描述，框架自动转换成 JSON Schema 注入给模型
- **@P 注解**：描述参数含义，帮助模型正确填写参数值
- **工具绑定**：通过 `AiServices.builder().tools()` 注册工具类
- **异常处理**：工具方法应捕获所有异常，返回描述性错误信息而非抛出异常
- **设计原则**：描述精准、职责单一、参数简洁、返回值对模型友好

工具是 Agent 从"会说"到"会做"的关键一步。掌握了工具机制，你就能让 Agent 真正连接到外部世界。

下一节课，我们会深入模型集成层，看看 LangChain4j 如何通过适配器模式支持 OpenAI、Azure、Ollama、HuggingFace 等不同模型提供商。

---

## 十三、引用文档与延伸阅读

- LangChain4j 工具调用官方文档：https://docs.langchain4j.dev/tutorials/tools
- LangChain4j @Tool 注解源码：https://github.com/langchain4j/langchain4j/blob/main/langchain4j-core/src/main/java/dev/langchain4j/agent/tool/Tool.java
- OpenAI Function Calling 官方文档：https://platform.openai.com/docs/guides/function-calling
- Anthropic Tool Use 官方文档：https://docs.anthropic.com/en/docs/build-with-claude/tool-use
- LangChain4j ToolSpecification 源码：https://github.com/langchain4j/langchain4j/blob/main/langchain4j-core/src/main/java/dev/langchain4j/agent/tool/ToolSpecification.java
- LangChain4j ChatModelListener 文档：https://docs.langchain4j.dev/tutorials/logging
- GitHub 示例代码：https://github.com/langchain4j/langchain4j-examples/tree/main/other-examples/src/main/java/ServiceWithToolsExample.java

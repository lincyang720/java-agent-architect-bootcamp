# 第八章第三节：工具使用（Tool Use）：外部 API 调用、数据库操作、浏览器自动化

大家好，我是秋天。

规划能力解决"想清楚下一步"，但 Agent 真正"动手改变世界"靠的是**工具使用（Tool Use）**。没有工具的 Agent 只是个会聊天的嘴；接上工具的 Agent 才能查数据库、调 API、点网页、写文件——这才是"Agent"和"chatbot"的分水岭。

学完这节课，你能掌握：

1. **工具调用的底层机制**：模型怎么决定调哪个工具、怎么填参数、怎么处理返回。
2. **三类核心工具**的工程实现：外部 API 调用、数据库操作、浏览器自动化。
3. **工具使用的安全护栏**：参数校验、权限隔离、超时熔断——生产必做。

<callout emoji="💡"><p>招聘映射：JD 高频"函数调用/工具调用（Function Calling / Tool Use）""会接外部系统"。这几乎是 Java Agent 岗的入场券，本节课给的就是可上手的真实代码。</p></callout>

---

## 一、工具调用的机制：模型不是"执行"工具，是"建议"调用

先破除一个误解：LLM 本身**不会**真的去查数据库。它做的是——根据对话，输出一段"我想调用 tool X，参数是 {a:1}"的结构化 JSON。真正执行的是你的 Java 代码。

```
用户: "北京今天天气？"
  │
  ▼ 模型推理：需要天气工具
  ▼ 模型输出: { "tool": "getWeather", "args": {"city":"北京"} }  ← 只是"建议"
  ▼ 你的代码解析 JSON → 真正调用天气 API → 拿到 "晴 25℃"
  ▼ 把结果塞回对话 → 模型生成自然语言回答
```

### 1.1 LangChain4j 的 @Tool 声明

```java
// ch08-cognition/src/main/java/demo/tool/WeatherTools.java
package demo.tool;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;

import java.time.LocalDate;

public class WeatherTools {

    @Tool("查询指定城市当天的天气")
    public String getWeather(
            @P("城市名称，如 北京") String city,
            @P("日期，默认今天") String date) {
        // 真实实现里调天气 API（如高德/和风）
        return city + " " + (date == null ? LocalDate.now() : date) + " 晴 25℃";
    }

    @Tool("查询两个城市间的距离(公里)")
    public double distance(
            @P("出发城市") String from,
            @P("到达城市") String to) {
        // 调地图 API
        return 1080.0;
    }
}
```

### 1.2 把工具挂到 Agent 上

```java
// ch08-cognition/src/main/java/demo/tool/ToolAgent.java
package demo.tool;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;

public class ToolAgent {
    interface Assistant {
        String chat(String userMessage);
    }

    public static void main(String[] args) {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o").build();

        Assistant agent = AiServices.builder(Assistant.class)
                .chatLanguageModel(model)
                .tools(new WeatherTools())   // 注册工具
                .build();

        // 模型会自主决定调用 getWeather("北京")
        String ans = agent.chat("北京今天天气怎么样？");
        System.out.println(ans);
    }
}
```

<callout emoji="⚠️"><p>关键认知：@Tool 方法里的代码是你写的、在你这边的 JVM 里执行的，模型碰不到你的服务器。所以"模型会不会乱删数据库"取决于你给它的工具边界——只暴露"查询"工具就不怕它删库。工具设计就是权限设计。</p></callout>

---

## 二、外部 API 调用：用声明式工具接真实系统

### 2.1 调一个 REST API（WebClient）

```java
// ch08-cognition/src/main/java/demo/tool/OrderTools.java
package demo.tool;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

public class OrderTools {

    private final WebClient client = WebClient.create("https://api.internal");

    @Tool("根据订单号查询订单状态")
    public String queryOrder(@P("订单号") String orderId) {
        // 真实 API 调用（带鉴权头）
        String resp = client.get()
                .uri("/orders/{id}", orderId)
                .header("Authorization", "Bearer " + System.getenv("API_TOKEN"))
                .retrieve()
                .bodyToMono(String.class)
                .onErrorResume(e -> Mono.just("{\"error\":\"查询失败\"}"))
                .block();   // 工具内同步拿结果；生产可用异步+超时
        return resp;
    }
}
```

### 2.2 MCP：把"工具"标准化（跨框架互操作）

MCP（Model Context Protocol）让工具的"描述 + 调用"变成标准协议，Agent 不用为每个 API 写适配。这是 2025-26 的重头戏（第十一章详讲），这里先给个客户端视角：

```java
// ch08-cognition/src/main/java/demo/tool/McpToolClient.java
package demo.tool;

// 示意：通过 MCP 客户端连接一个"订单服务"的 MCP Server
// 该 Server 已把 queryOrder 暴露为标准 tool，Agent 自动可见
public class McpToolClient {
    public static void main(String[] args) {
        System.out.println("通过 MCP 协议接入订单服务工具（详见第十一章）");
    }
}
```

<callout emoji="✅"><p>企业落地建议：内部系统多、工具杂时，别让每个 Agent 都手写 API 适配。用 MCP 把"订单服务""用户服务""风控服务"各自封装成 MCP Server，Agent 侧统一用 MCP 客户端接入。一次封装、多处复用，这是平台化的关键一步。</p></callout>

---

## 三、数据库操作：让 Agent 会"查数"

### 3.1 直接调用 DAO（受控，推荐起步）

最安全的方式是**不让模型直接写 SQL**，而是把"查什么"封装成带参数的工具方法，模型只填参数。

```java
// ch08-cognition/src/main/java/demo/tool/DbTools.java
package demo.tool;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

public class DbTools {

    private final JdbcTemplate jdbc;

    public DbTools(DataSource ds) { this.jdbc = new JdbcTemplate(ds); }

    @Tool("查询指定用户最近N笔订单")
    public List<Map<String, Object>> recentOrders(
            @P("用户ID") Long userId,
            @P("笔数，默认5") Integer limit) {
        int n = limit == null ? 5 : Math.min(limit, 20);  // 上限防护
        return jdbc.queryForList(
            "SELECT id,amount,status,created_at FROM orders " +
            "WHERE user_id=? ORDER BY created_at DESC LIMIT ?",
            userId, n);
    }
}
```

### 3.2 让模型生成 SQL（NL2SQL，需强护栏）

进阶玩法：模型直接把自然语言转成 SQL。但这风险极高，必须加护栏（只读、白名单表、行数限制）。

```java
// ch08-cognition/src/main/java/demo/tool/SafeSqlTool.java
package demo.tool;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import org.springframework.jdbc.core.JdbcTemplate;

public class SafeSqlTool {
    private final JdbcTemplate jdbc;

    @Tool("用自然语言查询数据库（仅只读）")
    public String nl2sql(@P("自然语言查询") String question) {
        // 1) 模型生成 SQL（这里示意，真实用 NL2SQL 模型/提示）
        String sql = generateSql(question);
        // 2) 强制护栏：禁止写操作 + 限制行数
        if (!sql.trim().toLowerCase().startsWith("select")) {
            return "❌ 仅允许只读查询";
        }
        sql = enforceLimit(sql, 100);
        try {
            return jdbc.queryForList(sql).toString();
        } catch (Exception e) {
            return "❌ 查询错误: " + e.getMessage();
        }
    }

    private static String generateSql(String q) { return "SELECT * FROM orders LIMIT 10"; }
    private static String enforceLimit(String sql, int n) {
        return sql.toLowerCase().contains("limit") ? sql : sql + " LIMIT " + n;
    }
}
```

<callout emoji="⚠️"><p>NL2SQL 的红线：绝对不要让模型生成的 SQL 带 INSERT/UPDATE/DELETE/DROP，且必须强制 LIMIT 和表白名单。曾有团队因让 Agent 直连生产库执行生成 SQL，一条 `DELETE` 没拦住，清空了半张表。只读副本 + 强护栏是底线。</p></callout>

---

## 四、浏览器自动化：让 Agent 会"上网"

有些信息没有 API（如竞品官网、政府公示页），只能让 Agent 真正打开浏览器操作。

### 4.1 用 Playwright 驱动浏览器

```java
// ch08-cognition/src/main/java/demo/tool/BrowserTools.java
package demo.tool;

import dev.langchain4j.agent.@Tool;
import dev.langchain4j.agent.tool.P;
import com.microsoft.playwright.*;

public class BrowserTools {

    @Tool("打开指定网址并提取页面正文文本")
    public String fetchPage(@P("URL") String url) {
        try (Playwright pw = Playwright.create();
             Browser browser = pw.chromium().launch();
             Page page = browser.newPage()) {
            page.navigate(url, new Page.NavigateOptions().setTimeout(15000));
            return page.innerText("body");  // 提取正文
        } catch (Exception e) {
            return "❌ 页面抓取失败: " + e.getMessage();
        }
    }

    @Tool("在网页表单输入并提交")
    public String fillForm(@P("URL") String url,
                           @P("字段名=值,逗号分隔") String fields) {
        try (Playwright pw = Playwright.create();
             Browser browser = pw.chromium().launch();
             Page page = browser.newPage()) {
            page.navigate(url);
            for (String kv : fields.split(",")) {
                String[] p = kv.split("=");
                page.fill("input[name=" + p[0] + "]", p[1]);
            }
            page.click("button[type=submit]");
            return "提交完成";
        }
    }
}
```

对应依赖：

```xml
<!-- ch08-cognition/pom.xml 片段 -->
<dependency>
    <groupId>com.microsoft.playwright</groupId>
    <artifactId>playwright</artifactId>
    <version>1.44.0</version>
</dependency>
```

### 4.2 浏览器自动化的风险与隔离

<callout emoji="✅"><p>浏览器工具威力大但风险也大（能点能提交）。生产必须：① 跑在沙箱/独立容器里，② 禁止访问内网地址，③ 敏感操作需 Human-in-the-Loop 确认（第四章），④ 全程录像留痕。把浏览器 Agent 当成"有手的外包"，权限给最小、动作留证据。</p></callout>

---

## 五、工具使用的安全护栏（生产必做）

```
工具调用四道护栏：
┌──────────────────────────────────────────────┐
│ 1. 参数校验：类型/范围/枚举，拒非法输入        │
│ 2. 权限隔离：工具按角色授权，敏感工具需审批    │
│ 3. 超时熔断：单次调用超时即中断，防卡死        │
│ 4. 副作用管控：写操作默认需确认/异步+审计      │
└──────────────────────────────────────────────┘
```

```java
// ch08-cognition/src/main/java/demo/tool/ToolGuard.java
package demo.tool;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

public class ToolGuard {

    private static final Set<String> WRITE_TOOLS = Set.of("fillForm", "submitOrder");
    private static final long TIMEOUT_MS = 10_000;

    /** 执行工具前的安全检查：超时 + 写操作需确认 */
    public static String executeWithGuard(String toolName,
                                          java.util.function.Supplier<String> action,
                                          boolean humanApproved) throws Exception {
        if (WRITE_TOOLS.contains(toolName) && !humanApproved) {
            return "⚠️ 写操作需人工确认";
        }
        CompletableFuture<String> f = CompletableFuture.supplyAsync(action);
        try {
            return f.get(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            f.cancel(true);
            return "❌ 工具调用超时";
        }
    }
}
```

---

## 六、企业落地：对账 Agent 的工具集

某财务对账 Agent 的工具清单：

```
工具名              类型        权限        说明
─────────────────────────────────────────────────────
queryTransactions   DB只读      自动        查交易流水
queryInvoice        DB只读      自动        查发票
callBankApi         API调用     自动        拉取银行对账单
flagDiscrepancy    写操作      需审批      标记差异(人工确认)
createTicket        写操作      需审批      生成差异工单
```

**为什么这样设计？** 对账是"查多、改少、错不起"的场景。所有查询工具自动执行提效；所有写操作（标记、建单）必须经过 Human-in-the-Loop 审批。工具集就是权限边界，把"自动化"和"可控"平衡好。

---

## 七、踩坑清单

| 坑 | 后果 | 解法 |
|----|------|------|
| 让模型直连生产库执行SQL | 误删数据 | 只读副本+强护栏 |
| 浏览器工具无沙箱 | 访问内网/乱点 | 沙箱+禁内网+留痕 |
| 工具无超时 | 调用卡死拖垮Agent | 统一超时熔断 |
| 写操作无确认 | 误操作难挽回 | Human-in-the-Loop |
| 暴露过多工具 | 模型选错/越权 | 按需最小工具集 |

---

## 八、工具描述（Schema）决定调用质量

模型靠你给的 `@Tool` 描述和参数注解来"理解"工具。描述写得烂，模型就调错。三条铁律：

1. **描述说清"何时用"**：不要只写"查询天气"，写"当用户询问某城市天气、温度、降水时使用"。
2. **参数描述写清格式与单位**：`@P("日期，格式 YYYY-MM-DD，默认今天")`。
3. **枚举约束写死**：状态参数用明确枚举，别让模型自由发挥。

```java
// ch08-cognition/src/main/java/demo/tool/WellDescribedTools.java
package demo.tool;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;

public class WellDescribedTools {

    @Tool("当用户询问指定城市、指定日期的天气（温度/降水/风力）时调用，不要用于历史天气")
    public String getWeather(
            @P("城市中文名，如 北京、上海") String city,
            @P("日期，格式 YYYY-MM-DD，留空表示今天") String date) {
        return city + " " + (date == null ? "今天" : date) + " 晴 25℃ 风力3级";
    }

    @Tool("查询订单状态，仅接受已存在的订单号")
    public String queryOrder(
            @P("订单号，格式 ORD+数字，如 ORD12345") String orderId) {
        return "订单 " + orderId + " 状态: 已发货";
    }
}
```

---

## 九、Spring AI 的函数调用写法（对照 LangChain4j）

Spring AI 走的是 `@Tool` + `ToolCallback` 路线，对 Spring 工程师更顺手：

```java
// ch08-cognition/src/main/java/demo/tool/SpringAiTools.java
package demo.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SpringAiTools {

    @Bean
    @Tool(description = "查询指定城市天气，当用户问天气时调用")
    public String weatherTool(@ToolParam(description = "城市名") String city) {
        return city + " 晴 25℃";
    }
}
```

对应调用端用 `ChatClient` + `defaultTools`：

```java
// ch08-cognition/src/main/java/demo/tool/SpringAiToolCall.java
package demo.tool;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;

public class SpringAiToolCall {
    @Bean
    public ChatClient toolChatClient(ChatClient.Builder b, Object weatherTool) {
        return b.defaultTools(weatherTool).build();
    }
}
```

---

## 十、工具调用的错误处理与降级

真实环境里工具会超时、报错、返回脏数据。生产代码必须有降级：

```java
// ch08-cognition/src/main/java/demo/tool/ToolResilience.java
package demo.tool;

import java.util.concurrent.*;

public class ToolResilience {
    private static final ScheduledExecutorService EX =
            Executors.newScheduledThreadPool(4);

    /** 带超时与降级的工具执行 */
    public static String callWithFallback(java.util.function.Supplier<String> action,
                                          String fallback) {
        CompletableFuture<String> f = CompletableFuture.supplyAsync(action, EX);
        try {
            return f.get(8, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            return fallback;   // 降级返回兜底信息，而非抛异常中断 Agent
        } catch (Exception e) {
            return "工具执行异常: " + e.getMessage();
        }
    }
}
```

```xml
<!-- 工具相关依赖示例 -->
<dependencies>
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-openai-spring-boot-starter</artifactId>
        <version>1.0.0-M6</version>
    </dependency>
    <dependency>
        <groupId>com.microsoft.playwright</groupId>
        <artifactId>playwright</artifactId>
        <version>1.44.0</version>
    </dependency>
</dependencies>
```

---

## 十一、企业工具治理体系

当工具数量从几个涨到几十个，必须治理，否则模型选工具会乱：

```
工具治理四要素：
┌──────────────────────────────────────────┐
│ 1. 命名规范：动词+名词，如 queryOrder     │
│ 2. 分类标签：db / api / browser / write   │
│ 3. 权限分级：read(自动) / write(审批)     │
│ 4. 版本管理：工具变更要向后兼容           │
└──────────────────────────────────────────┘
```

| 治理动作 | 做法 | 收益 |
|----------|------|------|
| 工具注册中心 | 集中登记所有工具元信息 | 可检索、可审计 |
| 调用审计 | 记录每次工具调用的入参出参 | badcase 可回溯 |
| 灰度上线 | 新工具先 10% 流量 | 防大面积故障 |
| 自动评测 | 工具选择准确率纳入评测(第16章) | 量化工具设计质量 |

<callout emoji="✅"><p>工具多了之后，模型"选错工具"会成为新瓶颈。这时可以引入"工具路由"（类似 lesson08 的知识库路由）：先用小模型/规则判断该调哪类工具，再让大模型填参数。治理先行，规模才上得去。</p></callout>

## 小结

这节课把"工具使用"这根支柱讲实了：

- 工具调用机制：模型只"建议"调用，真正执行在你 JVM，工具即权限边界。
- 三类核心工具：外部 API（WebClient/MCP）、数据库（受控 DAO 或带护栏 NL2SQL）、浏览器自动化（Playwright，需沙箱）。
- 安全护栏四道：参数校验、权限隔离、超时熔断、写操作需审批。
- 企业落地按"查多改少、写需确认"设计工具集，把自动化与可控平衡好。

下一节讲反思机制（Reflection）——Agent 怎么"发现错了并自己改"（lesson04）。

---

## 延伸阅读 / 引用文档

1. OpenAI Function Calling 文档: https://platform.openai.com/docs/guides/function-calling
2. LangChain4j Tools: https://docs.langchain4j.dev/tutorials/ai-services-tool-calling
3. MCP 官方规范: https://modelcontextprotocol.io/
4. Playwright Java 文档: https://playwright.dev/java/
5. Spring WebClient 文档: https://docs.spring.io/spring-framework/reference/web/webflux/webclient.html
6. Shinn et al., *Reflexion*（反思机制奠基论文，下一节）: https://arxiv.org/abs/2303.11366

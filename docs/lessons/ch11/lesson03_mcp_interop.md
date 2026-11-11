# 第十一章第3节：基于 MCP 的跨框架 Agent 互操作：LangChain4j、Spring AI 集成 MCP

大家好，我是秋天。

前两节你学会了 MCP 的原理，也用 Java 把 Client/Server 跑通了。这一节我们进入更现实的场景：**你的公司里不可能只有一个框架**。前端团队用 LangChain4j 写客服，中台团队用 Spring AI 写风控，数据团队用 Python 写了个 MCP Server 暴露知识库——怎么让这三拨人"复用同一套工具，而不是各写各的"？

这就是"跨框架互操作"要解决的问题。学完这一节，你将掌握：

1. 为什么 MCP 是"跨框架"的钥匙（协议中立）；
2. LangChain4j 如何作为 MCP Client 接入外部 Server；
3. Spring AI 如何同时作为 MCP Client 和 Server；
4. 一个 Python Server + Java 两个框架 Client 的真实互操作示例；
5. 企业里"工具中台化"的落地架构。

---

## 一、问题的本质：框架是"方言"，MCP 是"普通话"

先给你一张图，看清为什么没有 MCP 时互操作几乎不可能：

```text
              没有 MCP：框架各自为政
 ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
 │LangChain4j  │    │ Spring AI   │    │ Python Lang │
 │ 自有 Tool   │    │ 自有 Tool   │    │ 自有 Tool   │
 │ 注册机制    │    │ 注册机制    │    │ 注册机制    │
 └──────┬──────┘    └──────┬──────┘    └──────┬──────┘
        │                  │                  │
        ▼                  ▼                  ▼
 ┌──────────────────────────────────────────────────┐
 │   同一套"查订单"能力，被实现/维护 3 遍，版本还不一致  │
 └──────────────────────────────────────────────────┘

              有 MCP：协议统一一切
 ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
 │LangChain4j  │    │ Spring AI   │    │ Python Lang │
 │ 作为 MCP    │    │ 作为 MCP    │    │ 作为 MCP    │
 │ Client      │    │ Client      │    │ Client      │
 └──────┬──────┘    └──────┬──────┘    └──────┬──────┘
        │                  │                  │
        └──────────────────┼──────────────────┘
                           ▼
              ┌──────────────────────────┐
              │  统一 MCP Server：查订单   │
              │  （只实现、维护一遍）      │
              └──────────────────────────┘
```

<callout emoji="💡"><p>关键认知：MCP 不关心你是谁家的框架。它只定义"工具的输入输出长什么样、怎么调用"。所以 LangChain4j、Spring AI、甚至 Python 的 Agent，只要实现了 MCP Client，就能用同一套 Server。这就是"互操作"三个字的全部含义。</p></callout>

---

## 二、LangChain4j 集成 MCP：作为 Client 消费 Server

LangChain4j 从 1.x 起官方支持 MCP。核心思路是：用 MCP Client 连上 Server，把 Server 暴露的 Tools 转成 LangChain4j 的 `ToolSpecification`，挂到 `AiServices` 或 `ChatLanguageModel` 上。

Maven 依赖：

```xml
<dependency>
  <groupId>dev.langchain4j</groupId>
  <artifactId>langchain4j-mcp</artifactId>
  <version>1.0.0</version>
</dependency>
<dependency>
  <groupId>dev.langchain4j</groupId>
  <artifactId>langchain4j-open-ai</artifactId>
  <version>1.0.0</version>
</dependency>
```

连接 MCP Server 并消费工具：

```java
package com.qiutian.interop;

import dev.langchain4j.mcp.McpToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.ChatLanguageModel;

import java.util.List;

public class LangChain4jMcpDemo {

    interface Assistant {
        String chat(String userMessage);
    }

    public static void main(String[] args) {
        // 1. 配置到 MCP Server 的 stdio 传输
        McpTransport transport = new StdioMcpTransport.Builder()
                .command(List.of("java", "-cp", "mcp-server.jar",
                        "com.qiutian.mcp.WeatherMcpServer"))
                .logEvents(true)
                .build();

        // 2. 建立 MCP Client 并拉取工具清单
        McpClient mcpClient = new McpClient.Builder().transport(transport).build();
        List<McpToolSpecification> toolSpecs = mcpClient.listTools();

        // 3. 初始化底层模型（换成你的 API Key 配置）
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini")
                .build();

        // 4. 把 MCP 工具注入 AiServices
        Assistant assistant = AiServices.builder(Assistant.class)
                .chatLanguageModel(model)
                .toolSpecifications(toolSpecs)
                .build();

        // 5. 直接对话，模型会自行决定调用 MCP 工具
        String answer = assistant.chat("北京今天天气怎么样？");
        System.out.println(answer);
    }
}
```

<callout emoji="✅"><p>注意第 4 步：`toolSpecifications(toolSpecs)` 把你从 MCP Server 拉来的工具直接挂进了 LangChain4j 的 Agent。也就是说，LangChain4j 完全不知道（也不需要知道）这个工具是 Java、Python 还是 Go 写的——它只认 MCP 协议。这就是互操作。</p></callout>

---

## 三、LangChain4j 连远程 MCP Server（SSE）

如果是远程部署的 MCP Server（走 SSE/HTTP），传输换成 `HttpMcpTransport`：

```java
McpTransport transport = new HttpMcpTransport.Builder()
        .url("http://mcp-internal:8080/mcp/weather")
        .logRequests(true)
        .logResponses(true)
        .build();

McpClient mcpClient = new McpClient.Builder().transport(transport).build();
List<McpToolSpecification> specs = mcpClient.listTools();
```

```text
    LangChain4j 作为 MCP Client 的调用链
 ┌────────────────┐   1.listTools    ┌──────────────────┐
 │ LangChain4j    │ ───────────────▶ │ MCP Server(远程) │
 │ Assistant      │                  │ 返回 Tool 清单    │
 └───────┬────────┘                  └──────────────────┘
         │ 2.模型决定调用 query_weather
         ▼
 ┌────────────────┐   3.callTool     ┌──────────────────┐
 │ LangChain4j    │ ───────────────▶ │ MCP Server       │
 │ ToolExecutor   │ ◀─────────────── │ 返回天气结果      │
 └────────────────┘   4.结果回流       └──────────────────┘
```

---

## 四、Spring AI 集成 MCP：既能 Client 也能 Server

Spring AI 在 MCP 上的支持比 LangChain4j 更"双向"：既能作为 Client 消费别人的 Server，也能作为 Server 暴露自己的工具。这一节 2.2 我们已经看过 Server 端 `@Tool` 注解，这里重点看 **Client 端同时聚合多个 Server**。

配置多个 MCP Server（YAML）：

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: true
        # 声明要连接的多个 Server
        servers:
          weather:
            command: ["java", "-jar", "weather-server.jar"]
            type: STDIO
          knowledge:
            url: http://mcp-internal:8080/mcp/kb
            type: SSE
```

在 Agent 里聚合所有 Server 的工具：

```java
package com.qiutian.interop;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.McpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.
@RestController;

@RestController
public class InteropAgentController {

    private final ChatClient chatClient;
    private final McpToolCallbackProvider mcpProvider;

    public InteropAgentController(ChatClient.Builder builder,
                                  McpToolCallbackProvider mcpProvider) {
        this.chatClient = builder.build();
        this.mcpProvider = mcpProvider;
    }

    @PostMapping("/agent/ask")
    public String ask(String question) {
        // 关键：把"所有已连接的 MCP Server"的工具统一注入
        ToolCallback[] allTools = mcpProvider.getToolCallbacks();
        return chatClient.prompt()
                .user(question)
                .tools(allTools)
                .call()
                .content();
    }
}
```

<callout emoji="💡"><p>`mcpProvider.getToolCallbacks()` 返回的是**所有配置 Server 的并集**。这意味着：你加一个 Python 写的知识库 Server，只需在 application.yml 里加一条 `knowledge` 配置，Agent 自动就能用——零代码改动。这就是"工具中台化"的体验。</p></callout>

---

## 五、跨语言真实示例：Python Server + 两个 Java 框架 Client

光说不够，给你一个能跑的端到端例子。先是一个 **Python MCP Server**（数据团队最爱写的），暴露"查订单"工具：

```python
"""order_mcp_server.py —— 用 Python 写，给 Java 框架复用"""
import asyncio
from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import Tool, TextContent
import json

app = Server("order-service")

# 模拟订单库
ORDERS = {
    "1001": {"user": "张三", "amount": 299, "status": "已发货"},
    "1002": {"user": "李四", "amount": 599, "status": "待付款"},
}

@app.list_tools()
async def list_tools():
    return [Tool(
        name="query_order",
        description="根据订单号查询订单详情",
        inputSchema={
            "type": "object",
            "properties": {"orderId": {"type": "string"}},
            "required": ["orderId"],
        },
    )]

@app.call_tool()
async def call_tool(name, arguments):
    if name == "query_order":
        order = ORDERS.get(arguments["orderId"])
        if not order:
            return [TextContent(type="text", text="订单不存在")]
        return [TextContent(type="text",
                            text=json.dumps(order, ensure_ascii=False))]
    return [TextContent(type="text", text="unknown")]

async def main():
    async with stdio_server() as (r, w):
        await app.run(r, w, app.create_initialization_options())

if __name__ == "__main__":
    asyncio.run(main())
```

现在，**LangChain4j 的客服 Agent** 和 **Spring AI 的风控 Agent** 都能连这个 Python Server，谁也不用重写"查订单"逻辑。两个框架 Client 配置：

```text
# LangChain4j Client 启动命令指向 Python
command: ["python", "order_mcp_server.py"]

# Spring AI Client 同样
command: ["python", "order_mcp_server.py"]
```

<callout emoji="✅"><p>这就是互操作的威力：数据团队用最熟的语言（Python）写一次工具，Java 两个框架的 Agent 直接复用。在招聘里，这对应"具备跨语言、跨框架 Agent 集成能力"，是高级岗的硬门槛。</p></callout>

---

## 六、企业"工具中台"落地架构

我把上面所有东西拼成一张企业级架构图，这是你面试时能讲清楚的大图：

```text
               企业 Agent 工具中台（基于 MCP）
 ┌────────────────────────────────────────────────────────┐
 │                    工具治理层                            │
 │  工具注册中心 / 版本管理 / 权限目录 / 调用审计           │
 └───────────────┬──────────────────┬─────────────────────┘
                 │                  │
   ┌─────────────▼──────┐  ┌────────▼──────────┐  ┌──────────────┐
   │ MCP Server: 订单   │  │ MCP Server: 知识库│  │ MCP Server:  │
   │ (Python)          │  │ (Java)           │  │ 风控 (Java)  │
   │ 持 DB 凭据         │  │ 持向量库凭据      │  │ 持规则引擎    │
   └─────────┬─────────┘  └────────┬─────────┘  └──────┬───────┘
             │ stdio/SSE            │ SSE               │ SSE
             └──────────┬───────────┴───────────────────┘
                        ▼
   ┌────────────────────────────────────────────────────┐
   │              Agent 应用层（多框架共存）               │
   │  ┌────────────┐   ┌────────────┐   ┌────────────┐  │
   │  │LangChain4j │   │ Spring AI  │   │ Google ADK │  │
   │  │ 客服 Agent │   │ 风控 Agent │   │ 中台 Agent │  │
   │  │(MCP Client)│   │(MCP Client)│   │(MCP Client)│  │
   │  └────────────┘   └────────────┘   └────────────┘  │
   └────────────────────────────────────────────────────┘
```

这张图的价值在于：**工具与框架解耦**。框架可以换（今天 LangChain4j，明天 ADK），工具不动；工具可以增（新接一个 HR 系统 Server），框架零改动。

<callout emoji="⚠️"><p>提醒：工具中台不是银弹。它引入了一层治理复杂度（注册、版本、权限、审计）。小团队（<10 个 Agent）直接用框架自带 function calling 更高效；当工具数 > 20、跨团队复用成为常态时，MCP 中台才划算。秋天强调过无数次——不要为了用而用。</p></callout>

---

## 七、版本与兼容：protocolVersion 的坑

跨框架时最容易出问题的就是协议版本。MCP 用 `protocolVersion` 协商，Client 和 Server 各自支持一个范围，取交集。

```text
Client 支持: [2024-11-05, 2025-03-26]
Server 支持: [2025-03-26, 2025-06-18]
→ 协商结果: 2025-03-26（两者交集）

如果 Client 仅支持老版本，Server 不支持 → 握手失败
```

<callout emoji="💡"><p>排错口诀：连不上先 `initialize` 看 `protocolVersion` 是否协商成功。LangChain4j、Spring AI、Python SDK 的版本节奏不同，跨框架组合时务必对齐到双方都支持的协议版本。MCP Inspector 能直接看到握手报文。</p></callout>

---

## 八、互操作下的"工具描述"质量

跨框架复用后，工具的 `description` 变得前所未有地重要——因为它是模型判断"要不要调这个工具"的唯一依据，而不同框架的模型可能不同。

```java
// 好描述：讲清何时用、输入输出语义
@Tool(name = "query_order",
      description = "当用户提供订单号并询问物流/金额/状态时调用，"
                    + "返回 JSON：{user, amount, status}")
// 差描述：模型几乎无法判断调用时机
@Tool(name = "query_order", description = "查询订单")
```

<callout emoji="✅"><p>工具描述写得好，跨框架准确率立刻提升。这是招聘里"提示词工程 + 工具设计"交叉能力，也是 Context Engineering（第十五章）里 Write 策略的一部分。别忘了：给模型的东西，描述即契约。</p></callout>

---

## 九、一个完整的 application.yml 示例

把这一节所有配置收口，给你一份可直接参考的 Host 端配置：

```yaml
spring:
  application:
    name: qiutian-agent-host
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      model: gpt-4o-mini
    mcp:
      client:
        enabled: true
        request-timeout: 8000ms
        servers:
          weather:
            command: ["java", "-jar", "/opt/mcp/weather-server.jar"]
            type: STDIO
          order:
            command: ["python", "/opt/mcp/order_mcp_server.py"]
            type: STDIO
          knowledge:
            url: https://mcp-internal.k8s/mcp/kb
            type: SSE
```

---

## 十、多 Server 聚合的性能与上下文账

上一节 Agent 注入了"所有 Server 的并集工具"。但工具越多，模型选不准、token 越贵。给你算一笔账：

```text
   工具数量 vs 上下文成本（粗略估算）
 ┌──────────────────────────────────────────────────┐
 │ 注入工具数   每轮额外 token   选准率趋势           │
 │   5          ~800            高（>92%）            │
 │   20         ~3000           中（~85%）            │
 │   50         ~7500           低（~72%，易误调）    │
 │   100        ~15000          明显下滑 + 易幻觉      │
 └──────────────────────────────────────────────────┘
 结论：无脑注入所有工具 = 又贵又不准。要"按需加载"（见下节）。
```

<callout emoji="⚠️"><p>这直接呼应第十五章 Context Engineering 的 Select 策略：不是"所有工具都给模型"，而是"当前任务相关的才给"。MCP 的 Roots + 动态连接就是实现手段——Agent 按用户意图只连相关 Server。</p></callout>

---

## 十一、动态工具加载：用 Roots 做"按需连接"

不要启动时连全部 Server。根据意图先路由，再连对应 Server：

```java
// 概念：意图分类后只连相关 MCP Server，控制注入工具数
String intent = classify(userQuestion);  // "订单" / "天气" / "知识"
List<McpTransport> transports = switch (intent) {
    case "订单" -> List.of(orderTransport);
    case "天气" -> List.of(weatherTransport);
    default     -> List.of(knowledgeTransport);
};
// 只为本次任务建立 Client，工具数控制在 5 以内
```

```text
   按需连接 vs 全量连接
 ┌──────────────────────────────────────────────────┐
 │ 全量：Host 常连 10 个 Server → 注入 80 工具 → 贵   │
 │ 按需：每次任务连 1-2 个 Server → 注入 <10 工具 → 准 │
 └──────────────────────────────────────────────────┘
```

---

## 十二、MCP 工具与框架原生 Tool 的取舍

同一个能力，是写成 MCP Server 还是框架原生 `@Tool`？决策表：

| 情况 | 选 MCP | 选原生 Tool |
|------|--------|-------------|
| 跨框架/跨语言复用 | ✅ | ❌ |
| 只在本框架内用 | ❌（过度） | ✅ |
| 需独立部署/扩缩 | ✅ | ❌ |
| 快速原型 | ❌ | ✅ |
| 要工具市场/治理 | ✅ | ❌ |

<callout emoji="💡"><p>经验法则：只有"会被多方复用"的能力才值得 MCP 化。团队内部一个小工具，框架原生 Tool 三行搞定，强行 MCP 化反而增加运维负担。</p></callout>

---

## 十三、跨框架版本协商深度排错

lesson01 提过 protocolVersion，这里给真实排错流程：

```text
   协商失败排查 SOP
 ┌──────────────────────────────────────────────────┐
 │ 1. 看 Client 支持的版本列表                       │
 │ 2. 看 Server 支持的版本列表                       │
 │ 3. 取交集；空集 → 升/降一方到共同版本            │
 │ 4. 常见坑：LangChain4j 新版 + 老 Python SDK       │
 │    → 把 Python SDK 升到含 2025-03-26 的版本       │
 │ 5. 用 Inspector 直接看 handshake 报文定位         │
 └──────────────────────────────────────────────────┘
```

---

## 十四、企业工具中台落地五步

把"跨框架互操作"从 demo 推到生产，按这五步走：

```text
   工具中台落地五步
 ┌──────────────────────────────────────────────────┐
 │ 1. 盘点：列出所有内部能力（查订单/查知识/发短信） │
 │ 2. 标准化：把复用率高的封装成 MCP Server          │
 │ 3. 治理：建工具注册中心（Owner/版本/权限）         │
 │ 4. 接入：各 Agent 框架通过 MCP Client 消费         │
 │ 5. 运营：调用审计 + 成本统计 + 健康检查           │
 └──────────────────────────────────────────────────┘
```

---

## 十五、面试追问：跨框架一致性怎么保

```text
问：不同框架对同一个 MCP 工具的理解会不一致吗？
答：不会。MCP 把工具语义（name/description/inputSchema）标准化了，
    框架只是把它转成自己的 Tool 对象，调用走同一 JSON-RPC。
    但"模型怎么用工具"因底层 LLM 不同会有差异——
    所以工具 description 要写得模型无关、语义清晰。
```

---

## 小结

这一节我们把 MCP 的"跨框架互操作"真正落了地：

- **本质**：框架是方言，MCP 是普通话；协议中立带来互操作；
- **LangChain4j**：用 `langchain4j-mcp` 的 `McpClient` 拉工具清单，注入 `AiServices`；
- **Spring AI**：`McpToolCallbackProvider` 聚合多 Server 工具，配置即接入；
- **跨语言**：Python 写一次 Server，Java 两框架 Client 复用，零重复；
- **中台化**：工具与框架解耦，企业级架构可换框架、可增工具；
- **注意**：协议版本协商、工具描述质量、治理成本三件事别忽视。

下一节我们跳出 MCP，看它的"兄弟协议"——**A2A（Agent-to-Agent）**，以及标准化趋势怎么重塑 Agent 架构。

---

## 延伸阅读 / 引用文档

1. LangChain4j MCP 集成文档：https://docs.langchain4j.dev/integrations/mcp/
2. Spring AI MCP Client 文档：https://docs.spring.io/spring-ai/reference/1.0/api/mcp/mcp-client-boot-starter.html
3. MCP 官方 Server 示例集合：https://github.com/modelcontextprotocol/servers
4. Google 发布的 A2A 协议（下一节详述）：https://github.com/google/A2A
5. LangChain4j GitHub 仓库：https://github.com/langchain4j/langchain4j
6. MCP 协议规范（Capabilities 与版本协商）：https://modelcontextprotocol.io/specification/2025-03-26

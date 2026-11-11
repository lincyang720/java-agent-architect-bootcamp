# 第十一章第2节：MCP 在 Java 中的实现方式：客户端、服务端开发指南

大家好，我是秋天。

上一节我们把 MCP 的原理、架构、五大原语讲透了。这一节是硬核实战：用 **Java 17** 把 MCP 的 **Client** 和 **Server** 都写出来，并且跑通一条"模型请求 → Client 调 Server → 拿回结果"的完整链路。

学完这一节，你将掌握：

1. 用官方 `io.modelcontextprotocol` Java SDK 写一个简单的 MCP Server（暴露一个查询天气的 Tool）；
2. 用同一套 SDK 写 MCP Client，连接上面那个 Server 并列出/调用工具；
3. 用 **Spring AI MCP** 把 MCP Server 接入 Spring Boot 工程，理解"声明式"写法；
4. stdio 与 SSE/HTTP 两种传输在 Java 里的配置差异；
5. 企业里"工具服务化"的工程结构怎么拆。

<callout emoji="💡"><p>版本说明：MCP Java SDK 迭代很快。本文基于 `io.modelcontextprotocol:mcp` 0.x 系列的核心 API 思路，方法名以官方最新文档为准；核心结构（Server 端注册 Tool、Client 端 session 调用）长期稳定。跑不通时先看官方 README 对一下方法签名。</p></callout>

---

## 一、Java 侧技术选型：两条路

在 Java 里落地 MCP，目前有两条主流路径，先给你一张选型图：

```text
            Java 实现 MCP 的两条路线
 ┌─────────────────────────────────────────────────┐
 │ 路线 A：官方 Java SDK（io.modelcontextprotocol） │
 │  - 协议层，最贴近规范                            │
 │  - 适合写"纯 MCP Server/Client"                  │
 │  - 传输：stdio / SSE / 自定义                    │
 │  - 例：把内部系统封装成 MCP 工具给任意 Host 用   │
 │                                                  │
 │ 路线 B：Spring AI MCP（spring-ai-mcp）           │
 │  - 在 Spring Boot 生态里声明式暴露/消费 MCP       │
 │  - 和 ChatClient、ToolCallback 无缝集成          │
 │  - 适合"我已经用 Spring AI 写 Agent"的场景       │
 └─────────────────────────────────────────────────┘

 判断：要复用给多框架 → 路线 A；Agent 本身就是 Spring AI → 路线 B
```

我两条都给你演示，因为你面试/落地时两种都可能遇到。

---

## 二、路线 A：官方 Java SDK 写 MCP Server

先建 Maven 模块 `ch11-mcp`。`pom.xml` 关键依赖：

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.qiutian</groupId>
  <artifactId>ch11-mcp-server</artifactId>
  <version>1.0.0</version>
  <properties>
    <maven.compiler.release>17</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>

  <dependencies>
    <!-- MCP 官方 Java SDK：核心 + stdio 传输 -->
    <dependency>
      <groupId>io.modelcontextprotocol</groupId>
      <artifactId>mcp</artifactId>
      <version>0.9.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
    <dependency>
      <groupId>io.modelcontextprotocol</groupId>
      <artifactId>mcp-server</artifactId>
      <version>0.9.0</version>
    </dependency>
    <dependency>
      <groupId>io.modelcontextprotocol</groupId>
      <artifactId>mcp-server-transport-stdio</artifactId>
      <version>0.9.0</version>
    </dependency>
    <!-- JSON 处理 -->
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
      <version>2.17.2</version>
    </dependency>
  </dependencies>
</project>
```

<callout emoji="⚠️"><p>版本号请以 Maven Central 上 `io.modelcontextprotocol` 的最新发布为准。SDK 把 `mcp`（BOM）、`mcp-server`、`mcp-client`、`mcp-server-transport-stdio`、`mcp-client-transport-*` 拆成了多个 artifact，按需要引入。本文代码演示的是稳定结构。</p></callout>

### 2.1 定义一个"天气查询"工具

我们用一个最简的 POJO 描述工具。真实场景里，下面这个"天气"可以是查数据库、调内部 SOAP 服务、读 Kafka——MCP Server 的价值就是把"脏活"封装掉。

```java
package com.qiutian.mcp;

import java.util.Map;

/**
 * 天气查询工具的业务实现。
 * 真实场景：这里会调气象局 API 或内部服务，返回结构化数据。
 */
public class WeatherService {

    /** 模拟查询，返回某城市的天气摘要 */
    public String query(String city) {
        // 实际项目这里用 WebClient / RestClient 调外部 API
        Map<String, String> mock = Map.of(
                "北京", "晴 12°C 西北风3级",
                "上海", "多云 16°C 东风2级",
                "深圳", "阵雨 22°C 南风4级"
        );
        return mock.getOrDefault(city, "未知城市，暂无数据");
    }
}
```

### 2.2 用 SDK 注册 Tool 并启动 stdio Server

下面的代码演示用 `McpServer` 注册一个 Tool，并通过 stdio 传输对外暴露：

```java
package com.qiutian.mcp;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransport;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.ServerCapabilities;
import io.modelcontextprotocol.spec.ToolSpecification;
import io.modelcontextprotocol.spec.Tool;
import io.modelcontextprotocol.spec.JsonSchema;
import io.modelcontextprotocol.spec.CallToolResult;
import io.modelcontextprotocol.spec.TextContent;

/**
 * 最小可运行 MCP Server（stdio 传输）。
 * 运行后由 Host（Client）通过进程管道拉起并通信。
 */
public class WeatherMcpServer {

    public static void main(String[] args) {
        // 1. 传输层：stdio（进程间管道）
        McpServerTransport transport = new StdioServerTransport();

        // 2. 工具 schema（JSON Schema 描述入参）
        Tool tool = Tool.builder()
                .name("query_weather")
                .description("查询指定城市的当前天气")
                .inputSchema(JsonSchema.object()
                        .property("city", JsonSchema.string("城市名，如 北京"))
                        .required("city")
                        .build())
                .build();

        ToolSpecification spec = ToolSpecification.builder().tool(tool).build();

        // 3. 业务逻辑：被调用时执行
        WeatherService svc = new WeatherService();

        // 4. 构建 Server
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("weather-mcp", "1.0.0")
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .tool(spec, (exchange, arguments) -> {
                    String city = (String) arguments.get("city");
                    String result = svc.query(city);
                    return CallToolResult.builder()
                            .content(TextContent.builder().text(result).build())
                            .build();
                })
                .build();

        // 5. 阻塞运行，等待 Client 连接
        server.start();
        System.out.println("Weather MCP Server 已启动 (stdio)");
        // 实际由 Host 管理生命周期；演示可加一个 shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
    }
}
```

<callout emoji="💡"><p>注意 `McpServer.sync(...)`：官方 SDK 同时提供 sync（同步）和 async（响应式 `McpAsyncServer`）。企业内部阻塞型工具（查 DB、调 SOAP）用 sync 即可；高并发网关型用 async。别一上来就 async，过度设计。</p></callout>

---

## 三、路线 A：官方 Java SDK 写 MCP Client

Client 通常跑在 Host（你的 Agent 主程序）里。下面演示连接上面那个 Server 并列出、调用工具：

```java
package com.qiutian.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.ClientCapabilities;
import io.modelcontextprotocol.spec.Tool;
import io.modelcontextprotocol.spec.CallToolResult;

import java.util.List;
import java.util.Map;

/**
 * MCP Client 示例：通过 stdio 拉起并连接 Weather Server。
 */
public class WeatherMcpClient {

    public static void main(String[] args) throws Exception {
        // 1. 传输：stdio，指定如何启动 Server 进程
        StdioClientTransport transport = StdioClientTransport.builder()
                .command(List.of("java", "-cp", "target/classes",
                        "com.qiutian.mcp.WeatherMcpServer"))
                .build();

        // 2. 建立 Client 会话
        McpSyncClient client = McpClient.sync(transport)
                .capabilities(ClientCapabilities.builder().roots(true).build())
                .build();

        client.initialize();

        // 3. 列出 Server 暴露的工具
        List<Tool> tools = client.listTools().tools();
        tools.forEach(t -> System.out.println("发现工具: " + t.name() + " -> " + t.description()));

        // 4. 调用工具
        CallToolResult result = client.callTool(
                "query_weather", Map.of("city", "北京"));
        System.out.println("调用结果: " + result.content());

        client.close();
    }
}
```

运行 `WeatherMcpClient`，你会看到：

```text
发现工具: query_weather -> 查询指定城市的当前天气
调用结果: [TextContent[text=晴 12°C 西北风3级]]
```

这就是一条**完整、可运行**的 MCP 链路：Client 拉起 Server 进程 → 握手 → 列工具 → 调用 → 拿结果。

<callout emoji="✅"><p>到这里你已经具备"MCP 客户端/服务端开发"的核心能力，这正是招聘 JD 里"有 MCP Client 或 Server 端开发经验"的直接对应项。把它写进简历的项目描述里，比空说"熟悉 MCP"有力得多。</p></callout>

---

## 四、传输层：stdio vs SSE/HTTP

上面是 stdio（本地进程）。企业里更多是"Server 独立部署，远程序 Client 调用"。MCP 支持 SSE（Server-Sent Events）和更新的 Streamable HTTP 传输。

```text
       stdio（本地）                    SSE / HTTP（远程）
 ┌──────────┐ 管道 ┌──────────┐    ┌──────────┐ HTTP ┌──────────┐
 │ Host     │◀────▶│ Server   │    │ Host     │◀────▶│ Server   │
 │ (Client) │      │(同机进程) │    │ (Client) │      │(远程服务) │
 └──────────┘      └──────────┘    └──────────┘      └──────────┘
 优点：零网络、低延迟         优点：可集中部署、统一鉴权、水平扩展
 缺点：同机、难集中管控      缺点：需处理鉴权/超时/断线重连
```

Spring AI MCP 的 `mcp-client-transport-webmvc` / `webflux` 模块就是干这个的。配置上，Client 端从 `StdioClientTransport` 换成 `HttpClientTransport` 并填 Server 的 URL 即可。

<callout emoji="⚠️"><p>远程 MCP 的最大坑是**鉴权**。stdio 模式下凭据在本地进程里相对安全；一旦走 HTTP，Server 端必须做认证（API Key / OAuth / mTLS），否则等于把内部工具裸奔到公网。第十二章我们会专门讲工具调用的权限隔离。</p></callout>

---

## 五、路线 B：Spring AI MCP 声明式写法

如果你已经用 Spring AI 写 Agent，最省事的是用 `spring-ai-mcp` 把工具声明出来。先看 Server 端：

`pom.xml` 增加：

```xml
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-mcp</artifactId>
  <version>1.0.0</version>
</dependency>
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-mcp-server-webmvc</artifactId>
  <version>1.0.0</version>
</dependency>
```

`application.yml`：

```yaml
spring:
  ai:
    mcp:
      server:
        name: weather-mcp-spring
        version: 1.0.0
        type: SYNC          # 同步模式
        enabled: true
```

用 `@Tool` 注解声明工具，Spring AI MCP 会自动把它注册进 MCP Server：

```java
package com.qiutian.mcp.spring;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

@Service
public class WeatherToolService {

    @Tool(name = "query_weather",
          description = "查询指定城市的当前天气")
    public String queryWeather(@ToolParam("城市名") String city) {
        return switch (city) {
            case "北京" -> "晴 12°C 西北风3级";
            case "上海" -> "多云 16°C 东风2级";
            case "深圳" -> "阵雨 22°C 南风4级";
            default -> "未知城市，暂无数据";
        };
    }
}
```

启动类只要是个普通 Spring Boot 应用即可，MCP Server 端点由 `spring-ai-mcp-server-webmvc` 自动暴露（默认 `/mcp` 之类的端点，具体看版本）。

Client 端（另一个 Spring Boot 应用）消费这个 Server：

```java
package com.qiutian.mcp.spring;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.McpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AgentController {

    private final ChatClient chatClient;
    private final McpToolCallbackProvider mcpProvider;

    public AgentController(ChatClient.Builder builder,
                          McpToolCallbackProvider mcpProvider) {
        this.chatClient = builder.build();
        this.mcpProvider = mcpProvider;
    }

    @GetMapping("/ask")
    public String ask(String q) {
        ToolCallback[] callbacks = mcpProvider.getToolCallbacks();
        return chatClient.prompt()
                .user(q)
                .tools(callbacks)   // 把 MCP 工具注入模型
                .call()
                .content();
    }
}
```

<callout emoji="💡"><p>看到 `mcpProvider.getToolCallbacks()` 了吗？这就是"跨框架互操作"的魔法：不管 Server 是 Python、Go 还是 Java 写的，只要它说 MCP 语言，Spring AI 这边一行 `tools(callbacks)` 就能用。Lesson03 我们专门展开 LangChain4j / Spring AI 集成 MCP 的细节。</p></callout>

---

## 六、工程结构：怎么把"工具服务化"做对

企业里你不能把所有 Tool 写在一个 main 里。我给你一个推荐的模块拆分：

```text
ch11-mcp/                          （Maven 多模块）
├── mcp-api                共享的 Tool 入参/出参 DTO、错误码
├── mcp-server-weather     Weather Server（可独立部署）
├── mcp-server-db          数据库查询 Server（持 DB 连接+权限）
├── mcp-server-fs          文件系统 Server（持 Roots 白名单）
└── mcp-host-agent         你的 Agent 主程序（含多个 Client）
```

每个 Server 独立 Maven 模块、独立部署、独立扩缩容。Host 通过配置声明连哪些 Server，而不是硬编码。这样：

- 换模型厂商？Host 改一下 ChatClient 即可，Server 不动；
- 加工具？加一个 Server 模块，Host 配置里加一条连接；
- 权限出问题？责任边界清晰——凭据只在对应 Server。

<callout emoji="✅"><p>这种"工具服务化"拆分，正是大厂 Agent 平台的标准做法（参考百度文心、阿里通义、字节的 Agent 工具市场思路）。面试时能画出这个结构，直接体现架构能力。</p></callout>

---

## 七、调试与排错：MCP Inspector

MCP 官方提供了一个可视化调试工具 **MCP Inspector**，能连上你的 Server，列出工具、手动调用、看 JSON-RPC 报文。强烈建议你跑通 Server 后用它验证：

```bash
# 全局安装后启动（需 Node 环境）
npx @modelcontextprotocol/inspector
```

在 UI 里填 Server 的启动命令（stdio 模式填 `java -cp ...`）或 URL（SSE 模式），就能像 Postman 一样调试你的 MCP Server。

```text
        MCP Inspector 调试视图（示意）
 ┌─────────────────────────────────────────┐
 │ Server: weather-mcp                      │
 │ [Tools]  query_weather                   │
 │   输入 city=北京                          │
 │   输出: 晴 12°C 西北风3级                 │
 │ [Resources] (无)                         │
 │ [Logs]  handshake ok, protocol 2024-11-05│
 └─────────────────────────────────────────┘
```

---

## 八、一个常见反模式：把业务全塞进 Tool

我见过很多同学写 MCP Server，把一整套业务流程（查数据→判断→再查→生成报告）全写在一个 Tool 里。这是反模式。

```text
  反模式：巨型 Tool               推荐：原子 Tool + Host 编排
  ┌──────────────┐              ┌────────┐ ┌────────┐ ┌────────┐
  │ query_and_   │              │query_  │ │judge_  │ │report_ │
  │ report(city) │              │weather │ │risk    │ │gen    │
  │ 内部黑盒      │              └───┬────┘ └───┬────┘ └───┬────┘
  └──────────────┘                  ▼          ▼          ▼
                                Host 编排（可解释、可审计）
```

<callout emoji="⚠️"><p>Tool 应该是"单一能力、可组合、可观测"的原子单元。把流程逻辑留在 Host 侧，才能做到第十二章要讲的"行为审计"——每一步调用都有记录、可回溯。巨型 Tool 一旦出错，你根本不知道卡在哪一步。</p></callout>

---

## 九、性能与超时：企业落地的两个细节

1. **超时**：MCP 调用本质是远程过程调用。Client 端必须设超时（尤其 SSE 模式），否则一个慢 Tool 拖垮整个 Agent 响应。
2. **并发**：stdio Server 是单进程，并发能力差；高吞吐场景用 HTTP Server + 线程池，或把无状态 Tool 做成无状态服务水平扩展。

```yaml
# Spring AI MCP Client 超时示例（理念，具体字段看版本）
spring:
  ai:
    mcp:
      client:
        request-timeout: 5000ms
        connections:
          - id: weather
            url: http://mcp-internal/weather
```

---

## 十一、用 Spring AI MCP 连官方 Python Server 的完整示例

前面 lesson01 你用 Python 写了 filesystem server。这里把它和 Spring AI 的 Client 接起来，证明"跨语言复用"在 Java 侧也能跑通。先放 Python Server（带路径穿越防护加固）：

```python
"""带路径穿越防护的 filesystem MCP Server（stdio）"""
import asyncio
from pathlib import Path
from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import Tool, TextContent

app = Server("filesystem-demo")
ROOT = Path("/Users/qiutian/workspace").resolve()

@app.list_tools()
async def list_tools():
    return [Tool(
        name="read_file",
        description="读取白名单目录内的文本文件，返回前 4000 字符",
        inputSchema={"type": "object",
                     "properties": {"path": {"type": "string"}},
                     "required": ["path"]})]

@app.call_tool()
async def call_tool(name, arguments):
    if name != "read_file":
        return [TextContent(type="text", text="unknown")]
    target = (ROOT / arguments["path"]).resolve()
    if not str(target).startswith(str(ROOT)):
        return [TextContent(type="text", text="ERROR: 越权路径被拒绝")]
    return [TextContent(type="text", text=target.read_text(encoding="utf-8")[:4000])]

async def main():
    async with stdio_server() as (r, w):
        await app.run(r, w, app.create_initialization_options())

if __name__ == "__main__":
    asyncio.run(main())
```

Spring AI 侧 `application.yml` 把该 Server 声明为 stdio Client：

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: true
        servers:
          fs:
            command: ["python", "/opt/mcp/filesystem_server.py"]
            type: STDIO
```

```java
// Agent 侧：直接把 Python Server 的工具注入模型
@GetMapping("/read")
public String read(String p) {
    return chatClient.prompt()
        .user("请读取文件 " + p + " 并总结要点")
        .tools(mcpProvider.getToolCallbacks())
        .call().content();
}
```

<callout emoji="✅"><p>这段演示的价值：你的"查文件"能力是 Python 写的，但 Spring AI 的 Java Agent 零改动复用。这正是 lesson03 要展开的"跨框架互操作"的最小可运行证据。</p></callout>

---

## 十二、异步 Server：高并发场景怎么写

前面都是 sync。当你把 MCP Server 当"网关型工具"（被很多 Agent 高频调用）时，应该用响应式 `McpAsyncServer` 扛并发：

```java
package com.qiutian.mcp;

import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransport;
import reactor.core.publisher.Mono;

/** 异步 Server 骨架：适合高并发工具网关 */
public class AsyncMcpServer {

    public static void main(String[] args) {
        StdioServerTransport transport = new StdioServerTransport();
        McpAsyncServer server = McpServer.async(transport)
                .serverInfo("async-weather", "1.0.0")
                .build();

        // 工具注册返回 Mono，天然适配背压与并发
        server.registerTool(
                buildToolSpec(),
                (exchange, args) -> Mono.fromCallable(() -> {
                    String city = (String) args.get("city");
                    return buildResult("晴 " + city);
                }))
            .subscribe();

        server.start();
    }

    private static Object buildToolSpec() { return new Object(); }
    private static Object buildResult(String s) { return new Object(); }
}
```

<callout emoji="💡"><p>sync 还是 async 的取舍很简单：工具本身是阻塞型（查 DB、调 SOAP）→ sync 够用；Server 是高吞吐网关、要水平扩展 → async。别一上来就 async，过度设计。</p></callout>

---

## 十三、MCP Server 的"能力声明"最佳实践

Server 在 `initialize` 时声明 capabilities，这决定了 Client 能用哪些特性：

```java
ServerCapabilities caps = ServerCapabilities.builder()
        .tools(true)        // 暴露工具
        .resources(false)   // 是否暴露 Resources
        .prompts(false)     // 是否暴露 Prompts
        .logging()          // 支持日志回传（调试有用）
        .build();
```

```text
   capabilities 协商示意
 ┌──────────────────────────────────────────────────┐
 │ Client 声明: {roots:true, sampling:false}         │
 │ Server 声明: {tools:true, resources:false}        │
 │ 协商结果: Client 可列/调工具，但不能请求 resources│
 │   → 双方只在交集能力上交互，避免调用不支持的特性   │
 └──────────────────────────────────────────────────┘
```

---

## 十四、生产部署清单（Server 侧）

把你写的 MCP Server 推向生产，对照这份清单：

| 项 | 检查点 |
|----|--------|
| 鉴权 | stdio 本地可省；SSE/HTTP 必须 API Key/OAuth/mTLS |
| 日志 | 用 `logging()` capability 回传，但**绝不打敏感参数** |
| 超时 | Client 侧设 request-timeout，Server 侧设执行上限 |
| 限流 | 远程 Server 接网关限流，防单 Agent 打爆 |
| 版本 | Server 版本号进 ServerInfo，便于排查 |
| 错误 | 返回结构化错误，别抛裸异常导致 Client 卡死 |

```yaml
# 远程 Server 的网关配置示例（Nginx 思路）
location /mcp/weather {
  auth_request /auth;                       # 1. 鉴权
  limit_req zone=mcp_limit burst=10;        # 2. 限流
  proxy_pass http://mcp-weather-svc;        # 3. 转发
}
```

---

## 十五、常见错误与修复

1. **`Method not found`**：Client 调了 Server 没注册的 method，检查 capabilities 与工具注册。
2. **stdio 死锁**：Server 把日志打到 stdout，污染 JSON-RPC 流。日志必须走 stderr 或文件。
3. **中文乱码**：stdio 传输用 UTF-8，确保进程 `-Dfile.encoding=UTF-8`。
4. **Client 拿不到工具**：注册是异步的，`initialize` 后监听 ready 事件再 `listTools`。

<callout emoji="⚠️"><p>第 2 条是 stdio 头号坑：MCP 用 stdout 传 JSON-RPC，你一旦 `System.out.println` 调试，协议流就废了。调试日志一律 `System.err` 或写文件。</p></callout>

---

## 十六、面试追问：MCP Server 怎么保证高可用

```text
问：你的 MCP Server 挂了，Agent 怎么办？
答：① Server 无状态化，水平扩展 + 负载均衡；
   ② Client 侧做降级（工具不可用 → 模型降级/转人工）；
   ③ 关键工具多实例 + 健康检查；
   ④ 用 Durable Execution 保长任务不丢。
```

---

## 十、小结与下一节预告

这一节我们用 Java 17 把 MCP 的客户端和服务端都跑通了：

- **路线 A**（官方 SDK）：手写 `McpServer`/`McpClient`，最贴近协议，适合纯工具服务；
- **路线 B**（Spring AI MCP）：`@Tool` 注解 + `McpToolCallbackProvider`，适合已有 Spring AI Agent；
- **传输**：stdio 本地快、HTTP 远程可集中管控，远程必须加鉴权；
- **工程**：工具服务化拆分，原子 Tool + Host 编排，便于审计与扩展。

下一节我们专门讲"跨框架互操作"：LangChain4j 怎么接 MCP、Spring AI 怎么接 MCP、以及多个框架共享受同一个 MCP Server 的真实场景。

---

## 延伸阅读 / 引用文档

1. MCP Java SDK GitHub：https://github.com/modelcontextprotocol/java-sdk
2. Spring AI MCP 官方文档：https://docs.spring.io/spring-ai/reference/1.0/api/mcp/mcp-overview.html
3. MCP 官方 Java 快速开始（Server）：https://modelcontextprotocol.io/quickstart/server
4. MCP 官方 Java 快速开始（Client）：https://modelcontextprotocol.io/quickstart/client
5. MCP Inspector 调试工具：https://github.com/modelcontextprotocol/inspector
6. JSON-RPC 2.0 规范：https://www.jsonrpc.org/specification

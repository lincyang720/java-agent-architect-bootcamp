package com.qiutian.agent.ch11_mcp;

import com.qiutian.agent.common.Demo;

import java.util.Map;

/**
 * 第十一章第2节配套：最小 MCP 风格 Server 演示（纯 Java，离线可跑）。
 *
 * <p>本 Demo 用 {@link MinimalMcpServer} 暴露两个工具 {@code echo} 与 {@code add}，
 * 并依次演示 JSON-RPC 的 {@code initialize} → {@code tools/list} → {@code tools/call} 三次握手。
 * 真实生产里 Server 由 Host（Client）通过 stdio 进程或 HTTP 拉起；这里为离线演示，
 * 直接用「进程内 handle 调用」模拟传输，重点放在「协议结构」与「工具注册表」上。
 *
 * <p>可用官方 SDK 等价写法（思路，方法名以官方最新版为准）：
 * <pre>{@code
 * McpSyncServer server = McpServer.sync(transport)
 *     .serverInfo("minimal-mcp", "1.0.0")
 *     .capabilities(ServerCapabilities.builder().tools(true).build())
 *     .tool(spec, (exchange, args) -> CallToolResult.builder()
 *         .content(TextContent.builder().text(result).build()).build())
 *     .build();
 * server.start();
 * }</pre>
 */
public class McpServerDemo {

    /** 构造一个带 echo / add 两个工具的最小 Server（被本类与 McpClientDemo 复用）。 */
    public static MinimalMcpServer newDemoServer() {
        return new MinimalMcpServer("minimal-mcp", "1.0.0")
                .register(new MinimalMcpServer.McpTool(
                        "echo",
                        "原样返回传入的文本",
                        Map.of("type", "object",
                                "properties", Map.of("text", Map.of("type", "string")),
                                "required", java.util.List.of("text")),
                        args -> String.valueOf(args.getOrDefault("text", ""))))
                .register(new MinimalMcpServer.McpTool(
                        "add",
                        "把两个整数相加",
                        Map.of("type", "object",
                                "properties", Map.of(
                                        "a", Map.of("type", "integer"),
                                        "b", Map.of("type", "integer")),
                                "required", java.util.List.of("a", "b")),
                        args -> {
                            long a = ((Number) args.getOrDefault("a", 0)).longValue();
                            long b = ((Number) args.getOrDefault("b", 0)).longValue();
                            return "sum=" + (a + b);
                        }));
    }

    public static void main(String[] args) {
        Demo.banner("McpServerDemo：最小 MCP 风格 Server（initialize / tools/list / tools/call）");

        MinimalMcpServer server = newDemoServer();

        // 1) initialize：协议握手，声明能力与版本
        Demo.note("① 收到客户端的 initialize 请求，返回协议版本与能力声明");
        String initReq = Json.write(Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "initialize", "params", Map.of()));
        String initResp = server.handle(initReq);
        System.out.println(initResp);

        // 2) tools/list：列出本 Server 暴露的全部工具
        Demo.note("② 客户端列工具：tools/list，返回 echo / add 的 schema");
        String listResp = server.handle(Json.write(Map.of(
                "jsonrpc", "2.0", "id", 2, "method", "tools/list", "params", Map.of())));
        System.out.println(listResp);

        // 3) tools/call：调用 echo
        Demo.note("③ 调用工具 echo，arguments={text:\"你好 MCP\"}");
        String echoResp = server.handle(Json.write(Map.of(
                "jsonrpc", "2.0", "id", 3, "method", "tools/call",
                "params", Map.of("name", "echo", "arguments", Map.of("text", "你好 MCP")))));
        System.out.println(echoResp);

        // 4) tools/call：调用 add
        Demo.note("④ 调用工具 add，arguments={a:3,b:4}");
        String addResp = server.handle(Json.write(Map.of(
                "jsonrpc", "2.0", "id", 4, "method", "tools/call",
                "params", Map.of("name", "add", "arguments", Map.of("a", 3, "b", 4)))));
        System.out.println(addResp);

        // 5) 未注册方法：返回标准 JSON-RPC error
        Demo.note("⑤ 调用未注册方法，应返回 -32601 Method not found");
        String errResp = server.handle(Json.write(Map.of(
                "jsonrpc", "2.0", "id", 5, "method", "foo/bar", "params", Map.of())));
        System.out.println(errResp);

        Demo.note("小结：Server 已用最小代码跑通 MCP 三次握手；真实部署只需把 handle() 接到"
                + " stdio/HTTP 传输，并把业务函数注册进工具表即可（见 lesson02 官方 SDK 写法）。");
    }
}

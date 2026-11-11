package com.qiutian.agent.ch11_mcp;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 纯 Java 实现的「最小 MCP 风格」服务端引擎（不依赖官方 {@code io.modelcontextprotocol} SDK）。
 *
 * <p>它演示了 MCP 协议最内核的三件事：
 * <ol>
 *   <li>JSON-RPC 2.0 信封（{@code jsonrpc/id/method/params} 与 {@code result/error}）；</li>
 *   <li>三个核心方法的语义：{@code initialize} / {@code tools/list} / {@code tools/call}；</li>
 *   <li>工具注册表（Tool Registry）——把一个 Java 函数包装成可被协议暴露的「工具」。</li>
 * </ol>
 *
 * <p>为离线可跑，这里用「进程内 {@link #handle(String)} 调用」模拟传输层（真实 MCP 走 stdio / SSE / HTTP）。
 * 返回的 JSON 结构尽量贴近 MCP 规范（protocolVersion、capabilities、serverInfo、content、isError）。
 */
public class MinimalMcpServer {

    /** 一个工具：名称、描述、JSON Schema 入参、业务处理方法（入参 arguments → 文本结果）。 */
    public record McpTool(
            String name,
            String description,
            Map<String, Object> inputSchema,
            Function<Map<String, Object>, String> handler) {
    }

    /** MCP 方法未找到 / 参数错误时的错误载体。 */
    static final class McpError extends RuntimeException {
        final int code;

        McpError(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private final String serverName;
    private final String version;
    private final Map<String, McpTool> tools = new ConcurrentHashMap<>();

    public MinimalMcpServer(String serverName, String version) {
        this.serverName = serverName;
        this.version = version;
    }

    /** 注册一个工具（可链式调用）。 */
    public MinimalMcpServer register(McpTool tool) {
        tools.put(tool.name(), tool);
        return this;
    }

    /**
     * 处理一条 JSON-RPC 请求字符串，返回 JSON-RPC 响应字符串。
     * 这是 Server 对外的统一入口：解析 → 按 method 分发 → 组装 result/error。
     */
    public String handle(String requestJson) {
        Map<String, Object> req = Json.read(requestJson);
        Object id = req.get("id");
        String method = String.valueOf(req.get("method"));
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) req.getOrDefault("params", Map.of());
        try {
            Map<String, Object> result = dispatch(method, params);
            return Json.write(response(id, result));
        } catch (McpError e) {
            return Json.write(errorResponse(id, e.code, e.getMessage()));
        } catch (Exception e) {
            return Json.write(errorResponse(id, -32603, "Internal error: " + e.getMessage()));
        }
    }

    private Map<String, Object> dispatch(String method, Map<String, Object> params) {
        return switch (method) {
            case "initialize" -> initialize();
            case "tools/list" -> toolsList();
            case "tools/call" -> toolsCall(params);
            default -> throw new McpError(-32601, "Method not found: " + method);
        };
    }

    /** initialize：返回协议版本、能力声明、服务端信息（对应 MCP 握手）。 */
    private Map<String, Object> initialize() {
        Map<String, Object> capabilities = Map.of("tools", Map.of("listChanged", false));
        Map<String, Object> serverInfo = Map.of("name", serverName, "version", version);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("protocolVersion", "2024-11-05");
        result.put("capabilities", capabilities);
        result.put("serverInfo", serverInfo);
        return result;
    }

    /** tools/list：返回所有已注册工具的描述（名称、描述、inputSchema）。 */
    private Map<String, Object> toolsList() {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("tools", List.copyOf(tools.values().stream()
                .map(t -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("name", t.name());
                    m.put("description", t.description());
                    m.put("inputSchema", t.inputSchema());
                    return m;
                })
                .toList()));
        return result;
    }

    /** tools/call：按 name 找到工具，用 arguments 调用其 handler，把结果封装成 content[]。 */
    private Map<String, Object> toolsCall(Map<String, Object> params) {
        String name = String.valueOf(params.get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = (Map<String, Object>) params.getOrDefault("arguments", Map.of());
        McpTool tool = tools.get(name);
        if (tool == null) {
            throw new McpError(-32602, "Unknown tool: " + name);
        }
        String text = tool.handler().apply(arguments);
        Map<String, Object> contentItem = new java.util.LinkedHashMap<>();
        contentItem.put("type", "text");
        contentItem.put("text", text);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("content", List.of(contentItem));
        result.put("isError", false);
        return result;
    }

    private Map<String, Object> response(Object id, Map<String, Object> result) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", result);
        return m;
    }

    private Map<String, Object> errorResponse(Object id, int code, String message) {
        Map<String, Object> error = new java.util.LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("error", error);
        return m;
    }
}

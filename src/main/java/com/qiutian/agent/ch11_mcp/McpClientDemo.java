package com.qiutian.agent.ch11_mcp;

import com.qiutian.agent.common.Demo;

import java.util.Map;

/**
 * 第十一章第2节配套：最小 MCP 风格 Client 演示（纯 Java，离线可跑）。
 *
 * <p>Client 的职责：① 构造 JSON-RPC 请求；② 把请求交给 Server（真实场景走 stdio / HTTP 传输，
 * 这里用进程内 {@link MinimalMcpServer#handle(String)} 模拟）；③ 解析响应里的 {@code result.content}。
 * 注意 Client 与 Server 通过「同一套协议 + 同一份工具注册表」解耦——Server 怎么实现 Client 不关心。
 */
public class McpClientDemo {

    public static void main(String[] args) {
        Demo.banner("McpClientDemo：最小 MCP 风格 Client（构造 tools/call 并解析结果）");

        // 复用 McpServerDemo 的工厂拿到同一个 Server（真实场景这里是一个独立进程 / 远端 URL）
        MinimalMcpServer server = McpServerDemo.newDemoServer();

        // —— 第一步：initialize 握手 ——
        Demo.note("① Client 发送 initialize，与服务端握手");
        String initReq = Json.write(Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "initialize", "params", Map.of()));
        Map<String, Object> initResp = Json.read(server.handle(initReq));
        System.out.println("握手返回: " + initResp.get("result"));

        // —— 第二步：tools/list 发现工具 ——
        Demo.note("② Client 发送 tools/list，发现 Server 暴露的工具");
        Map<String, Object> listResp = Json.read(server.handle(Json.write(Map.of(
                "jsonrpc", "2.0", "id", 2, "method", "tools/list", "params", Map.of()))));
        System.out.println("可用工具: " + listResp.get("result"));

        // —— 第三步：tools/call 调用 add，并解析 content[0].text ——
        Demo.note("③ Client 构造 tools/call（add, a=10, b=32）并发往 Server");
        String callReq = Json.write(Map.of(
                "jsonrpc", "2.0", "id", 3, "method", "tools/call",
                "params", Map.of("name", "add", "arguments", Map.of("a", 10, "b", 32))));
        System.out.println("发出请求: " + callReq);

        Map<String, Object> callResp = Json.read(server.handle(callReq));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) callResp.get("result");
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> content =
                (java.util.List<Map<String, Object>>) result.get("content");
        String text = String.valueOf(content.get(0).get("text"));
        boolean isError = Boolean.TRUE.equals(result.get("isError"));

        Demo.note("④ 解析服务端 result.content[0].text = \"" + text + "\"，isError=" + isError);

        // 真实 Agent 里，这个 text 会被模型当作「工具返回」继续推理；无密钥也不影响（纯协议演示）。
        Demo.note("小结：Client 已完成「请求构造 → 传输 → 解析」闭环；把它嵌进 ChatClient 的工具循环，"
                + "即等价于 Spring AI 的 tools(callbacks) 调用（见 McpInteropDemo 的注释写法）。");
    }
}

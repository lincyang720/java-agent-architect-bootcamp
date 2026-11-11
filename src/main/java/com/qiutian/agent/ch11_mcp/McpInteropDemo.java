package com.qiutian.agent.ch11_mcp;

import com.qiutian.agent.common.Demo;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 第十一章第3节配套：跨框架互操作演示（纯 Java，离线可跑）。
 *
 * <p>核心论点：同一套工具只要登记进「与框架无关的 {@link ToolRegistry}」，
 * 就能被 LangChain4j、Spring AI 等任意框架的「适配桩」以各自风格消费。
 * 本 Demo 用两个桩类 {@link LangChain4jAdapter} / {@link SpringAiAdapter} 调用同一个注册表，
 * 证明「定义一次、多框架复用」。下方注释给出 Spring AI 标准接法的真实代码。
 */
public class McpInteropDemo {

    /**
     * LangChain4j 适配桩：演示「LangChain4j 侧如何把注册表里的工具当 @Tool 用」。
     * 真实写法见下方注释；这里用桩直接调 registry.get(name).handler()。
     */
    static final class LangChain4jAdapter {
        private final ToolRegistry registry;

        LangChain4jAdapter(ToolRegistry registry) {
            this.registry = registry;
        }

        /** 模拟 LangChain4j 的 AiServices 工具调用：按名取工具并执行。 */
        String invoke(String name, Map<String, Object> args) {
            ToolRegistry.ToolSpec spec = registry.get(name);
            if (spec == null) {
                return "[LangChain4j] 未找到工具 " + name;
            }
            return "[LangChain4j] 调用 " + name + " => " + spec.handler().apply(args);
        }

        /** 列出可被 AiServices 暴露的工具名（对应 @Tool 扫描结果）。 */
        List<String> discover() {
            return registry.all().stream().map(ToolRegistry.ToolSpec::name).toList();
        }
    }

    /**
     * Spring AI 适配桩：演示「Spring AI 侧如何把注册表里的工具转成 ToolCallback」。
     * 真实写法见下方注释；这里用桩直接调 registry.get(name).handler()。
     */
    static final class SpringAiAdapter {
        private final ToolRegistry registry;

        SpringAiAdapter(ToolRegistry registry) {
            this.registry = registry;
        }

        String invoke(String name, Map<String, Object> args) {
            ToolRegistry.ToolSpec spec = registry.get(name);
            if (spec == null) {
                return "[SpringAI] 未找到工具 " + name;
            }
            return "[SpringAI] 调用 " + name + " => " + spec.handler().apply(args);
        }

        List<String> discover() {
            return registry.all().stream().map(ToolRegistry.ToolSpec::name).toList();
        }
    }

    public static void main(String[] args) {
        Demo.banner("McpInteropDemo：同一套工具，被 LangChain4j / Spring AI 双框架接入");

        // ① 只定义一次工具（与框架无关）
        ToolRegistry registry = new ToolRegistry()
                .register(new ToolRegistry.ToolSpec(
                        "echo",
                        "回显文本",
                        Map.of("type", "object",
                                "properties", Map.of("text", Map.of("type", "string"))),
                        arguments -> String.valueOf(arguments.getOrDefault("text", ""))))
                .register(new ToolRegistry.ToolSpec(
                        "add",
                        "加法",
                        Map.of("type", "object",
                                "properties", Map.of("a", Map.of("type", "integer"),
                                        "b", Map.of("type", "integer"))),
                        arguments -> {
                            long a = ((Number) arguments.getOrDefault("a", 0)).longValue();
                            long b = ((Number) arguments.getOrDefault("b", 0)).longValue();
                            return "sum=" + (a + b);
                        }))
                .register(new ToolRegistry.ToolSpec(
                        "weather",
                        "查天气",
                        Map.of("type", "object",
                                "properties", Map.of("city", Map.of("type", "string"))),
                        arguments -> "晴 " + arguments.getOrDefault("city", "未知")));

        // ② 同一个 registry，喂给两个框架适配桩
        LangChain4jAdapter lc4j = new LangChain4jAdapter(registry);
        SpringAiAdapter springAi = new SpringAiAdapter(registry);

        Demo.note("LangChain4j 发现的工具: " + lc4j.discover());
        Demo.note("Spring AI 发现的工具: " + springAi.discover());

        Demo.note("—— 用 LangChain4j 适配桩调用 ——");
        System.out.println(lc4j.invoke("add", Map.of("a", 2, "b", 5)));
        System.out.println(lc4j.invoke("weather", Map.of("city", "北京")));

        Demo.note("—— 用 Spring AI 适配桩调用（同一注册表）——");
        System.out.println(springAi.invoke("add", Map.of("a", 2, "b", 5)));
        System.out.println(springAi.invoke("echo", Map.of("text", "跨框架复用")));

        Demo.note("要点：工具只在 ToolRegistry 定义一次；LangChain4j / Spring AI 各自写适配层，"
                + "无需为不同框架重复实现业务逻辑。");

        // ===== 真实生产写法（注释，pom 已含 spring-ai-starter-mcp-server / -client）=====
        // —— Server 端：用 @Tool 声明，spring-ai-mcp-server 自动暴露 ——
        // @Service
        // public class WeatherToolService {
        //     @Tool(name = "weather", description = "查询指定城市的当前天气")
        //     public String weather(@ToolParam("城市名") String city) { return "晴 " + city; }
        // }
        // # application.yml
        // # spring.ai.mcp.server.name=weather-mcp
        // # spring.ai.mcp.server.version=1.0.0
        // # spring.ai.mcp.server.type=SYNC
        //
        // —— Client 端：spring-ai-starter-mcp-client 拉起 Server 并把工具注入 ChatClient ——
        // @GetMapping("/ask")
        // public String ask(String q, McpToolCallbackProvider mcpProvider) {
        //     ToolCallback[] callbacks = mcpProvider.getToolCallbacks();
        //     return chatClient.prompt().user(q).tools(callbacks).call().content();
        // }
        // # application.yml（stdio 拉起 Python/Java Server）
        // # spring.ai.mcp.client.servers.weather.command=java -jar weather-server.jar
        // # spring.ai.mcp.client.servers.weather.type=STDIO
        Demo.note("上方注释即 spring-ai-starter-mcp-server / -client 的标准接入写法（pom 已含这两个 starter）。");
    }
}

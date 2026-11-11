package com.qiutian.agent.ch11_mcp;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 跨框架互操作的核心：一个「与框架无关」的工具注册表。
 *
 * <p>无论下游是 LangChain4j、Spring AI 还是 Python/Go 写的 Host，
 * 只要工具先登记进这一个 {@link ToolRegistry}，就能被任何框架的适配层以各自的风格消费。
 * 这正是 MCP 存在的意义——「工具定义一次，任意框架接入」。
 */
public class ToolRegistry {

    /** 工具规格：名称、描述、JSON Schema 入参、业务处理方法。 */
    public record ToolSpec(
            String name,
            String description,
            Map<String, Object> inputSchema,
            Function<Map<String, Object>, String> handler) {
    }

    private final Map<String, ToolSpec> registry = new ConcurrentHashMap<>();

    /** 注册一个工具。 */
    public ToolRegistry register(ToolSpec spec) {
        registry.put(spec.name(), spec);
        return this;
    }

    /** 按名称取工具规格；找不到返回 null。 */
    public ToolSpec get(String name) {
        return registry.get(name);
    }

    /** 全部已注册工具（用于 list 场景）。 */
    public Collection<ToolSpec> all() {
        return registry.values();
    }
}

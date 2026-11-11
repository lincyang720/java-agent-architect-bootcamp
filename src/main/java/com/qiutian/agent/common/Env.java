package com.qiutian.agent.common;

import java.util.Optional;

/**
 * 环境变量 / 系统属性读取工具。
 * 课程示例统一从这里读取 API Key（OPENAI_API_KEY / DASHSCOPE_API_KEY 等），
 * 便于在不硬编码密钥的前提下运行示例。
 */
public final class Env {

    private Env() {
    }

    /**
     * 优先读环境变量，其次读 JVM 系统属性（-Dkey=value）。
     *
     * @return 取值，未设置或为空返回 null
     */
    public static String get(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            v = System.getProperty(name);
        }
        return (v == null || v.isBlank()) ? null : v;
    }

    public static String getOrDefault(String name, String def) {
        String v = get(name);
        return v == null ? def : v;
    }

    public static Optional<String> optional(String name) {
        return Optional.ofNullable(get(name));
    }
}

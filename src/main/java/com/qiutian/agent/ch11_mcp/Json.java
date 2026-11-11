package com.qiutian.agent.ch11_mcp;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 极简 JSON 工具：基于 Jackson（pom 已间接依赖 spring-boot-starter-web → jackson-databind）。
 * 仅用于把 {@code Map/List} 序列化成 JSON 字符串、把 JSON 字符串解析成 {@code Map}，
 * 让本包所有 Demo 在不引入官方 MCP SDK 的前提下，也能构造/解析符合协议的 JSON-RPC 报文。
 */
final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    /** 把任意可被 Jackson 序列化的对象（Map/List/POJO）转成 JSON 字符串。 */
    static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException("JSON 序列化失败", e);
        }
    }

    /** 把 JSON 对象字符串解析成 {@code Map<String,Object>}（数组→List，对象→Map）。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> read(String json) {
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            throw new RuntimeException("JSON 解析失败: " + json, e);
        }
    }
}

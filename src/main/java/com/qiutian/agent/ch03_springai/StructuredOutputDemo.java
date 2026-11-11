package com.qiutian.agent.ch03_springai;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.qiutian.agent.common.Demo;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Lesson04：结构化输出 —— 用 ChatClient.entity() 把模型响应映射到 Java POJO。
 *
 * 思路：先定义 POJO（加描述注解），框架据此生成 JSON Schema 约束模型输出，
 * 再把模型返回的 JSON 反序列化回强类型对象，format 约束在协议层完成。
 */
public class StructuredOutputDemo {

    /** 目标 POJO：描述注解会写进 JSON Schema 的 description，提升抽取准确率。 */
    @JsonClassDescription("某演员出演过的电影作品列表")
    public record ActorFilms(
            @JsonPropertyDescription("演员姓名") String actor,
            @JsonPropertyDescription("该演员出演的代表作电影名称列表") java.util.List<String> movies
    ) {
    }

    public static void main(String[] args) {
        Demo.banner("StructuredOutputDemo · 结构化输出");

        if (!Demo.ensureKey("OPENAI_API_KEY")) {
            return;
        }

        try (ConfigurableApplicationContext ctx = Ch03Support.bootstrap()) {
            ChatClient chatClient = Ch03Support.openAiClient(ctx);

            Demo.note("定义 POJO（ActorFilms）+ 描述注解，交给 entity() 直接映射");
            ActorFilms result = chatClient.prompt()
                    .user(u -> u.text("请列出演员 {actor} 的 3 部代表作电影，只输出结构化结果。")
                                .param("actor", "巩俐"))
                    .call()
                    .entity(ActorFilms.class);

            System.out.println("【演员】 " + result.actor());
            System.out.println("【作品】 " + result.movies());
        }

        Demo.note("示例结束。结构化输出 ≠ 100% 可靠，生产需叠加 Bean Validation 与降级重试。");
        Demo.note("（也可手工用 BeanOutputConverter<T> 拼 prompt + 解析，思路一致。）");
    }
}

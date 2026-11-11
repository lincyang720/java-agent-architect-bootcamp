package com.qiutian.agent.ch03_springai;

import com.qiutian.agent.common.Demo;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ConfigurableApplicationContext;
import reactor.core.publisher.Flux;

/**
 * Lesson02：ChatClient 详解 —— 流畅 API、同步与流式调用。
 *
 * 演示：通过 Spring 上下文注入 ChatClient.Builder 并构建 ChatClient，
 * 用 {@code prompt().user().system().call().content()} 做同步调用，
 * 用 {@code .stream().content()} 做流式调用（返回 Flux<String>）。
 */
public class ChatClientDemo {

    public static void main(String[] args) {
        Demo.banner("ChatClientDemo · 同步 / 流式调用");

        // 编译安全铁律：真实模型调用前必须校验密钥
        if (!Demo.ensureKey("OPENAI_API_KEY")) {
            return;
        }

        Demo.note("启动 Spring 上下文，注入 ChatClient.Builder 并构建 ChatClient（默认系统提示已固化）...");
        try (ConfigurableApplicationContext ctx = Ch03Support.bootstrap()) {
            ChatClient chatClient = Ch03Support.openAiClient(ctx);

            // ① 同步调用：call().content() 返回完整 String（阻塞直到模型返回整段）
            Demo.note("① 同步调用：问一句话，拿完整回答");
            String answer = chatClient.prompt()
                    .system("你是一名 Java 讲师，讲解要通俗易懂。")
                    .user("用一句话解释什么是 Spring Bean？")
                    .call()
                    .content();
            System.out.println("【同步回答】 " + answer);

            // ② 流式调用：stream().content() 返回 Flux<String>，模型每吐一个 token 就收到一段
            Demo.note("② 流式调用：逐字输出（Flux<String>），体验打字机效果");
            Flux<String> flux = chatClient.prompt()
                    .user("写一句欢迎学习《Java 版 Agent 架构师训练营》的话，20 字以内。")
                    .stream()
                    .content();
            System.out.print("【流式输出】 ");
            flux.toStream().forEach(System.out::print);
            System.out.println();
        }

        Demo.note("示例结束。经验：内部批处理/测试用 call()；面向用户、结果长的接口优先用 stream()。");
    }
}

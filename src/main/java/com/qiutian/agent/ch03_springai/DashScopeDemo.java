package com.qiutian.agent.ch03_springai;

import com.qiutian.agent.common.Demo;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Lesson06：Spring AI Alibaba —— 用 DashScopeChatModel（通义千问）跑通 ChatClient。
 *
 * 关键：Spring AI Alibaba 是「在 Spring AI 标准之上扩展」，ChatClient API 完全一致，
 * 只是底层 ChatModel 换成了 DashScopeChatModel。Lesson02~05 的所有能力原样可用。
 */
public class DashScopeDemo {

    public static void main(String[] args) {
        Demo.banner("DashScopeDemo · 通义千问（Spring AI Alibaba）");

        if (!Demo.ensureKey("DASHSCOPE_API_KEY")) {
            return;
        }

        Demo.note("启动 Spring 上下文，注入 DashScopeChatModel 并构建 ChatClient ...");
        try (ConfigurableApplicationContext ctx = Ch03Support.bootstrap()) {
            ChatClient chatClient = Ch03Support.dashScopeClient(ctx);

            Demo.note("用通义千问做同步问答（ChatClient API 与 OpenAI 示例完全一致）");
            String answer = chatClient.prompt()
                    .system("你是一名严谨的 Java 架构师。")
                    .user("用一句话说明 Spring AI Alibaba 和 Spring AI 的关系。")
                    .call()
                    .content();

            System.out.println("【通义回答】 " + answer);
        }

        Demo.note("示例结束。底层 ChatModel 换成 DashScopeChatModel 后，提示词/结构化/工具能力原样可用。");
    }
}

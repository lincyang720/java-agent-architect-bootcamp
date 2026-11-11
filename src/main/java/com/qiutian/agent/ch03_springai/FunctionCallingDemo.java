package com.qiutian.agent.ch03_springai;

import com.qiutian.agent.common.Demo;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Lesson05：函数调用 / 工具集成 —— 用 @Tool 声明工具，模型自动调用。
 *
 * 关键事实：模型只「请求」调用，真正执行发生在你的 JVM。下面的工具返回确定值
 * （当前时间 / 加减乘计算），不需要任何真实网络，演示「模型决策 + 本地执行」的闭环。
 */
public class FunctionCallingDemo {

    /** 确定性的本地工具集：模型按对话自行决定何时调用。 */
    public static class LocalTools {

        @Tool(description = "获取当前的日期和时间，返回 yyyy-MM-dd HH:mm:ss 格式")
        public String getCurrentTime() {
            return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }

        @Tool(description = "计算两个整数之和，返回它们的和")
        public int add(
                @ToolParam(description = "第一个整数") int a,
                @ToolParam(description = "第二个整数") int b) {
            return a + b;
        }

        @Tool(description = "计算两个整数之积，返回它们的乘积")
        public int multiply(
                @ToolParam(description = "第一个整数") int a,
                @ToolParam(description = "第二个整数") int b) {
            return a * b;
        }
    }

    public static void main(String[] args) {
        Demo.banner("FunctionCallingDemo · @Tool 工具调用");

        if (!Demo.ensureKey("OPENAI_API_KEY")) {
            return;
        }

        try (ConfigurableApplicationContext ctx = Ch03Support.bootstrap()) {
            ChatClient chatClient = Ch03Support.openAiClient(ctx);

            Demo.note("注册 @Tool 工具（当前时间 / 加法 / 乘法），模型按对话自行决定是否调用");
            String answer = chatClient.prompt()
                    .system("你是一个会调用工具的计算助手，需要数据时优先调用工具而非自己编造。")
                    .user("现在几点了？另外请帮我算一下 123 乘以 456 等于多少？")
                    .tools(new LocalTools())
                    .call()
                    .content();

            System.out.println("【助手回答】 " + answer);
        }

        Demo.note("示例结束。模型只『请求』调用，真正执行在你 JVM，权限与审计完全可控。");
    }
}

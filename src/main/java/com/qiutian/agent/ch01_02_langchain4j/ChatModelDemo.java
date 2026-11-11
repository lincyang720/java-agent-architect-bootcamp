package com.qiutian.agent.ch01_02_langchain4j;

import com.qiutian.agent.common.Demo;
import com.qiutian.agent.common.Env;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;

/**
 * 第 1-2 章 · LangChain4j 基础：最朴素的 ChatModel 调用。
 *
 * 演示要点：
 * 1. 如何用 {@link OpenAiChatModel} 直接发起一次对话（不经过 AiServices 封装）。
 * 2. {@code model.chat(...)} 返回 {@link String}；
 *    若想拿到更丰富的信息（token 用量、停止原因等），用 {@code model.generate(...)} 返回 {@link Response}&lt;{@link AiMessage}&gt;。
 * 3. 所有真实模型调用前必须用 {@link Demo#ensureKey(String)} 守卫，保证无密钥环境可编译、可运行不抛异常。
 *
 * 运行前置：export OPENAI_API_KEY=sk-xxxx
 */
public class ChatModelDemo {

    public static void main(String[] args) {
        Demo.banner("ChatModelDemo · 用 OpenAiChatModel 做基础对话");

        // 编译安全铁律：无密钥时直接 return，避免运行时抛异常。
        if (!Demo.ensureKey("OPENAI_API_KEY")) {
            return;
        }

        Demo.note("构建 OpenAiChatModel（modelName 默认 gpt-4o-mini，可按需替换）。");
        OpenAiChatModel model = OpenAiChatModel.builder()
                .apiKey(Env.get("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini")
                .temperature(0.7)
                .build();

        Demo.note("方式一：最简洁的字符串对话。");
        String answer = model.chat("用一句话解释什么是大语言模型（LLM）？");
        System.out.println("模型回答：" + answer);

        Demo.note("方式二：用 chat(ChatMessage) 拿到结构化响应 ChatResponse（含 token 用量）。");
        ChatResponse response = model.chat(UserMessage.from("用一句话解释什么是 Agent？"));
        System.out.println("模型回答：" + response.aiMessage().text());
        System.out.println("本次输入 token 数：" + response.tokenUsage().inputTokenCount());
        System.out.println("本次输出 token 数：" + response.tokenUsage().outputTokenCount());

        Demo.note("两种方式都可工作；后续章节我们会用 AiServices 把模型封装成带工具的 Agent。");
    }
}

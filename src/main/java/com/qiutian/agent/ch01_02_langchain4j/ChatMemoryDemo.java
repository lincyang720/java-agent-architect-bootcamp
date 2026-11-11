package com.qiutian.agent.ch01_02_langchain4j;

import com.qiutian.agent.common.Demo;
import com.qiutian.agent.common.Env;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;

/**
 * 第 1-2 章 · LangChain4j 基础：多轮对话记忆（ChatMemory）。
 *
 * 朴素的 ChatModel 是无状态的：每发一次请求都是全新的上下文。
 * 用 {@link MessageWindowChatMemory} 给 Agent 接上「记忆」，
 * 它维护一个滑动窗口，只保留最近 N 条消息，既能多轮连贯，又避免上下文无限膨胀。
 *
 * 运行前置：export OPENAI_API_KEY=sk-xxxx
 */
public class ChatMemoryDemo {

    interface Assistant {
        String chat(String userMessage);
    }

    public static void main(String[] args) {
        Demo.banner("ChatMemoryDemo · 多轮对话记忆");

        if (!Demo.ensureKey("OPENAI_API_KEY")) {
            return;
        }

        Demo.note("构建底模型。");
        OpenAiChatModel model = OpenAiChatModel.builder()
                .apiKey(Env.get("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini")
                .temperature(0.7)
                .build();

        Demo.note("创建带窗口的记忆（最多保留 10 条消息），并注入到 AiServices。");
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(10);
        Assistant assistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                .chatMemory(memory)
                .build();

        Demo.note("第一轮：先告诉它一个事实。");
        String r1 = assistant.chat("我叫秋田，是一名后端工程师。");
        System.out.println("Assistant：" + r1);

        Demo.note("第二轮：换个话题，看看它是否还记得我是谁（关键看记忆是否生效）。");
        String r2 = assistant.chat("那我刚才说我的职业是什么？");
        System.out.println("Assistant：" + r2);

        Demo.note("小结：把 memory 绑定到 Assistant 后，多轮对话自动连贯；窗口上限防止 token 爆掉。");
    }
}

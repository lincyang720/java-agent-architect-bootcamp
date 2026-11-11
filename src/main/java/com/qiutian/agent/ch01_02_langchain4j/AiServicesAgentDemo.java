package com.qiutian.agent.ch01_02_langchain4j;

import com.qiutian.agent.common.Demo;
import com.qiutian.agent.common.Env;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;

/**
 * 第 1-2 章 · LangChain4j 基础：用 AiServices 把 ChatModel 封装成「Agent 接口」。
 *
 * 核心思想：定义一个普通的 Java 接口，描述 Agent 能做什么（方法签名即能力契约），
 * 然后 {@link AiServices#create(Class, dev.langchain4j.model.chat.ChatModel)} 在运行时
 * 自动生成一个实现类，把方法调用翻译成对大模型的 prompt 调用。
 *
 * 这一步是后续所有 Agent（工具调用 / 记忆 / RAG / 结构化输出）的地基。
 *
 * 运行前置：export OPENAI_API_KEY=sk-xxxx
 */
public class AiServicesAgentDemo {

    /** 这就是「Agent 的能力契约」：一个方法 = 一种对话能力。 */
    interface Assistant {
        /** 简单的问答：传入用户问题，返回模型回答。 */
        String answer(String userMessage);
    }

    public static void main(String[] args) {
        Demo.banner("AiServicesAgentDemo · 接口即 Agent");

        if (!Demo.ensureKey("OPENAI_API_KEY")) {
            return;
        }

        Demo.note("构建底模型 ChatModel（真正的对话引擎）。");
        OpenAiChatModel model = OpenAiChatModel.builder()
                .apiKey(Env.get("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini")
                .temperature(0.7)
                .build();

        Demo.note("用 AiServices 把接口绑定到模型，得到可调用实例（无需手写实现类）。");
        Assistant assistant = AiServices.create(Assistant.class, model);

        Demo.note("像调用普通 Java 方法一样问问题，底层实际是一次模型调用。");
        String reply = assistant.answer("Java 和 Python 在 AI 工程化上各有什么优势？用三点概括。");
        System.out.println("Assistant：" + reply);

        Demo.note("AiServices 的强大之处在于：同一个接口可以无缝叠加工具、记忆、RAG（见后续 Demo）。");
    }
}

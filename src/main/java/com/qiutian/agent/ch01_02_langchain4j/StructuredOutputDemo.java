package com.qiutian.agent.ch01_02_langchain4j;

import com.qiutian.agent.common.Demo;
import com.qiutian.agent.common.Env;
import dev.langchain4j.model.output.structured.Description;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;

/**
 * 第 1-2 章 · LangChain4j 基础：结构化输出（Structured Output）。
 *
 * 很多时候我们不想要一段自然语言，而要一个可被程序直接消费的对象（JSON / POJO）。
 * LangChain4j 通过 {@link AiServices} + 返回 POJO + 字段 {@link Description} 注解，
 * 让模型按 schema 输出并自动反序列化成 Java 对象，省去自己解析文本的脆弱逻辑。
 *
 * 运行前置：export OPENAI_API_KEY=sk-xxxx
 */
public class StructuredOutputDemo {

    /** 希望模型填好的目标结构；@Description 帮助模型理解每个字段的语义。 */
    static class Person {
        @Description("人物的姓名")
        public String name;

        @Description("人物的年龄（整数）")
        public int age;

        @Description("人物从事的职业")
        public String occupation;

        @Override
        public String toString() {
            return "Person{name='" + name + "', age=" + age + ", occupation='" + occupation + "'}";
        }
    }

    /** 返回 POJO 的 Agent 契约：模型会被约束输出可解析为 Person 的结构。 */
    interface Assistant {
        Person extract(String text);
    }

    public static void main(String[] args) {
        Demo.banner("StructuredOutputDemo · 让模型直接返回 POJO");

        if (!Demo.ensureKey("OPENAI_API_KEY")) {
            return;
        }

        Demo.note("构建底模型（结构化输出建议低温度，结果更规整）。");
        OpenAiChatModel model = OpenAiChatModel.builder()
                .apiKey(Env.get("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini")
                .temperature(0.0)
                .build();

        Demo.note("接口方法返回 Person 类型，AiServices 会自动加 JSON schema 约束并反序列化。");
        Assistant assistant = AiServices.create(Assistant.class, model);

        Demo.note("给一段非结构化文本，让模型抽取成结构化对象。");
        String text = "张伟今年 34 岁，在一家互联网公司担任算法工程师，主要负责推荐系统。";
        Person person = assistant.extract(text);
        System.out.println("抽取结果：" + person);

        Demo.note("小结：结构化输出让 LLM 成为「数据抽取器」，下游可直接用对象，无需正则/手写解析。");
    }
}

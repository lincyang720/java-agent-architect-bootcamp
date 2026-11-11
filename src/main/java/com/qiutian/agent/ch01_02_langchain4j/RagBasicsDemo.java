package com.qiutian.agent.ch01_02_langchain4j;

import com.qiutian.agent.common.Demo;
import com.qiutian.agent.common.Env;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import java.util.List;

/**
 * 第 1-2 章 · LangChain4j 基础：最小可跑的 RAG（检索增强生成）。
 *
 * RAG 流程：
 *   1. 把知识切成片段（TextSegment），用 Embedding 模型向量化，存进向量库（这里用内存版）。
 *   2. 用户提问时，先把问题向量化，从库里检索最相关的片段（ContentRetriever）。
 *   3. AiServices 会自动把检索到的片段作为上下文塞进 prompt，让模型「基于资料」作答。
 *
 * 注意：Embedding 模型与 Chat 模型都需要 OPENAI_API_KEY；缺密钥时按契约 return。
 *
 * 运行前置：export OPENAI_API_KEY=sk-xxxx
 */
public class RagBasicsDemo {

    interface Assistant {
        String answer(String question);
    }

    public static void main(String[] args) {
        Demo.banner("RagBasicsDemo · InMemoryEmbeddingStore 最小 RAG");

        // Embedding 模型同样需要密钥；缺 key 时给出提示并 return（编译安全铁律）。
        if (!Demo.ensureKey("OPENAI_API_KEY")) {
            Demo.note("缺少 OPENAI_API_KEY，无法生成 embedding，RAG 演示结束（代码已编译通过）。");
            return;
        }

        Demo.note("第 1 步：准备一份「私有知识库」（这里用课程简介片段演示）。");
        List<TextSegment> segments = List.of(
                TextSegment.from("《Java 版 Agent 架构师训练营》共 18 章，覆盖 LangChain4j、Spring AI、多 Agent 编排、记忆、RAG、MCP、安全与部署。"),
                TextSegment.from("LangChain4j 是 Java 生态最流行的 LLM 应用框架，提供 AiServices、Tool、Memory、RAG 等开箱即用能力。"),
                TextSegment.from("Agent 的核心闭环是：感知（用户输入）→ 推理（LLM）→ 行动（调用工具）→ 观察（结果）→ 再推理，直到任务完成。"),
                TextSegment.from("RAG 能缓解大模型幻觉，通过把外部权威文档检索出来作为上下文，让回答有据可依。")
        );

        Demo.note("第 2 步：构建 Embedding 模型，把知识片段向量化并写入内存向量库。");
        OpenAiEmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
                .apiKey(Env.get("OPENAI_API_KEY"))
                .modelName("text-embedding-3-small")
                .build();

        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        // 1.0.1 中 store 没有「embeddings + segments」二参 addAll，逐条 add（同时保存向量与原文）。
        for (int i = 0; i < segments.size(); i++) {
            store.add(embeddings.get(i), segments.get(i));
        }
        Demo.note("已向量化并入库 " + segments.size() + " 个片段。");

        Demo.note("第 3 步：构建检索器（Retriever），指定最多返回 3 条最相关片段。");
        EmbeddingStoreContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(store)
                .embeddingModel(embeddingModel)
                .maxResults(3)
                .minScore(0.0)
                .build();

        Demo.note("第 4 步：把 Chat 模型与检索器一起绑定到 AiServices。");
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .apiKey(Env.get("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini")
                .temperature(0.0)
                .build();
        Assistant assistant = AiServices.builder(Assistant.class)
                .chatModel(chatModel)
                .contentRetriever(retriever)
                .build();

        Demo.note("提问：只凭模型记忆可能答不准，但 RAG 让它基于我们刚入库的资料作答。");
        String answer = assistant.answer("这门训练营主要覆盖哪些技术主题？请基于资料回答。");
        System.out.println("回答：" + answer);

        Demo.note("小结：RAG = 向量化入库 + 语义检索 + 上下文增强；生产可换 pgvector（pom 已含 langchain4j-pgvector）。");
    }
}

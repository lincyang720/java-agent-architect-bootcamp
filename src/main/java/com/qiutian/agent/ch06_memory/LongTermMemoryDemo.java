package com.qiutian.agent.ch06_memory;

import com.qiutian.agent.common.Demo;
import com.qiutian.agent.common.Env;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.CosineSimilarity;
import dev.langchain4j.store.embedding.EmbeddingMatch;

import java.util.ArrayList;
import java.util.List;

/**
 * 第六章第3-4节 · 长期记忆（Long-Term Memory）：存入与语义检索。
 *
 * 长期记忆 = Embedding 向量化 + 向量库存储 + 语义检索。
 * 记忆检索比的是「意」不是「字」：query 里没有『猫』，也能靠向量邻近召回橘猫那条。
 *
 * 关键约束：真实模型调用前必须 {@code if (!Demo.ensureKey("OPENAI_API_KEY")) return;}，
 * 无密钥环境只打印提示、不抛异常。存和取必须用「同一个 Embedding 模型」，
 * 否则向量空间不一致，检索全乱（lesson04 坑点）。
 *
 * 说明：本仓库 pom 当前未含 langchain4j-embeddings-store-in-memory 模块，故这里用
 * 纯 Java 实现了一个最小内存向量库（基于 core 自带的 Embedding / TextSegment /
 * CosineSimilarity / EmbeddingMatch）。若引入该模块，可直接替换为：
 *   EmbeddingStore&lt;TextSegment&gt; store = new InMemoryEmbeddingStore&lt;&gt;();
 * 其余「embed → add → findRelevant」逻辑完全一致。
 *
 * 运行前置：export OPENAI_API_KEY=sk-xxxx
 */
public class LongTermMemoryDemo {

    /** 最小内存向量库：存 Embedding+原文，按余弦相似度返回 Top-K。 */
    static final class SimpleInMemoryStore {
        private final List<Embedding> embeddings = new ArrayList<>();
        private final List<TextSegment> segments = new ArrayList<>();
        private int counter = 0;

        String add(Embedding embedding, TextSegment segment) {
            String id = "mem-" + (++counter);
            embeddings.add(embedding);
            segments.add(segment);
            return id;
        }

        List<EmbeddingMatch<TextSegment>> findRelevant(Embedding query, int maxResults, double minScore) {
            List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
            for (int i = 0; i < embeddings.size(); i++) {
                double score = CosineSimilarity.between(query, embeddings.get(i));
                if (score >= minScore) {
                    matches.add(new EmbeddingMatch<>(score, "mem-" + (i + 1), embeddings.get(i), segments.get(i)));
                }
            }
            matches.sort((a, b) -> Double.compare(b.score(), a.score())); // 按相似度降序
            return matches.subList(0, Math.min(maxResults, matches.size()));
        }
    }

    public static void main(String[] args) {
        Demo.banner("LongTermMemoryDemo · 长期记忆：存入与语义检索");

        Demo.note("长期记忆 = Embedding 模型 + 向量库。需要真实 Embedding 模型，先校验 OPENAI_API_KEY。");
        if (!Demo.ensureKey("OPENAI_API_KEY")) {
            return;
        }

        OpenAiEmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
                .apiKey(Env.get("OPENAI_API_KEY"))
                .modelName("text-embedding-3-small")
                .build();

        // 若引入 langchain4j-embeddings-store-in-memory，可替换为：new InMemoryEmbeddingStore<>()
        SimpleInMemoryStore store = new SimpleInMemoryStore();

        Demo.note("存入若干条记忆（统一使用同一个 Embedding 模型，保证向量空间一致）。");
        String[] memories = {
                "用户秋田是后端工程师，擅长 Java 与分布式系统。",
                "用户正在学习 Agent 架构，重点关注记忆模块。",
                "用户上周退了一件不合适的运动鞋，关注退货流程。",
                "用户喜欢用图示理解算法，偏好中文讲解。",
                "用户养了一只叫『豆豆』的橘猫。"
        };
        for (String m : memories) {
            Embedding embedding = embeddingModel.embed(m).content();
            store.add(embedding, TextSegment.from(m));
        }
        Demo.note("已存入 " + memories.length + " 条记忆（内存向量库）。");

        Demo.note("语义检索：查询『他养的宠物叫什么』，验证能否召回橘猫那条（query 里没有『猫』字）。");
        String query = "他养的宠物叫什么名字";
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        List<EmbeddingMatch<TextSegment>> matches = store.findRelevant(queryEmbedding, 3, 0.0);
        for (EmbeddingMatch<TextSegment> match : matches) {
            System.out.println("  [score=" + String.format("%.4f", match.score()) + "] "
                    + match.embedded().text());
        }

        Demo.note("结论：语义检索靠『意』而非『字』，可跨表述召回相关记忆。生产可把 SimpleInMemoryStore "
                + "换成 PgVector / Milvus / Chroma（builder 写法见 VectorStoreComparison 注释）。");
    }
}

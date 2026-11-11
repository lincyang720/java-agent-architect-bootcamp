package com.qiutian.agent.ch06_memory;

import com.qiutian.agent.common.Demo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 第六章第4节 · 向量相似度检索对比（纯 Java 可离线跑）。
 *
 * 真实向量库（PgVector / Milvus / Chroma）的「检索 API 形态」都一致：
 * 把文本 Embedding 成向量 → 存入 store → 用 query 向量做 findRelevant 拿到 Top-K。
 * 区别只在「存储后端与连接参数」。所以这里用纯 Java 手搓一个最小向量库
 * （手算余弦相似度，lesson04 同款实现），先跑通检索逻辑；再用注释给出切到
 * 各生产向量库的 builder 写法——不真正 import 这些类（契约禁止新增依赖）。
 *
 * 相似度算法说明：
 *  - 余弦相似度：看「方向」是否一致，与向量长度无关，文本检索几乎都用它。
 *  - 欧氏距离：看「直线距离」，受长度影响。
 */
public class VectorStoreComparison {

    /** 一条被向量化后的记忆（真实场景的向量由 Embedding 模型生成）。 */
    static final class VectorDoc {
        final String id;
        final String text;
        final double[] vector;

        VectorDoc(String id, String text, double[] vector) {
            this.id = id;
            this.text = text;
            this.vector = vector;
        }
    }

    /** 余弦相似度：方向一致性，范围 [-1,1]，1 表示同方向（语义最接近）。 */
    static double cosine(double[] a, double[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("维度必须一致");
        }
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    public static void main(String[] args) {
        Demo.banner("VectorStoreComparison · 向量相似度检索（纯 Java 演示）");

        Demo.note("构造一个最小向量库：用 4 维向量示意文本 Embedding（真实场景由 Embedding 模型生成）。");
        List<VectorDoc> docs = new ArrayList<>();
        docs.add(new VectorDoc("m1", "用户是后端工程师，擅长 Java", new double[]{0.8, 0.2, 0.1, 0.05}));
        docs.add(new VectorDoc("m2", "用户关注退货流程", new double[]{0.7, 0.3, 0.15, 0.1}));
        docs.add(new VectorDoc("m3", "用户养了一只橘猫叫豆豆", new double[]{0.1, 0.1, 0.85, 0.2}));
        docs.add(new VectorDoc("m4", "用户喜欢用图示理解算法", new double[]{0.2, 0.8, 0.1, 0.7}));

        Demo.note("语义查询：『他家的宠物』（向量应靠近 m3 的橘猫）。");
        double[] query = new double[]{0.12, 0.12, 0.8, 0.18};

        List<Scored> scored = new ArrayList<>();
        for (VectorDoc d : docs) {
            scored.add(new Scored(d, cosine(query, d.vector)));
        }
        scored.sort(Comparator.comparingDouble((Scored s) -> s.score).reversed());
        for (Scored s : scored) {
            System.out.println("  [score=" + String.format("%.4f", s.score) + "] " + s.doc.text);
        }

        Demo.note("结论：相似度检索 = Embedding + 余弦/欧氏距离排序。下面给出切到生产向量库的写法（注释，不引入依赖）。");
        printProductionSwitch();
    }

    /** 检索结果（文档 + 相似度分数）。 */
    static final class Scored {
        final VectorDoc doc;
        final double score;

        Scored(VectorDoc doc, double score) {
            this.doc = doc;
            this.score = score;
        }
    }

    /**
     * 生产切换：下列 builder 来自不同依赖，本 demo 仅作示意，不真正 import。
     * 切换后检索代码与上面完全一致：store.add(embedding, segment) / store.findRelevant(...)。
     */
    static void printProductionSwitch() {
        // ===== PgVector（已在本项目 pom 中声明：langchain4j-pgvector）=====
        // 依赖：PostgreSQL + pgvector 扩展；连接参数走 JDBC。
        //   PgVectorEmbeddingStore store = PgVectorEmbeddingStore.builder()
        //       .host("localhost")
        //       .port(5432)
        //       .database("agent_mem")
        //       .user("postgres")
        //       .password("postgres")
        //       .table("memory_embeddings")
        //       .dimension(1536)            // 必须与 Embedding 模型维度一致
        //       .build();
        //   // 检索：List<EmbeddingMatch<TextSegment>> hits = store.findRelevant(queryEmbedding, 5, 0.7);

        // ===== Milvus（需额外引入：dev.langchain4j:langchain4j-milvus）=====
        // 依赖：Milvus 服务（standalone 或 cluster，默认端口 19530）。
        //   MilvusEmbeddingStore store = MilvusEmbeddingStore.builder()
        //       .host("localhost")
        //       .port(19530)
        //       .collectionName("agent_memory")
        //       .dimension(1536)
        //       .build();

        // ===== Chroma（需额外引入：dev.langchain4j:langchain4j-chroma）=====
        // 依赖：Chroma 服务（默认 HTTP 8000）。
        //   ChromaEmbeddingStore store = ChromaEmbeddingStore.builder()
        //       .baseUrl("http://localhost:8000")
        //       .collectionName("agent_memory")
        //       .build();

        Demo.note("PgVector 依赖已声明在 pom；Milvus / Chroma 需自行补充对应 langchain4j-* 依赖"
                + "（契约禁止新增依赖，故仅注释给出）。");
    }
}

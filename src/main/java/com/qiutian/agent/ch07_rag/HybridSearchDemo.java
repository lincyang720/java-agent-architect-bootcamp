package com.qiutian.agent.ch07_rag;

import com.qiutian.agent.common.Demo;
import com.qiutian.agent.common.Env;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ch07-rag · 混合检索（Hybrid Retrieval）：关键词(BM25) + 向量，RRF 融合
 *
 * 对应 lesson04（检索优化技术：混合检索、重排序、查询扩展）。
 *
 * 为什么混合：纯向量检索对"精确词/型号/错误码"弱；纯关键词(BM25)对"口语/语义"弱。
 * 两者互补，生产标配。融合用 RRF（Reciprocal Rank Fusion）——只看排名、不碰分数绝对值，
 * 避免向量分(0~1)与 BM25 分(量纲大)直接相加被量纲主导。
 *
 * 离线骨架：关键词召回与"离线哈希向量召回"均无需密钥即可运行，演示完整链路；
 * 检测到 OPENAI_API_KEY 时，会用真实 OpenAiEmbeddingModel 重建向量库再做一次检索（key 守卫）。
 */
public class HybridSearchDemo {

    /** 离线向量维度（仅用于无密钥时的骨架演示，真实维度由模型决定）。 */
    private static final int DIM = 256;

    /** 知识库文档（带 cite_id 与 source，用于溯源与结果展示）。 */
    record Doc(String id, String source, String text) {}

    public static void main(String[] args) {
        Demo.banner("HybridSearchDemo · BM25 关键词 + 向量检索 + RRF 融合");

        List<Doc> corpus = List.of(
                new Doc("c1", "退换货政策2024.pdf", "退款申请提交后，系统将在二十四小时内完成审核。"),
                new Doc("c2", "退换货政策2024.pdf", "审核通过后，款项将原路退回至付款账户，一般一至三个工作日到账。"),
                new Doc("c3", "退换货政策2024.pdf", "客户签收商品七日内可申请无理由退货，需保持吊牌完好不影响二次销售。"),
                new Doc("c4", "换货流程.docx", "换货需先将瑕疵商品寄回仓库，仓库验收无误后发出新品。"),
                new Doc("c5", "促销规则.docx", "夏季新品满三百减五十，活动期间下单立享优惠。"),
                new Doc("c6", "售后FAQ.txt", "退款一般多久到账？通常审核通过后一至三个工作日原路退回。")
        );

        String query = "退款一般多久能到账";
        Demo.note("用户查询: " + query);

        // ---------- 第一路：关键词召回（BM25，纯 Java，离线可跑） ----------
        List<String> bm25Rank = bm25Rank(query, corpus);
        Demo.note("关键词(BM25)召回排名: " + bm25Rank);
        bm25Rank.forEach(id -> System.out.println("    BM25 ▸ " + id + " | " + textOf(corpus, id)));

        // ---------- 第二路：向量召回（InMemoryEmbeddingStore，离线骨架可跑） ----------
        // 无密钥时用离线哈希向量（仅演示结构）；有密钥时换成真实 OpenAI embedding。
        InMemoryEmbeddingStore<TextSegment> store = buildStore(corpus, null);
        List<String> vectorRank = vectorRank(store, query, null);
        Demo.note("向量召回排名(离线哈希向量): " + vectorRank);
        vectorRank.forEach(id -> System.out.println("    向量 ▸ " + id + " | " + textOf(corpus, id)));

        // ---------- RRF 融合两路结果 ----------
        List<String> fused = rrfFuse(List.of(vectorRank, bm25Rank), 60);
        Demo.note("RRF 融合后 Top 结果: " + fused);
        fused.forEach(id -> System.out.println("    融合 ▸ " + id + " | " + textOf(corpus, id)));

        // ---------- 可选：真实 OpenAI embedding（key 守卫） ----------
        if (Env.get("OPENAI_API_KEY") != null) {
            Demo.note("检测到 OPENAI_API_KEY，用真实 OpenAiEmbeddingModel 重建向量库再做一次向量召回。");
            OpenAiEmbeddingModel realModel = OpenAiEmbeddingModel.builder()
                    .apiKey(Env.get("OPENAI_API_KEY"))
                    .modelName("text-embedding-3-small")
                    .build();
            InMemoryEmbeddingStore<TextSegment> realStore = buildStore(corpus, realModel);
            List<String> realVectorRank = vectorRank(realStore, query, realModel);
            Demo.note("向量召回排名(真实 OpenAI embedding): " + realVectorRank);
            List<String> fusedReal = rrfFuse(List.of(realVectorRank, bm25Rank), 60);
            Demo.note("融合(真实向量) Top 结果: " + fusedReal);
        } else {
            Demo.note("未设置 OPENAI_API_KEY，跳过真实向量召回（骨架已用离线向量演示完整融合逻辑）。");
        }

        Demo.note("小结：混合检索 = 向量(语义) + 关键词(字面) 双路互补，RRF 只看排名稳健融合；"
                + "生产可在融合后再接 bge-reranker 精排（见 ReRankingDemo / lesson04 第三节）。");
    }

    // ===================== 关键词召回：BM25 =====================

    /** 标准 BM25 打分，按分数降序返回 docId 列表。 */
    public static List<String> bm25Rank(String query, List<Doc> corpus) {
        List<List<String>> docToks = new ArrayList<>();
        for (Doc d : corpus) {
            docToks.add(tokenize(d.text));
        }
        List<String> qToks = tokenize(query);

        // 文档频率 df
        Map<String, Integer> df = new HashMap<>();
        for (List<String> toks : docToks) {
            for (String t : new java.util.HashSet<>(toks)) {
                df.merge(t, 1, Integer::sum);
            }
        }
        int n = corpus.size();
        double avgdl = docToks.stream().mapToInt(List::size).average().orElse(1.0);
        double k1 = 1.5, b = 0.75;

        Map<String, Double> scores = new LinkedHashMap<>();
        for (int i = 0; i < corpus.size(); i++) {
            Map<String, Integer> tf = new HashMap<>();
            for (String t : docToks.get(i)) {
                tf.merge(t, 1, Integer::sum);
            }
            double score = 0.0;
            int dl = docToks.get(i).size();
            for (String qt : qToks) {
                Integer f = df.get(qt);
                if (f == null) {
                    continue;
                }
                int termFreq = tf.getOrDefault(qt, 0);
                if (termFreq == 0) {
                    continue;
                }
                double idf = Math.log((n - f + 0.5) / (f + 0.5) + 1.0);
                double denom = termFreq + k1 * (1 - b + b * dl / avgdl);
                score += idf * (termFreq * (k1 + 1)) / denom;
            }
            scores.put(corpus.get(i).id, score);
        }
        return scores.entrySet().stream()
                .filter(e -> e.getValue() > 0.0)
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
    }

    // ===================== 向量召回：InMemoryEmbeddingStore =====================

    /** 构建内存向量库。model 为 null 时用离线哈希向量（骨架演示）。 */
    public static InMemoryEmbeddingStore<TextSegment> buildStore(List<Doc> corpus, OpenAiEmbeddingModel model) {
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        List<TextSegment> segments = new ArrayList<>();
        for (Doc d : corpus) {
            Metadata md = Metadata.from("cite_id", d.id).put("source", d.source);
            segments.add(TextSegment.from(d.text, md));
        }
        if (model == null) {
            // 离线骨架：用确定性哈希向量填充，保证无密钥也能演示检索结构
            for (int i = 0; i < corpus.size(); i++) {
                store.add(Embedding.from(embedOffline(corpus.get(i).text)), segments.get(i));
            }
        } else {
            // 真实路径：用 OpenAI embedding 向量化并入库
            List<Embedding> embeddings = model.embedAll(segments).content();
            for (int i = 0; i < segments.size(); i++) {
                store.add(embeddings.get(i), segments.get(i));
            }
        }
        return store;
    }

    /** 在向量库中检索 query，返回 docId 排名（按相似度降序）。 */
    public static List<String> vectorRank(InMemoryEmbeddingStore<TextSegment> store, String query, OpenAiEmbeddingModel model) {
        Embedding q = (model == null)
                ? Embedding.from(embedOffline(query))
                : model.embed(query).content();
        EmbeddingSearchRequest req = EmbeddingSearchRequest.builder()
                .queryEmbedding(q)
                .maxResults(6)
                .minScore(0.0)
                .build();
        List<EmbeddingMatch<TextSegment>> matches = store.search(req).matches();
        List<String> rank = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> m : matches) {
            String id = m.embedded().metadata().getString("cite_id");
            rank.add(id);
        }
        return rank;
    }

    // ===================== RRF 融合 =====================

    /** Reciprocal Rank Fusion：只看排名，score = Σ 1/(k + rank)。k 通常取 60。 */
    public static List<String> rrfFuse(List<List<String>> rankLists, int k) {
        Map<String, Double> scoreMap = new HashMap<>();
        for (List<String> ranks : rankLists) {
            for (int rank = 0; rank < ranks.size(); rank++) {
                String id = ranks.get(rank);
                scoreMap.merge(id, 1.0 / (k + rank + 1), Double::sum);
            }
        }
        return scoreMap.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
    }

    // ===================== 离线工具：分词 + 哈希向量 =====================

    /** 中文按"字 bigram + 英文单词"分词，简单但足以演示检索与相似度。 */
    public static List<String> tokenize(String text) {
        List<String> toks = new ArrayList<>();
        for (String w : text.toLowerCase().split("[^a-z0-9]+")) {
            if (!w.isEmpty()) {
                toks.add(w);
            }
        }
        StringBuilder cjk = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk.append(c);
            } else {
                flushBigrams(cjk, toks);
                cjk.setLength(0);
            }
        }
        flushBigrams(cjk, toks);
        return toks;
    }

    private static void flushBigrams(StringBuilder sb, List<String> out) {
        String s = sb.toString();
        if (s.length() == 1) {
            out.add(s);
            return;
        }
        for (int i = 0; i < s.length() - 1; i++) {
            out.add(s.substring(i, i + 2));
        }
    }

    /** 离线确定性向量：token 哈希到固定维度并 L2 归一化（仅骨架演示，不可用于生产）。 */
    public static float[] embedOffline(String text) {
        float[] v = new float[DIM];
        for (String t : tokenize(text)) {
            int idx = Math.floorMod(t.hashCode(), DIM);
            v[idx] += 1.0f;
        }
        double norm = 0.0;
        for (float x : v) {
            norm += x * x;
        }
        norm = Math.sqrt(norm);
        if (norm > 0.0) {
            for (int i = 0; i < v.length; i++) {
                v[i] = (float) (v[i] / norm);
            }
        }
        return v;
    }

    private static String textOf(List<Doc> corpus, String id) {
        for (Doc d : corpus) {
            if (d.id.equals(id)) {
                return d.text;
            }
        }
        return "";
    }
}

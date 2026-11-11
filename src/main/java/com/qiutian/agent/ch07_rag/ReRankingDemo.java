package com.qiutian.agent.ch07_rag;

import com.qiutian.agent.common.Demo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ch07-rag · 重排序（Re-ranking）：启发式重排器
 *
 * 对应 lesson04（检索优化技术：重排序）。
 *
 * 为什么要重排：向量召回是"近似最近邻(ANN)"，为快牺牲精度，且只看 query 与单 chunk 的两两相似，
 * 看不到 chunk 之间关系。重排器（生产用 Cross-Encoder / bge-reranker）把"query+chunk"拼一起精排，
 * 精度远高于双塔。经验公式：粗排 K = 精排 K 的 5~10 倍（如 粗排20 → 精排3）。
 *
 * 本 Demo 用纯 Java 实现一个【启发式重排器】做离线骨架：综合
 *   1) 关键词重叠度（query 词在 chunk 中的命中/密度）
 *   2) 位置加权（命中点越靠前、chunk 越靠前，权重越高）
 *   3) 长度惩罚（过长 chunk 噪声大，适度降权）
 * 真实生产把 score 换成 bge-reranker 的相关性分数即可（见文件末尾注释）。
 */
public class ReRankingDemo {

    /** 候选片段：id、原文、粗排阶段给的向量相似度（模拟 ANN 召回分数）。 */
    record Candidate(String id, String text, double coarseScore) {}

    /** 重排后的结果。 */
    record Ranked(String id, String text, double finalScore) {}

    public static void main(String[] args) {
        Demo.banner("ReRankingDemo · 启发式重排器（关键词重叠 + 位置加权 + 长度惩罚）");

        String query = "退款一般多久能到账";
        Demo.note("用户查询: " + query);

        // 模拟"粗召回 Top-8"（来自混合检索，这里直接给候选文本 + 各自的向量粗排分）
        List<Candidate> candidates = List.of(
                new Candidate("d1", "退款申请提交后，系统将在二十四小时内完成审核。", 0.62),
                new Candidate("d2", "审核通过后，款项将原路退回至付款账户，一般一至三个工作日到账。", 0.71),
                new Candidate("d3", "客户签收商品七日内可申请无理由退货，需保持吊牌完好。", 0.55),
                new Candidate("d4", "换货需先将瑕疵商品寄回仓库，仓库验收无误后发出新品。", 0.48),
                new Candidate("d5", "夏季新品满三百减五十，活动期间下单立享优惠。", 0.20),
                new Candidate("d6", "退款一般多久到账？通常审核通过后一至三个工作日原路退回。", 0.78),
                new Candidate("d7", "本店支持七天无理由退换货，详情见退换货政策。", 0.40),
                new Candidate("d8", "会员积分可在结算时抵扣现金，每百分抵一元。", 0.12)
        );

        Demo.note("粗召回顺序(按向量分):");
        candidates.forEach(c -> System.out.println("    " + c.id + " [" + String.format("%.2f", c.coarseScore) + "] " + c.text));

        Demo.note("调用启发式重排器，综合'关键词重叠 + 位置加权 + 长度惩罚'重新打分。");
        List<Ranked> reranked = heuristicRerank(query, candidates);
        System.out.println("  重排后顺序:");
        reranked.forEach(r -> System.out.println("    ▸ " + r.id + " [总分 " + String.format("%.3f", r.finalScore)
                + "] " + r.text));

        Demo.note("对比可见：与 query 强相关的 d2/d6 被提到最前，无关促销 d5、积分 d8 沉底——正是重排『收网提质』的作用。");
        Demo.note("生产对接：把 finalScore 换成 bge-reranker-large 的 Cross-Encoder 分数，再取 Top-3 送 prompt（见下方注释）。");
    }

    /**
     * 启发式重排：final = w1*归一化粗排分 + w2*关键词重叠 + w3*位置加权 − w4*长度惩罚。
     */
    public static List<Ranked> heuristicRerank(String query, List<Candidate> candidates) {
        List<String> qToks = HybridSearchDemo.tokenize(query);
        double maxCoarse = candidates.stream().mapToDouble(Candidate::coarseScore).max().orElse(1.0);

        List<Ranked> result = new ArrayList<>();
        for (Candidate c : candidates) {
            List<String> cToks = HybridSearchDemo.tokenize(c.text);

            // 1) 关键词重叠度：query 词在 chunk 中的命中比例（Jaccard 风格）
            long hit = qToks.stream().filter(cToks::contains).count();
            double overlap = qToks.isEmpty() ? 0.0 : (double) hit / qToks.size();

            // 2) 位置加权：第一个命中词出现的位置越靠前，权重越高（0~1）
            double position = 0.0;
            for (String qt : qToks) {
                int idx = cToks.indexOf(qt);
                if (idx >= 0) {
                    position = Math.max(position, 1.0 - (double) idx / Math.max(1, cToks.size()));
                }
            }

            // 3) 长度惩罚：超过 60 字开始线性降权（抑制噪声大的长 chunk）
            double lengthPenalty = c.text.length() > 60 ? (c.text.length() - 60) * 0.002 : 0.0;

            // 4) 粗排分归一化（保留 ANN 召回信号）
            double coarseNorm = maxCoarse > 0 ? c.coarseScore / maxCoarse : 0.0;

            double finalScore = 0.25 * coarseNorm + 0.45 * overlap + 0.30 * position - lengthPenalty;
            result.add(new Ranked(c.id, c.text, finalScore));
        }

        result.sort(Comparator.comparingDouble(Ranked::finalScore).reversed());
        return result;
    }

    /*
     * 生产重排器（伪代码，key 守卫，需 OPENAI_API_KEY 或本地 bge-reranker）：
     *
     * // 用 FlagEmbedding 的 BAAI/bge-reranker-large（本地 ONNX 推理，无需联网）
     * FlagReranker reranker = new FlagReranker("BAAI/bge-reranker-large", true);
     * List<String> pairs = candidates.stream()
     *         .map(c -> query + " ||| " + c.text()).toList();
     * float[] scores = reranker.computeScore(pairs);
     * // 按 scores 降序取 Top-3 送 prompt
     *
     * 也可用 LangChain4j 的 CohereRerank 或 OpenAiChatModel 让小模型打 0~1 分（见 lesson04 第三节）。
     * 关键原则：先粗排广撒网（Top-20），再重排精筛选（Top-3），不要对全库重排（太慢）。
     */
}

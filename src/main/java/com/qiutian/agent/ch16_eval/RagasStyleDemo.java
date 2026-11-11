package com.qiutian.agent.ch16_eval;

import com.qiutian.agent.common.Demo;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 第十六章第2节 · RAGAS 风格评测（启发式近似，纯 Java 离线可跑）。
 *
 * 真实 RAGAS 用 LLM 当裁判算 Faithfulness / Answer Relevancy / Context Recall。
 * 这里用「关键词重叠 + 简单 NLI 桩」做可离线运行的近似实现，便于理解指标含义；
 * 生产/精确评测请接入 Python RAGAS（见本文件末尾注释，以及 ch16_eval/python/ragas_eval.py）。
 */
public class RagasStyleDemo {

    public static void main(String[] args) {
        Demo.banner("RagasStyleDemo · RAGAS 风格启发式评测（Faithfulness/Relevancy/Recall）");

        // 一组 (question, context, answer, ground_truth) 桩样本
        List<Sample> samples = List.of(
            new Sample("退款多久到账",
                "退款审核通过后24小时内原路退回。",
                "退款24小时内到账。",
                "退款审核通过后24小时内原路退回。"),
            new Sample("吊牌摘了能退货吗",
                "吊牌完好方可退货。",
                "吊牌摘除不支持无理由退货。",
                "吊牌完好方可退货。"),
            // 幻觉反例：答案引入了上下文没有的信息（"换货需寄回门店"无依据）
            new Sample("退款多久到账",
                "退款审核通过后24小时内原路退回。",
                "退款24小时内到账，换货需寄回门店。",
                "退款审核通过后24小时内原路退回。"));

        Demo.note("对每个样本计算三项启发式指标（0~1，越高越好）：");
        double sumF = 0, sumR = 0, sumC = 0;
        for (Sample s : samples) {
            double faith = faithfulness(s.context, s.answer);
            double rel = answerRelevancy(s.question, s.answer);
            double rec = contextRecall(s.context, s.groundTruth);
            sumF += faith;
            sumR += rel;
            sumC += rec;
            System.out.println("  Q: " + s.question);
            System.out.println("    Faithfulness=" + String.format("%.2f", faith)
                    + "  AnswerRelevancy=" + String.format("%.2f", rel)
                    + "  ContextRecall=" + String.format("%.2f", rec));
        }
        int n = samples.size();
        Demo.note("均值：Faithfulness=" + String.format("%.2f", sumF / n)
                + "  AnswerRelevancy=" + String.format("%.2f", sumR / n)
                + "  ContextRecall=" + String.format("%.2f", sumC / n));
        Demo.note("第 3 条是幻觉反例，其 Faithfulness 显著低于前两条——这正是 RAGAS 要抓的『编造』。"
                + "卡点建议：Faithfulness<0.9 / AnswerRelevancy<0.85 / ContextRecall<0.8 阻断合并。");

        // 接入 Python RAGAS 的方法见本文件末尾注释。
    }

    /**
     * Faithfulness 近似：答案中每个论断词是否都能在 context 中找到依据（关键词重叠）。
     * 真实 RAGAS：把答案拆论断，用 LLM 判「能否从 context 推出」，忠实度=可推出/总论断。
     */
    static double faithfulness(String context, String answer) {
        Set<String> ctxWords = tokenize(context);
        Set<String> ansWords = tokenize(answer);
        if (ansWords.isEmpty()) return 0.0;
        long supported = ansWords.stream().filter(ctxWords::contains).count();
        return supported / (double) ansWords.size();
    }

    /**
     * Answer Relevancy 近似：问题与答案的词重叠度。
     * 真实 RAGAS：用 LLM 基于 answer 反向生成若干问题，算其与原问题的语义相似度。
     */
    static double answerRelevancy(String question, String answer) {
        Set<String> q = tokenize(question), a = tokenize(answer);
        if (q.isEmpty() || a.isEmpty()) return 0.0;
        Set<String> inter = new HashSet<>(q);
        inter.retainAll(a);
        return inter.size() / (double) Math.min(q.size(), a.size());
    }

    /**
     * Context Recall 近似：ground_truth 的关键事实词有多少出现在检索到的 context 中。
     * 真实 RAGAS：把 ground_truth 拆事实点，检查是否都在 retrieved context 中。
     */
    static double contextRecall(String context, String groundTruth) {
        Set<String> ctx = tokenize(context), gt = tokenize(groundTruth);
        if (gt.isEmpty()) return 0.0;
        long hit = gt.stream().filter(ctx::contains).count();
        return hit / (double) gt.size();
    }

    /** 中文逐字 + 英文按空白切分（演示用 tokenizer）。 */
    static Set<String> tokenize(String text) {
        Set<String> set = new HashSet<>();
        for (String w : text.toLowerCase().split("[\\s\\p{Punct}]+")) {
            if (!w.isBlank()) set.add(w);
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4e00 && c <= 0x9fa5) set.add(String.valueOf(c));
        }
        return set;
    }

    record Sample(String question, String context, String answer, String groundTruth) {}

    /*
     * 接入 Python RAGAS（精确评测）参考：
     *
     *   from datasets import Dataset
     *   from ragas import evaluate
     *   from ragas.metrics import faithfulness, answer_relevancy, context_recall
     *   from langchain_openai import ChatOpenAI, OpenAIEmbeddings
     *
     *   judge_llm = ChatOpenAI(model="gpt-4o-mini", temperature=0)
     *   judge_emb = OpenAIEmbeddings(model="text-embedding-3-small")
     *   data = Dataset.from_list([
     *       {"question": ..., "answer": ..., "contexts": [...], "ground_truth": ...},
     *       ...
     *   ])
     *   result = evaluate(data, metrics=[faithfulness, answer_relevancy, context_recall],
     *                     llm=judge_llm, embeddings=judge_emb)
     *   print(result)
     *
     * 也可直接运行本仓库参考实现：  python ch16_eval/python/ragas_eval.py --help
     */
}

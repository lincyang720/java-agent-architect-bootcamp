package com.qiutian.agent.ch16_eval;

import com.qiutian.agent.common.Demo;
import com.qiutian.agent.common.Env;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 第十六章第3节 · LLM-as-Judge：用（可替换的）裁判评估答案质量。
 *
 * 设计要点：
 *   - Judge 是一个接口，可替换实现；默认用「规则裁判」离线跑（无需密钥）；
 *   - 若设置了 OPENAI_API_KEY，则切换到真实 LLM 裁判（Demo.ensureKey 守卫）；
 *   - 演示两个关键工程问题：① 校准（用人工标注集算一致率）；② 位置偏差（交换顺序跑两次）。
 */
public class LlmAsJudgeDemo {

    /** 裁判接口：给 (问题, 上下文, 答案) 打 0~1 的忠实度分。 */
    interface Judge {
        String name();

        double faithfulness(String question, String context, String answer);
    }

    /** 规则裁判（离线默认）：基于答案词是否被上下文支撑的启发式。 */
    static final class RuleJudge implements Judge {
        public String name() {
            return "RuleJudge(离线)";
        }

        public double faithfulness(String q, String context, String answer) {
            Set<String> ctx = tokenize(context), ans = tokenize(answer);
            if (ans.isEmpty()) return 0.0;
            long sup = ans.stream().filter(ctx::contains).count();
            return sup / (double) ans.size();
        }
    }

    /** LLM 裁判（需密钥）：用 OpenAI 模型判断是否忠实，解析 JSON 返回分值。 */
    static final class LlmJudge implements Judge {
        private final OpenAiChatModel model;

        LlmJudge(OpenAiChatModel model) {
            this.model = model;
        }

        public String name() {
            return "LlmJudge(gpt-4o-mini)";
        }

        public double faithfulness(String q, String context, String answer) {
            String resp = model.chat(judgePrompt(q, context, answer));
            return parseScore(resp);
        }
    }

    public static void main(String[] args) {
        Demo.banner("LlmAsJudgeDemo · LLM-as-Judge（可替换裁判 + 校准/位置偏差）");

        Judge judge;
        if (Demo.ensureKey("OPENAI_API_KEY")) {
            OpenAiChatModel model = OpenAiChatModel.builder()
                    .apiKey(Env.get("OPENAI_API_KEY"))
                    .modelName("gpt-4o-mini").temperature(0.0).build();
            judge = new LlmJudge(model);
            Demo.note("检测到 OPENAI_API_KEY，已切换到真实 LLM 裁判。");
        } else {
            judge = new RuleJudge();
            Demo.note("未设置密钥，使用「规则裁判」离线评测（生产可替换为 LLM 裁判）。");
        }

        // 评测两个样本（第 2 条是幻觉反例）
        String[][] cases = {
            {"退款多久", "退款审核通过后24小时内原路退回。", "退款24小时内到账。"},
            {"退款多久", "退款审核通过后24小时内原路退回。", "退款24小时内到账，换货需寄回门店。"}
        };
        Demo.note("逐个样本打分（Faithfulness 0~1，越高越忠实）：");
        for (String[] c : cases) {
            double s = judge.faithfulness(c[0], c[1], c[2]);
            System.out.println("  [" + judge.name() + "] faithfulness=" + String.format("%.2f", s)
                    + "  | " + c[2]);
        }

        // ===== 校准（calibration）：用人工标注集算一致率 =====
        Demo.note("校准（calibration）：用人工标注的 gold label 校验裁判一致性。");
        List<Integer> human = List.of(1, 0, 1, 0, 1, 0, 0, 0);
        List<Integer> ai = new ArrayList<>();
        for (int i = 0; i < human.size(); i++) {
            double sc = judge.faithfulness("退款", CALIB_CTX[i], CALIB_ANS[i]);
            ai.add(sc >= 0.6 ? 1 : 0);
        }
        double agree = agreement(human, ai);
        Demo.note("裁判与人类标注一致率 = " + String.format("%.2f", agree)
                + (agree >= 0.8
                    ? "（>0.8 可信，可直接采用）"
                    : "（<0.8 仅作初筛，需人工抽检 20%~30%）")
                + "。生产需每月重校防漂移（见 lesson03）。");

        // ===== 位置偏差提示（position bias）=====
        Demo.note("位置偏差提示：对比评测时裁判易偏好『排第一』的候选。"
                + "对策：交换 A/B 顺序各跑一次，仅当结论一致才采信。");
        BiFunction<String, String, String> naiveJudge = (a, b) ->
                // 模拟一个"偏好第一个候选（通常更长）"的有位置偏差的裁判
                (a.length() >= b.length()) ? "A胜" : "B胜";
        String verdict = balancedVerdict(naiveJudge, "答案甲（内容详尽）", "答案乙（简短）");
        Demo.note("naive 裁判对 (甲,乙) 与 (乙,甲) 的结论 -> " + verdict);
    }

    /** 一致率：裁判与人工标注相同的比例。 */
    static double agreement(List<Integer> human, List<Integer> ai) {
        long same = 0;
        for (int i = 0; i < human.size(); i++) {
            if (human.get(i).equals(ai.get(i))) same++;
        }
        return (double) same / human.size();
    }

    /**
     * 位置平衡（对抗位置偏差）：交换 A/B 顺序各评一次，
     * 若两次结论一致（含平局）才采信，否则标记「结论不稳，需人工复核」。
     */
    static String balancedVerdict(BiFunction<String, String, String> judgeFn, String a, String b) {
        String fwd = judgeFn.apply(a, b);  // 顺序 (A,B)
        String rev = judgeFn.apply(b, a);  // 顺序 (B,A)
        if (fwd.equals(rev)) return fwd + "（顺序无关，稳定）";
        return "不一致[" + fwd + "/" + rev + "]（存在位置偏差，需人工复核）";
    }

    /** 从 LLM 裁判返回中解析忠实度分值（true/false 或 score 字段）。 */
    static double parseScore(String resp) {
        if (resp == null) return 0.0;
        Matcher m = Pattern.compile("\"faithful\"\\s*:\\s*(true|false)").matcher(resp);
        if (m.find()) return Boolean.parseBoolean(m.group(1)) ? 1.0 : 0.0;
        m = Pattern.compile("\"score\"\\s*:\\s*([0-9.]+)").matcher(resp);
        if (m.find()) return Double.parseDouble(m.group(1));
        String low = resp.toLowerCase();
        return low.contains("不忠实") || low.contains("\"false\"") ? 0.0 : 0.5;
    }

    static String judgePrompt(String q, String context, String answer) {
        return "你是严格的评测裁判。只判断【回答】是否完全基于【上下文】，不引入上下文之外的信息。"
                + "输出 JSON：{\"faithful\": true/false, \"reason\": \"...\"}。\n"
                + "【问题】" + q + "\n【上下文】" + context + "\n【回答】" + answer;
    }

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

    // 校准集：上下文 / 答案 / 人工标注（1=忠实，0=幻觉），与 human 列表对齐
    private static final String[] CALIB_CTX = {
        "退款24小时内到账。",
        "退款24小时内到账。",
        "吊牌完好可退货。",
        "吊牌完好可退货。",
        "物流一般3天送达。",
        "退款24小时内到账。",
        "吊牌完好可退货。",
        "营业时间9点到18点。"
    };
    private static final String[] CALIB_ANS = {
        "退款24小时内到账。",
        "换货请到门店办理。",
        "吊牌完好可退货。",
        "七天无理由随便退。",
        "物流约3天送达。",
        "换货需到门店。",
        "随便退都行。",
        "我们24小时营业。"
    };
}

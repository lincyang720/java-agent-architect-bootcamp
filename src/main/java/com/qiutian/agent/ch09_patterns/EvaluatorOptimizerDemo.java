package com.qiutian.agent.ch09_patterns;

import com.qiutian.agent.common.Demo;

import java.util.ArrayList;
import java.util.List;

/**
 * 第九章第五节：评估-优化（Evaluator-Optimizer）演示。
 *
 * 核心思想：生成器（Generator）产出结果，评估器（Evaluator）挑毛病，
 * 两者反复迭代直到通过——把“质量判定”从一次性“靠运气”变成可迭代的工程过程。
 *
 * 本 Demo 用纯 Java 桩逻辑（离线可跑）：Generator 逐步补全客服文案，
 * Evaluator 用规则打分（必含订单号 + 必含补偿方案 + 长度达标）。
 * 带“最大迭代上限”硬护栏，避免死循环烧钱。
 *
 * 真实 LLM-as-Judge 可加 {@code if (!Demo.ensureKey("OPENAI_API_KEY")) return;} 守卫后调用。
 */
public class EvaluatorOptimizerDemo {

    /** 一次评估结果：是否通过 + 各维度检查 + 反馈。 */
    public record Evaluation(boolean pass, List<Check> checks, String feedback) {}

    public record Check(String name, boolean pass, String reason) {}

    /** 桩生成器：根据反馈逐步补全文案（模拟“带批评重生成”）。 */
    public static String generate(String task, String feedback, int iteration) {
        // 任务要求：含订单号 A100 与补偿方案（补偿券 50 元）
        StringBuilder sb = new StringBuilder();
        sb.append("非常抱歉给您带来不便");
        if (iteration >= 1) {
            sb.append("，订单 A100"); // 第 1 轮后补上订单号
        }
        if (iteration >= 2) {
            sb.append("，已为您发放 50 元补偿券"); // 第 2 轮后补上补偿
        }
        if (!feedback.isBlank()) {
            sb.append("（已参考上轮反馈）");
        }
        return sb.toString();
    }

    /** 桩评估器：规则校验（快且稳），返回是否通过 + 具体改进建议。 */
    public static Evaluation evaluate(String task, String output) {
        List<Check> checks = new ArrayList<>();
        boolean hasOrder = output.contains("A100");
        checks.add(new Check("含订单号", hasOrder, hasOrder ? "ok" : "缺少订单号 A100"));

        boolean hasCompensation = output.contains("补偿");
        checks.add(new Check("含补偿方案", hasCompensation, hasCompensation ? "ok" : "缺少补偿方案"));

        boolean enough = output.length() >= 15;
        checks.add(new Check("足够详细", enough, enough ? "ok" : "内容过短"));

        boolean pass = hasOrder && hasCompensation && enough;
        StringBuilder fb = new StringBuilder();
        if (!pass) {
            fb.append("请补充：");
            checks.stream().filter(c -> !c.pass())
                    .forEach(c -> fb.append(c.reason()).append("；"));
        }
        return new Evaluation(pass, checks, fb.toString());
    }

    public static void main(String[] args) {
        Demo.banner("评估-优化 Evaluator-Optimizer：生成→评估→迭代自反思循环");

        String task = "写客服道歉文案，需含订单号 A100 与补偿方案（补偿券 50 元）";
        int maxIter = 3; // 最大迭代上限（成本护栏）

        String output = "";
        String feedback = "";
        int iter = 0;
        boolean passed = false;

        for (int i = 0; i < maxIter; i++) {
            iter = i + 1;
            output = generate(task, feedback, i);
            Evaluation eval = evaluate(task, output);
            passed = eval.pass();

            Demo.note("==== 第 " + iter + " 轮 ====");
            Demo.note("生成产出：" + output);
            eval.checks().forEach(c ->
                    Demo.note((c.pass() ? "  ✅ " : "  ❌ ") + c.name() + " - " + c.reason()));

            if (passed) {
                Demo.note("评审 PASS，停止迭代 ✓");
                break;
            } else {
                Demo.note("评审 FAIL，反馈 → " + eval.feedback());
                feedback = eval.feedback(); // 注入下一轮生成
            }
        }

        Demo.note("==== 最终结果 ====");
        System.out.println("迭代次数=" + iter + " / 上限=" + maxIter);
        System.out.println("是否通过=" + (passed ? "是 ✓" : "否（已达上限，升级人工）"));
        System.out.println("最终文案=" + output);

        Demo.note("提示：停止条件必须有“最大迭代数”硬上限 + “早期退出”（连续 N 轮无提升）；"
                + "Evaluator 能上规则绝不调模型，关键产出用“规则 + LLM-Judge”双闸。");
    }
}

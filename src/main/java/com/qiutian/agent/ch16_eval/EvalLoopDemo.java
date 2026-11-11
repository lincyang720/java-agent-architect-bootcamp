package com.qiutian.agent.ch16_eval;

import com.qiutian.agent.common.Demo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 第十六章第4节 · 评测-归因-优化-回归闭环（调度骨架，纯 Java 离线可跑）。
 *
 * 演示一个最小闭环：
 *   跑一批 case -> 汇总指标 -> 对未达标样本做归因 -> badcase 回流（按失败层分桶） -> 回归护栏。
 * 真实项目把 attributions / badcase 落库并接 CI 卡点（见 lesson04）。
 */
public class EvalLoopDemo {

    /** 失败发生所在的层（归因用）。 */
    enum Layer { RETRIEVAL, GENERATION, TOOL, CONTEXT, POLICY }

    /** 一条评测样本及其实际得分（桩数据，真实来自 RAG 链路导出）。 */
    record Case(String id, String question, String answer,
                double faithfulness, double contextRecall, boolean toolCorrect) {
    }

    public static void main(String[] args) {
        Demo.banner("EvalLoopDemo · 评测-归因-优化-回归闭环（调度骨架）");

        // 1) 跑一批 case
        List<Case> batch = List.of(
            new Case("c1", "退款多久", "退款24小时内到账。", 0.95, 0.9, true),
            new Case("c2", "吊牌能退吗", "吊牌摘除不支持退货。", 0.9, 0.85, true),
            new Case("c3", "退款多久", "退款到账，换货需寄回门店。", 0.5, 0.9, true),  // 幻觉
            new Case("c4", "开发票", "已为您开票。", 0.8, 0.4, false));               // 检索+工具错

        // 2) 汇总指标
        double avgF = batch.stream().mapToDouble(Case::faithfulness).average().orElse(0);
        double avgR = batch.stream().mapToDouble(Case::contextRecall).average().orElse(0);
        Demo.note("批次指标：平均 Faithfulness=" + String.format("%.2f", avgF)
                + "  平均 ContextRecall=" + String.format("%.2f", avgR));

        // 3) 归因 + badcase 回流（按失败层分桶，便于修复后针对性回归）
        List<Case> badcases = batch.stream()
                .filter(c -> c.faithfulness() < 0.9 || c.contextRecall() < 0.8 || !c.toolCorrect())
                .collect(Collectors.toList());
        Demo.note("未达标 " + badcases.size() + " 条，进入归因与回流：");
        Map<Layer, List<String>> byLayer = new HashMap<>();
        for (Case c : badcases) {
            Layer layer = attribute(c);
            byLayer.computeIfAbsent(layer, k -> new ArrayList<>()).add(c.id());
            System.out.println("    " + c.id() + " -> 失败层=" + layer);
        }

        // 4) 回归护栏：优化后重跑，比对基线（faithfulness 不允许显著下降）
        Demo.note("回归护栏：优化后重跑，比对基线（Faithfulness 不允许显著下降）。");
        double newAvgF = 0.93; // 假设优化后的新得分
        boolean passed = newAvgF >= avgF;
        Demo.note("基线 Faithfulness=" + String.format("%.2f", avgF)
                + " -> 优化后=" + String.format("%.2f", newAvgF)
                + " => " + (passed ? "通过（无回归）" : "阻断（出现回归，禁止合并）"));
        Demo.note("闭环总结：badcase 已按失败层分桶（" + byLayer.keySet()
                + "），下次只跑对应桶做回归，黄金集持续演进（lesson04）。"
                + "CI 卡点示例：Faithfulness≥0.9 且 ContextRecall≥0.8 才放行合并。");
    }

    /** 简单归因：定位失败发生在哪一层（真实接血缘/追踪，见 lesson04 + 十五章血缘）。 */
    static Layer attribute(Case c) {
        if (!c.toolCorrect()) return Layer.TOOL;
        if (c.contextRecall() < 0.8) return Layer.RETRIEVAL;
        if (c.faithfulness() < 0.9) return Layer.GENERATION;
        return Layer.POLICY;
    }
}

package com.qiutian.agent.ch16_eval;

import com.qiutian.agent.common.Demo;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 第十六章第2节 · 组件级指标（离线，纯 Java）。
 *
 * 评测金字塔最底层：先把「检索」「工具选择」等组件评准，再拼端到端。
 * 这里用桩数据计算：
 *   - HitRate@K / Recall@K：检索是否召回了相关文档
 *   - MRR@K：第一个相关文档的排名质量
 *   - 工具选择正确率：意图 -> 期望工具
 *   - 确定性检查：结构化输出能否稳健解析
 * 全部纯 Java，离线可跑。
 */
public class ComponentMetricsDemo {

    public static void main(String[] args) {
        Demo.banner("ComponentMetricsDemo · 组件级评测指标（检索/工具/确定性）");

        // ---- 桩数据：每个 query 的相关文档 id 列表 + 实际检索返回列表 ----
        List<Set<String>> relevant = List.of(
                Set.of("d1", "d3"),
                Set.of("d2"),
                Set.of("d5", "d6"));
        List<List<String>> retrieved = List.of(
                List.of("d1", "d9", "d2"),
                List.of("d2", "d8"),
                List.of("d7", "d5", "d6"));

        int k = 3;
        double hitRate = hitRateAtK(relevant, retrieved, k);
        double recall = recallAtK(relevant, retrieved, k);
        double mrr = mrrAtK(relevant, retrieved, k);
        Demo.note("检索组件（K=" + k + "）：HitRate@K=" + pct(hitRate)
                + "  Recall@K=" + pct(recall) + "  MRR@K=" + String.format("%.3f", mrr));
        System.out.println("  HitRate=每个query至少一个相关文档进TopK；Recall=相关文档被召回比例；MRR=首个相关文档排名倒数。");

        // ---- 工具选择正确率 ----
        Map<String, String> kwTool = Map.of(
                "退款", "refund_tool", "物流", "logistics_tool", "发票", "invoice_tool");
        List<Map.Entry<String, String>> golden = List.of(
                Map.entry("我要退款", "refund_tool"),
                Map.entry("查物流到哪了", "logistics_tool"),
                Map.entry("开张发票", "invoice_tool"),
                Map.entry("随便聊聊", "fallback"));
        long correct = golden.stream()
                .filter(e -> route(e.getKey(), kwTool).equals(e.getValue())).count();
        double toolAcc = (double) correct / golden.size();
        Demo.note("工具选择正确率 = " + pct(toolAcc) + "（" + correct + "/" + golden.size()
                + "）。注意：真实场景还要测参数正确性（金额/订单号是否提取对）。");

        // ---- 确定性检查：结构化输出解析（解析失败应被捕获）----
        String llmOut = "好的，结果是：{\"orderId\":\"A123\",\"approved\":true}";
        boolean parsed = safeParse(llmOut);
        Demo.note("确定性检查：模型输出含前缀噪声时能否稳健提取 JSON？ " + parsed
                + "（这类确定逻辑用 JUnit + Mock 测，见 lesson02，又快又省。）");

        Demo.note("组件级评测性价比最高：RAG 80% 问题出在检索而非生成，先把这些指标卡进 CI（见 lesson04）。"
                + "阈值建议：HitRate@5≥0.9、Faithfulness≥0.9、ToolAccuracy≥0.95。");
    }

    static double hitRateAtK(List<Set<String>> relevant, List<List<String>> retrieved, int k) {
        int hits = 0;
        for (int i = 0; i < relevant.size(); i++) {
            Set<String> topk = new HashSet<>(retrieved.get(i).subList(0, Math.min(k, retrieved.get(i).size())));
            if (relevant.get(i).stream().anyMatch(topk::contains)) hits++;
        }
        return (double) hits / relevant.size();
    }

    static double recallAtK(List<Set<String>> relevant, List<List<String>> retrieved, int k) {
        double total = 0, hit = 0;
        for (int i = 0; i < relevant.size(); i++) {
            Set<String> topk = new HashSet<>(retrieved.get(i).subList(0, Math.min(k, retrieved.get(i).size())));
            total += relevant.get(i).size();
            hit += relevant.get(i).stream().filter(topk::contains).count();
        }
        return hit / total;
    }

    static double mrrAtK(List<Set<String>> relevant, List<List<String>> retrieved, int k) {
        double sum = 0;
        for (int i = 0; i < relevant.size(); i++) {
            List<String> topk = retrieved.get(i).subList(0, Math.min(k, retrieved.get(i).size()));
            for (int j = 0; j < topk.size(); j++) {
                if (relevant.get(i).contains(topk.get(j))) {
                    sum += 1.0 / (j + 1);
                    break;
                }
            }
        }
        return sum / relevant.size();
    }

    /** 关键词 -> 工具路由（与课程 lesson02 工具选择逻辑一致）。 */
    static String route(String intent, Map<String, String> kwTool) {
        for (var e : kwTool.entrySet()) {
            if (intent.contains(e.getKey())) return e.getValue();
        }
        return "fallback";
    }

    /** 确定性解析检查：从模型噪声输出中提取 JSON 骨架（生产用 Jackson 解析）。 */
    static boolean safeParse(String output) {
        try {
            String json = output.substring(output.indexOf("{"));
            return json.trim().endsWith("}") && json.contains("orderId");
        } catch (Exception e) {
            return false;
        }
    }

    static String pct(double v) {
        return String.format("%.0f%%", v * 100);
    }
}

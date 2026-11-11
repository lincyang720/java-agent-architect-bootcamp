package com.qiutian.agent.ch04_multiagent;

import com.qiutian.agent.common.Demo;

import java.util.List;
import java.util.Map;

/**
 * SequentialAndLoopDemo：顺序执行（Sequential）+ 循环（Loop：反思-修正）。
 *
 * <p>知识点（对应 lesson04）：
 * <ul>
 *   <li><b>Sequential</b>：节点用普通边串成一条线，本质是「状态接力」。适合步骤固定、无分支的流程。</li>
 *   <li><b>Loop</b>：条件边把下一跳指回自身节点形成循环，核心是「业务退出条件 + 最大尝试上限」双保险，
 *       对应 ReAct / 自迭代的底层机制。无上限 = 生产事故。</li>
 * </ul>
 *
 * <p>本 Demo 用桩逻辑跑通一条「检索 → 分析 → 报告（顺序）」流水线，
 * 再接一个「生成 → 自审 → 不达标重写（Loop）」的反思-修正循环，离线可运行。
 *
 * <p>框架标准写法（Spring AI Alibaba Graph）关键片段：
 * <pre>{@code
 *   graph.addEdge("generate", "review");
 *   graph.addConditionalEdges("review", edge_async(s -> {
 *       boolean ok = (Boolean) s.value("check", false);
 *       int tries = (Integer) s.value("tries", 0);
 *       return (ok || tries >= MAX_TRIES) ? "exit" : "retry";   // 退出条件 + 上限兜底
 *   }), Map.of("retry", "generate", "exit", StateGraph.END));
 * }</pre>
 */
public class SequentialAndLoopDemo {

    /** Loop 最大尝试次数——安全阀，防止模型永远不达标而无限烧钱。 */
    private static final int MAX_TRIES = 3;

    public static void main(String[] args) throws Exception {
        Demo.banner("SequentialAndLoopDemo · 顺序流水线 + 反思-修正 Loop");

        MiniGraph graph = new MiniGraph("seqThenLoop")
                // —— 顺序部分：检索 → 分析 → 报告（状态接力）——
                .addNode("retrieve", SequentialAndLoopDemo::retrieveNode)
                .addNode("analyze", SequentialAndLoopDemo::analyzeNode)
                .addNode("report", SequentialAndLoopDemo::reportNode)
                // —— 循环部分：生成 → 自审 →(不达标)→ 生成 …——
                .addNode("generate", SequentialAndLoopDemo::generateNode)
                .addNode("review", SequentialAndLoopDemo::reviewNode)
                .addEdge(MiniGraph.START, "retrieve")
                .addEdge("retrieve", "analyze")
                .addEdge("analyze", "report")
                .addEdge("report", "generate")   // 顺序尾部接到 Loop 起点
                .addEdge("generate", "review")
                // 条件边：达标或达上限则 EXIT，否则 RETRY（指回 generate 形成循环）
                .addConditionalEdges("review", SequentialAndLoopDemo::loopDecision,
                        Map.of("retry", "generate", "exit", MiniGraph.END));

        MiniGraph.State state = new MiniGraph.State();
        state.put("topic", "智能水杯");
        Demo.note("开始执行：先走顺序流水线（检索→分析→报告），再进入「生成-自审」Loop。");
        graph.run(state);

        Demo.note("全部完成，最终产物：");
        System.out.println("  报告: " + state.get("report"));
        System.out.println("  定稿宣传语: " + state.get("slogan"));
        System.out.println("  总迭代次数: " + state.get("tries") + "（上限 " + MAX_TRIES + "）");
        System.out.println("  自审轨迹: " + state.get("reviewLog"));
    }

    // ===== 顺序部分节点（桩逻辑） =====

    private static Map<String, Object> retrieveNode(MiniGraph.State state) {
        Demo.note("[顺序] 检索知识：根据主题拉取相关资料");
        return Map.of("docs", "相关资料：智能水杯支持水温监测、饮水提醒、App 联动。");
    }

    private static Map<String, Object> analyzeNode(MiniGraph.State state) {
        String docs = state.get("docs", "");
        Demo.note("[顺序] 分析资料：提取卖点");
        return Map.of("analysis", "卖点=健康饮水+智能提醒（来源：" + docs.substring(0, 6) + "…）");
    }

    private static Map<String, Object> reportNode(MiniGraph.State state) {
        Demo.note("[顺序] 生成报告：整合检索与分析");
        return Map.of("report", "《" + state.get("topic") + "》调研报告：主打健康与智能化。");
    }

    // ===== 循环部分节点（反思-修正） =====

    /** 生成节点：写 slogan，尝试次数 +1。桩逻辑让第 2 次起包含关键词从而达标。 */
    private static Map<String, Object> generateNode(MiniGraph.State state) {
        int tries = state.get("tries", 0);
        String slogan = (tries >= 1)
                ? "智能健康水杯，提醒你喝好每一口水"
                : "一款不错的水杯";   // 第 1 版不含关键词，自审会判不达标
        Demo.note("[Loop] 第 " + (tries + 1) + " 次生成宣传语：" + slogan);
        // messages 以列表追加，模拟框架的"追加通道"，记录每次产出
        List<String> log = state.has("reviewLog") ? state.get("reviewLog") : List.of();
        return Map.of("slogan", slogan, "tries", tries + 1, "reviewLog", log);
    }

    /** 自审节点：桩逻辑判定是否达标（含"智能"且"健康"即通过）。 */
    private static Map<String, Object> reviewNode(MiniGraph.State state) {
        String slogan = state.get("slogan", "");
        boolean pass = slogan.contains("智能") && slogan.contains("健康");
        List<String> log = state.get("reviewLog", List.of());
        log = new java.util.ArrayList<>(log);
        log.add((pass ? "PASS" : "FAIL") + ":" + slogan);
        Demo.note("[Loop] 自审结果：" + (pass ? "达标 ✅" : "未达标，将重写 🔁"));
        return Map.of("check", pass, "reviewLog", log);
    }

    /** 条件边决策：达标或达上限 → exit；否则 → retry。 */
    private static String loopDecision(MiniGraph.State state) {
        boolean ok = state.get("check", false);
        int tries = state.get("tries", 0);
        return (ok || tries >= MAX_TRIES) ? "exit" : "retry";
    }
}

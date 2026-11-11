package com.qiutian.agent.ch04_multiagent;

import com.qiutian.agent.common.Demo;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SupervisorAgentDemo：主控 Agent（Supervisor）协调多个子 Agent。
 *
 * <p>知识点（对应 lesson03 Supervisor 模式）：星型拓扑——一个主管 Agent 负责
 * 「理解任务 → 决定分发给谁 → 汇总子 Agent 结果」，子 Agent 之间不直接互联。
 *
 * <p>本 Demo 用桩逻辑实现 router 分类与子 Agent 作答，离线可跑。若接真实模型，
 * router 节点应先用 {@code if (!Demo.ensureKey("OPENAI_API_KEY")) return;} 守护；
 * 且生产推荐「规则优先 + 模型兜底」做分类，省成本降延迟。
 *
 * <p>框架标准写法（Spring AI Alibaba Graph，仅注释参考）：
 * <pre>{@code
 *   graph.addNode("router", node_async(s -> {
 *       String domain = chatClient.prompt().system("判断领域：客服/财务/技术").user(s.value("input","")).call().content();
 *       return Map.of("domain", domain);
 *   }));
 *   graph.addConditionalEdges("router", edge_async(s -> switch(s.value("domain","").toString()){
 *       case "财务" -> "finance"; case "技术" -> "tech"; default -> "customer"; }),
 *       Map.of("customer","customer","finance","finance","tech","tech"));
 *   // 子 Agent 干完都回流 aggregate，再 END
 * }</pre>
 */
public class SupervisorAgentDemo {

    public static void main(String[] args) throws Exception {
        Demo.banner("SupervisorAgentDemo · 主控 Agent 分派与汇总");
        Demo.note("场景：用户提问，Supervisor 判断领域后分派给对应子 Agent，最终汇总回复。");

        MiniGraph graph = new MiniGraph("supervisor")
                .addNode("router", SupervisorAgentDemo::routerNode)
                .addNode("customer", SupervisorAgentDemo::customerAgent)   // 客服子 Agent
                .addNode("finance", SupervisorAgentDemo::financeAgent)       // 财务子 Agent
                .addNode("tech", SupervisorAgentDemo::techAgent)            // 技术子 Agent
                .addNode("aggregate", SupervisorAgentDemo::aggregateNode)   // 汇总节点
                .addEdge(MiniGraph.START, "router")
                // 条件边：按 domain 路由到对应子 Agent（中心化分发）
                .addConditionalEdges("router", SupervisorAgentDemo::routeDecision,
                        Map.of("customer", "customer", "finance", "finance", "tech", "tech"))
                .addEdge("customer", "aggregate")
                .addEdge("finance", "aggregate")
                .addEdge("tech", "aggregate")
                .addEdge("aggregate", MiniGraph.END);

        // 用三条不同领域的任务，演示动态分发
        runTask(graph, "我的订单 ORD-778 物流停滞三天了，能催一下吗？");
        runTask(graph, "上个月信用卡被多扣了 200 元，怎么退款？");
        runTask(graph, "调用支付接口一直报 504 超时，是不是服务挂了？");

        Demo.note("要点：子 Agent 之间不直接通信，全部经 Supervisor 中转——这是『中心化控制』的代表，流程清晰易审计，"
                + "但 Supervisor 是单点，生产需降级/监控兜底。");
    }

    private static void runTask(MiniGraph graph, String task) throws Exception {
        System.out.println("\n──────── 用户任务 ────────\n  " + task);
        MiniGraph.State state = new MiniGraph.State();
        state.put("input", task);
        graph.run(state);
        System.out.println("  → " + state.get("final", ""));
    }

    /** 主控路由节点：桩逻辑按关键词判定领域（真实场景可让 LLM 分类）。 */
    private static Map<String, Object> routerNode(MiniGraph.State state) {
        String input = state.get("input", "");
        String domain;
        if (input.contains("退款") || input.contains("扣") || input.contains("信用卡") || input.contains("账单")) {
            domain = "finance";
        } else if (input.contains("接口") || input.contains("超时") || input.contains("报错") || input.contains("服务")) {
            domain = "tech";
        } else {
            domain = "customer";
        }
        Demo.note("Supervisor 路由：判定领域 = " + domain);
        return Map.of("domain", domain);
    }

    /** 条件边决策：把路由结果键直接映射为下一节点名（这里一一对应）。 */
    private static String routeDecision(MiniGraph.State state) {
        return state.get("domain", "customer");
    }

    /** 客服子 Agent（桩）：只懂客服领域。 */
    private static Map<String, Object> customerAgent(MiniGraph.State state) {
        String input = state.get("input", "");
        return Map.of("result_customer", "客服专员：已为您加急催办物流，预计 24h 内更新轨迹（任务：" + input.substring(0, 6) + "…）");
    }

    /** 财务子 Agent（桩）。 */
    private static Map<String, Object> financeAgent(MiniGraph.State state) {
        String input = state.get("input", "");
        return Map.of("result_finance", "财务专员：经核实存在重复扣款，已发起 200 元退款，3 日内到账（任务：" + input.substring(0, 6) + "…）");
    }

    /** 技术子 Agent（桩）。 */
    private static Map<String, Object> techAgent(MiniGraph.State state) {
        String input = state.get("input", "");
        return Map.of("result_tech", "技术专员：支付网关出现瞬时抖动，已切备用链路，请重试（任务：" + input.substring(0, 6) + "…）");
    }

    /** 汇总节点：把被选中的那个子 Agent 结果拼成最终回复。 */
    private static Map<String, Object> aggregateNode(MiniGraph.State state) {
        Map<String, String> results = new LinkedHashMap<>();
        if (state.has("result_customer")) results.put("客服", state.get("result_customer"));
        if (state.has("result_finance")) results.put("财务", state.get("result_finance"));
        if (state.has("result_tech")) results.put("技术", state.get("result_tech"));

        StringBuilder sb = new StringBuilder("【Supervisor 汇总】\n");
        results.forEach((k, v) -> sb.append("  · ").append(k).append("：").append(v).append("\n"));
        return Map.of("final", sb.toString().strip());
    }
}

package com.qiutian.agent.ch04_multiagent;

import com.qiutian.agent.common.Demo;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * HumanInTheLoopDemo：编排中插入人工审核节点（HITL，人在回路）。
 *
 * <p>知识点（对应 lesson05）：金融/医疗/政企等场景，高风险动作（退款、放款、删数据、对外发公告）
 * 不能全自动——必须在流程中留一个「人工审核节点」：模型生成 → 暂停等人审 → 人通过则执行/驳回则重写。
 *
 * <p>真实框架里人工节点用 {@code interrupt()} 原语「冻结状态 + 释放连接」，外部 {@code resume(threadId)}
 * 注入 {@code human_decision} 后续跑（异步中断-恢复，状态持久化）。本 Demo 用「模拟人工输入」表达同一语义，
 * 离线即可演示整条「起草 → 待确认 → 通过/驳回 → 发送」闭环，无需真实模型或 Key。
 *
 * <p>生产要点：① 中断点要少而精；② 状态需持久化（带 threadId）才能 resume；③ 必须有超时升级/默认策略，
 * 绝不能「无限等待人工」；④ 每次人工决策留痕审计。
 *
 * <p>框架标准写法（仅注释参考）：
 * <pre>{@code
 *   graph.addEdge("draft", "human");
 *   graph.addConditionalEdges("human", edge_async(s ->
 *       "APPROVE".equals(s.value("human_decision","REJECT")) ? "send" : "rewrite"),
 *       Map.of("send","send","rewrite","rewrite"));
 *   // 真实环境：human 节点调用 interrupt() 持久化状态；resume 时把 human_decision 写回同一 threadId
 * }</pre>
 */
public class HumanInTheLoopDemo {

    public static void main(String[] args) throws Exception {
        Demo.banner("HumanInTheLoopDemo · 人工审核节点（人在回路）");
        Demo.note("场景：模型起草退款话术 → 人工审核 → 通过则发送 / 驳回则重写再审核。");

        // 模拟「人工」依次给出的决策：第一次驳回，第二次通过（演示驳回→重写→再审核闭环）
        List<String> decisions = new ArrayList<>(List.of("REJECT", "APPROVE"));
        Iterator<String> humanInput = decisions.iterator();

        MiniGraph graph = new MiniGraph("hitl")
                .addNode("draft", HumanInTheLoopDemo::draftNode)
                // 人工审核节点：lambda 捕获模拟输入源，演示「暂停等人审」
                .addNode("human", (state) -> humanReviewNode(state, humanInput))
                .addNode("send", HumanInTheLoopDemo::sendNode)
                .addNode("rewrite", HumanInTheLoopDemo::rewriteNode)
                .addEdge(MiniGraph.START, "draft")
                .addEdge("draft", "human")
                .addConditionalEdges("human", HumanInTheLoopDemo::afterReview,
                        Map.of("send", "send", "rewrite", "rewrite"))
                .addEdge("send", MiniGraph.END)
                .addEdge("rewrite", "human");   // 驳回后回到人工节点再审核（闭环）

        MiniGraph.State state = new MiniGraph.State();
        state.put("orderId", "ORD-123");
        state.put("amount", "200");
        Demo.note("执行图：START → draft → human(暂停待审) →[通过]send / [驳回]rewrite→human …");
        graph.run(state);

        System.out.println("\n  最终结果: " + state.get("result", "(无)"));
        Demo.note("对比框架：human 节点即「中断点」，真实环境用 interrupt() 释放连接、resume(threadId) 续跑；"
                + "务必配超时升级策略，杜绝无限等待。");
    }

    /** 起草节点：生成退款话术（桩逻辑）。 */
    private static Map<String, Object> draftNode(MiniGraph.State state) {
        String orderId = state.get("orderId", "");
        String amount = state.get("amount", "");
        String draft = "尊敬的客户，您的订单 " + orderId + " 多扣的 " + amount
                + " 元已核实，我们将为您原路退款，预计 3 日内到账。";
        Demo.note("[起草] 模型生成退款话术");
        return Map.of("draft", draft, "status", "PENDING_HUMAN");
    }

    /** 人工审核节点：打印待确认内容，读取（模拟）人工决策后写回状态。 */
    private static Map<String, Object> humanReviewNode(MiniGraph.State state, Iterator<String> humanInput) {
        String draft = state.get("draft", "");
        // —— 这里即「中断点」：暂停流程，把待确认内容交给人 ——
        System.out.println("  ┌──────── 人工审核面板 ────────");
        System.out.println("  │ 待确认内容: " + draft);
        // 真实环境此处 interrupt() 并等待 resume；本例从模拟输入读取决策
        String decision = humanInput.hasNext() ? humanInput.next() : "APPROVE";
        System.out.println("  │ (模拟人工输入) 决策 = " + decision);
        System.out.println("  └────────────────────────────");
        Demo.note("人工审核节点：读取到决策 " + decision);
        // 写回 human_decision，下游条件边据此路由
        return Map.of("human_decision", decision, "status", "REVIEWED");
    }

    /** 条件边决策：通过 → send，否则 → rewrite。 */
    private static String afterReview(MiniGraph.State state) {
        return "APPROVE".equals(state.get("human_decision", "REJECT")) ? "send" : "rewrite";
    }

    /** 发送节点：被批准的高风险动作真正执行（桩）。 */
    private static Map<String, Object> sendNode(MiniGraph.State state) {
        Demo.note("[执行] 人工已批准，发送退款通知");
        return Map.of("result", "已发送给客户：" + state.get("draft", ""));
    }

    /** 重写节点：被驳回后改进措辞，再回到人工节点复核。 */
    private static Map<String, Object> rewriteNode(MiniGraph.State state) {
        String old = state.get("draft", "");
        String better = old + "（已精简，去掉冗余表述）";
        Demo.note("[重写] 上一版被驳回，改进措辞后再次提交审核");
        return Map.of("draft", better);
    }
}

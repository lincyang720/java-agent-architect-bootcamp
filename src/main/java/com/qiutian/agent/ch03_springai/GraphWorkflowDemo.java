package com.qiutian.agent.ch03_springai;

import com.qiutian.agent.common.Demo;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lesson06：Agent 工作流编排 —— 纯 Java 实现的极简 StateGraph / Workflow runner。
 *
 * <p><b>为什么不用真实的 Spring AI Alibaba Graph？</b> 它的 API 在不同版本细节有差异，
 * 为保「离线编译 + 可运行」，这里用纯 Java 实现一套 node / edge / start / end 的状态流转引擎，
 * 完整演示「节点加工 + 共享状态 + 条件路由 + 安全阀」的编排思想。文末给出
 * {@code com.alibaba.cloud.ai.graph.StateGraph} 的标准写法示意。</p>
 *
 * <p>本示例<b>不需要任何密钥</b>，可直接 {@code java -cp} 运行，观察工作流如何按状态流转。</p>
 */
public class GraphWorkflowDemo {

    /** 共享状态：贯穿整张图的键值字典（对应 OverAllState）。 */
    public static class State extends LinkedHashMap<String, Object> {
    }

    /** 节点：输入 State，返回要合并进 State 的增量 Map（对应 NodeAction.apply）。 */
    public interface Node {
        Map<String, Object> apply(State state);
    }

    /** 条件边路由：读 State，返回下一个节点名（对应 EdgeAction.apply）。 */
    public interface Router {
        String route(State state);
    }

    /** 极简工作流引擎：START → 节点 →（普通/条件）边 → ... → END。 */
    public static class Workflow {

        private final String name;
        private final Map<String, Node> nodes = new LinkedHashMap<>();
        private final Map<String, String> edges = new LinkedHashMap<>();        // 普通边 from -> to
        private final Map<String, Router> routers = new LinkedHashMap<>();      // 条件边路由
        private final Map<String, Map<String, String>> routes = new LinkedHashMap<>(); // 条件边映射 result -> 节点
        private final String start = "START";
        private final String end = "END";
        private int maxSteps = 50; // 安全阀：防止条件边配置错误导致死循环

        public Workflow(String name) {
            this.name = name;
        }

        public Workflow node(String name, Node node) {
            nodes.put(name, node);
            return this;
        }

        public Workflow edge(String from, String to) {
            edges.put(from, to);
            return this;
        }

        public Workflow edge(String from, Router router, Map<String, String> mapping) {
            routers.put(from, router);
            routes.put(from, mapping);
            return this;
        }

        /** 从 START 开始，按边流转执行节点，直到 END 或触发安全阀。 */
        public State run(State init) {
            String cur = start;
            int steps = 0;
            while (!end.equals(cur)) {
                if (++steps > maxSteps) {
                    Demo.note("[安全阀] 步数超过 " + maxSteps + "，强制结束，避免死循环");
                    break;
                }
                // 决定下一跳
                if (edges.containsKey(cur)) {
                    cur = edges.get(cur);
                } else if (routers.containsKey(cur)) {
                    String result = routers.get(cur).route(init);
                    Map<String, String> mapping = routes.get(cur);
                    cur = mapping.getOrDefault(result, mapping.getOrDefault("__default__", end));
                } else {
                    Demo.note("[结束] 节点 " + cur + " 没有出边，流程终止");
                    break;
                }
                // 执行节点并合并状态
                if (nodes.containsKey(cur)) {
                    Map<String, Object> delta = nodes.get(cur).apply(init);
                    if (delta != null) {
                        init.putAll(delta);
                    }
                    Demo.note("执行节点 [" + cur + "] -> 当前状态: " + init);
                }
            }
            return init;
        }
    }

    public static void main(String[] args) {
        Demo.banner("GraphWorkflowDemo · 极简工作流编排（纯 Java，可离线运行）");

        // 场景：客诉处理 —— 分类 -> 风险路由 -> 人工审核 / 自动回复 -> END
        Demo.note("构建工作流：START → 分类节点 → 风险路由 → (人工审核 | 自动回复) → END");

        State init = new State();
        init.put("complaint", "我要求退款，客服一直不处理，我要投诉！");

        Workflow wf = new Workflow("complaint-handling");
        wf.node("classify", s -> {
            String text = String.valueOf(s.get("complaint"));
            // 真实场景这里会调 ChatClient + 结构化输出；这里用确定性规则演示
            String risk = (text.contains("投诉") || text.contains("退款")) ? "HIGH" : "LOW";
            return Map.of("risk", risk, "category", "退换货");
        });
        wf.node("human_review", s -> Map.of("decision", "转人工审核（高风险客诉）"));
        wf.node("auto_reply", s -> Map.of("decision", "自动回复（低风险客诉）"));

        wf.edge("START", "classify");
        // 条件边：分类后按 risk 路由；映射里覆盖所有可能分支，并预留 __default__ 兜底
        wf.edge("classify",
                s -> String.valueOf(s.get("risk")),
                Map.of("HIGH", "human_review", "LOW", "auto_reply", "__default__", "auto_reply"));
        wf.edge("human_review", "END");
        wf.edge("auto_reply", "END");

        State result = wf.run(init);
        System.out.println("【最终结果】 决策=" + result.get("decision")
                + "，分类=" + result.get("category")
                + "，风险=" + result.get("risk"));

        /*
         * ============ 标准写法示意：com.alibaba.cloud.ai.graph.StateGraph ============
         * （仅示意，不编译进本示例；真实工程用下列 API 表达同样的 node/edge/state 思想）
         *
         * StateGraph graph = new StateGraph("complaint", keyStrategyFactory);
         * graph.addNode("classify",
         *     AsyncNodeAction.node_async(state ->
         *         Map.of("risk", state.value("risk", "LOW"))));
         * graph.addNode("human_review", AsyncNodeAction.node_async(...));
         * graph.addNode("auto_reply",  AsyncNodeAction.node_async(...));
         *
         * graph.addEdge(StateGraph.START, "classify");
         * graph.addConditionalEdges("classify",
         *     AsyncEdgeAction.edge_async(state -> state.value("risk", "LOW")),
         *     Map.of("HIGH", "human_review", "LOW", "auto_reply"));
         * graph.addEdge("human_review", StateGraph.END);
         * graph.addEdge("auto_reply",  StateGraph.END);
         *
         * CompiledGraph compiled = graph.compile();
         * Optional<OverAllState> r = compiled.invoke(
         *     Map.of("complaint", "..."),
         *     RunnableConfig.builder().threadId("t1").build());
         * ==========================================================================
         */

        Demo.note("示例结束。真实项目用 Spring AI Alibaba Graph 表达同样的 node/edge/state 思想，"
                + "并获得持久化、节点级 trace 等能力。");
    }
}

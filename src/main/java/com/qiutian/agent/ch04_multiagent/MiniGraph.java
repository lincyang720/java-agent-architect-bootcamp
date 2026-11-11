package com.qiutian.agent.ch04_multiagent;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极简「状态图 / 工作流」运行引擎（教学等价实现）。
 *
 * <p>本类用纯 Java 复刻 {@code com.alibaba.cloud.ai.graph.StateGraph} 的核心心智：
 * <ul>
 *   <li><b>节点 Node</b>：计算单元，消费 {@link State} 并返回要合并进状态的增量 Map
 *       （对应 Spring AI Alibaba 的 {@code NodeAction.apply(OverAllState)}）。</li>
 *   <li><b>边 Edge</b>：普通边顺序流转；条件边按运行时状态路由到不同下一节点
 *       （对应 {@code addEdge} / {@code addConditionalEdges}）。</li>
 *   <li><b>共享状态 OverAllState</b>：节点间通过状态传递数据（"状态接力"）。</li>
 *   <li><b>START / END</b>：图的入口与出口。</li>
 * </ul>
 *
 * <p>与框架版的对应（仅作注释参考，不引入框架依赖）：
 * <pre>{@code
 *   StateGraph graph = new StateGraph("demo", keyStrategyFactory);
 *   graph.addNode("summarize", AsyncNodeAction.node_async(new SummarizeNode(client)));
 *   graph.addNode("title",     AsyncNodeAction.node_async(new TitleNode()));
 *   graph.addEdge(StateGraph.START, "summarize");
 *   graph.addEdge("summarize", "title");
 *   graph.addEdge("title", StateGraph.END);
 *   CompiledGraph g = graph.compile();
 *   Optional<OverAllState> r = g.invoke(Map.of("input", "..."), config);
 * }</pre>
 *
 * <p>本引擎刻意保持最小：同步执行、单线程、按普通/条件边流转，直到 END 或达到步数上限
 * （防止条件边配错导致死循环）。多 Agent 的 Supervisor / Sequential / Loop / HITL 模式，
 * 均可在本引擎上以「节点 + 边」的方式表达——印证「Graph 是引擎，模式是跑在引擎上的运用」。
 */
public class MiniGraph {

    /** 共享状态：节点间流转的数据中枢（对应 OverAllState）。 */
    public static class State {
        private final Map<String, Object> data = new HashMap<>();

        public void put(String key, Object value) {
            data.put(key, value);
        }

        @SuppressWarnings("unchecked")
        public <T> T get(String key) {
            return (T) data.get(key);
        }

        @SuppressWarnings("unchecked")
        public <T> T get(String key, T def) {
            return data.containsKey(key) ? (T) data.get(key) : def;
        }

        public boolean has(String key) {
            return data.containsKey(key);
        }

        public Map<String, Object> raw() {
            return data;
        }

        @Override
        public String toString() {
            return data.toString();
        }
    }

    /** 节点：执行计算并返回写入状态的增量 Map（可为空）。 */
    @FunctionalInterface
    public interface Node {
        Map<String, Object> apply(State state) throws Exception;
    }

    /** 条件边路由：读状态，返回条件边的「结果键」。 */
    @FunctionalInterface
    public interface Router {
        String route(State state) throws Exception;
    }

    public static final String START = "__START__";
    public static final String END = "__END__";

    private final String name;
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, String> edges = new HashMap<>();
    private final Map<String, Router> condRouters = new HashMap<>();
    private final Map<String, Map<String, String>> condEdges = new HashMap<>();

    public MiniGraph(String name) {
        this.name = name;
    }

    public MiniGraph addNode(String nodeName, Node node) {
        nodes.put(nodeName, node);
        return this;
    }

    /** 普通边：from 执行完直接去 to（to 可为 END）。 */
    public MiniGraph addEdge(String from, String to) {
        edges.put(from, to);
        return this;
    }

    /** 条件边：from 执行完后由 router 决定走哪条映射分支。 */
    public MiniGraph addConditionalEdges(String from, Router router, Map<String, String> mapping) {
        condRouters.put(from, router);
        condEdges.put(from, mapping);
        return this;
    }

    /** 执行整张图，从 START 出发，沿边流转直到 END。 */
    public State run(State init) throws Exception {
        String cursor = edges.getOrDefault(START, null);
        if (cursor == null) {
            throw new IllegalStateException("图 [" + name + "] 没有从 START 出发的边");
        }
        int maxSteps = 1000; // 防御：条件边配错时防止死循环
        while (!END.equals(cursor) && maxSteps-- > 0) {
            Node node = nodes.get(cursor);
            if (node == null) {
                throw new IllegalStateException("图 [" + name + "] 存在未知节点: " + cursor);
            }
            Map<String, Object> delta = node.apply(init);
            if (delta != null) {
                init.raw().putAll(delta);
            }
            // 流转：优先条件边，否则走普通边
            if (condRouters.containsKey(cursor)) {
                String result = condRouters.get(cursor).route(init);
                String next = condEdges.get(cursor).get(result);
                if (next == null) {
                    throw new IllegalStateException(
                            "节点 [" + cursor + "] 路由结果 [" + result + "] 没有对应的边");
                }
                cursor = next;
            } else {
                cursor = edges.get(cursor);
            }
            if (cursor == null) {
                throw new IllegalStateException("节点 [" + cursor + "] 没有出边且不是 END，流程断头");
            }
        }
        if (maxSteps <= 0) {
            throw new IllegalStateException("图 [" + name + "] 执行超过最大步数，疑似死循环");
        }
        return init;
    }
}

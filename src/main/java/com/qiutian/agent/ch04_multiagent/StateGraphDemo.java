package com.qiutian.agent.ch04_multiagent;

import com.qiutian.agent.common.Demo;

import java.util.List;
import java.util.Map;

/**
 * StateGraphDemo：用「极简 MiniGraph 引擎」演示顺序工作流编排。
 *
 * <p>知识点（对应 lesson02 Graph 核心概念）：
 * Graph = 带共享状态的有向图，节点做计算、边管流转、状态在节点间流动。
 * 本 Demo 用纯 Java 桩逻辑跑通一条「输入 → 摘要 → 标题 → 结束」的顺序流水线，
 * <b>无需任何 API Key，离线即可运行</b>，便于先理解编排机制，再对照框架写法。
 *
 * <p>若接真实模型，标准写法（Spring AI Alibaba Graph）如下，注意先用
 * {@code if (!Demo.ensureKey("OPENAI_API_KEY")) return;} 守护：
 * <pre>{@code
 *   KeyStrategyFactory ksf = () -> {
 *       Map<String, KeyStrategy> m = new HashMap<>();
 *       m.put("input", Channel.<String>create().build());          // 值类型：后写覆盖
 *       m.put("messages", Channel.<List<String>>create(             // 通道类型：列表追加
 *           (cur, up) -> { List<String> x = new ArrayList<>(cur); x.addAll(up); return x; }).build());
 *       return m;
 *   };
 *   StateGraph graph = new StateGraph("docPipeline", ksf);
 *   graph.addNode("summarize", AsyncNodeAction.node_async(s -> {
 *       String doc = s.value("input", "").toString();
 *       String summary = chatClient.prompt().system("摘要专家").user(doc).call().content();
 *       return Map.of("summary", summary, "messages", List.of("summarize done"));
 *   }));
 *   graph.addNode("title", AsyncNodeAction.node_async(s ->
 *       Map.of("title", "【摘要】" + s.value("summary","").toString().substring(0, 10))));
 *   graph.addEdge(StateGraph.START, "summarize");
 *   graph.addEdge("summarize", "title");
 *   graph.addEdge("title", StateGraph.END);
 *   CompiledGraph g = graph.compile();
 *   Optional<OverAllState> r = g.invoke(Map.of("input", "长文档..."), config);
 * }</pre>
 */
public class StateGraphDemo {

    public static void main(String[] args) throws Exception {
        Demo.banner("StateGraphDemo · 顺序工作流编排（MiniGraph 极简引擎）");
        Demo.note("本例用桩逻辑替代真实模型调用，离线即可跑通，专注理解『节点+边+状态』三件套。");

        // ① 构建图：START → summarize → title → END
        MiniGraph graph = new MiniGraph("docPipeline")
                .addNode("summarize", StateGraphDemo::summarizeNode)
                .addNode("title", StateGraphDemo::titleNode)
                .addEdge(MiniGraph.START, "summarize")
                .addEdge("summarize", "title")
                .addEdge("title", MiniGraph.END);

        // ② 初始状态：放入输入文档
        MiniGraph.State state = new MiniGraph.State();
        state.put("input", "多智能体协作（Multi-Agent）通过任务分发与状态同步，让多个专职 Agent 协同完成复杂任务。");

        Demo.note("执行图：START → summarize → title → END（状态在节点间像接力棒一样传递）");
        graph.run(state);

        // ③ 读取最终状态
        Demo.note("执行完成，读取共享状态中的产物：");
        System.out.println("  摘要 : " + state.get("summary"));
        System.out.println("  标题 : " + state.get("title"));
        System.out.println("  消息流: " + state.get("messages"));

        Demo.note("对比框架：以上即 Spring AI Alibaba StateGraph 的『节点=计算、边=流转、OverAllState=共享状态』，"
                + "区别在于框架还提供通道归约(Channel/Reducer)、异步执行、线程隔离(threadId)与持久化续跑。");
    }

    /** 节点1：摘要桩逻辑（真实场景此处调 ChatClient）。写入 summary 与追加型 messages。 */
    private static Map<String, Object> summarizeNode(MiniGraph.State state) {
        String doc = state.get("input", "");
        // 桩：取前 18 字作为"摘要"
        String summary = doc.length() > 18 ? doc.substring(0, 18) + "…" : doc;
        // messages 用列表，模拟框架的"追加通道"——多次写入不会互相覆盖
        return Map.of("summary", summary, "messages", List.of("summarize done"));
    }

    /** 节点2：标题桩逻辑。消费 summary，输出 title。 */
    private static Map<String, Object> titleNode(MiniGraph.State state) {
        String summary = state.get("summary", "");
        String title = "【摘要】" + (summary.length() > 10 ? summary.substring(0, 10) : summary);
        return Map.of("title", title);
    }
}

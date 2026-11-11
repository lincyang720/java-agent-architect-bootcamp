package com.qiutian.agent.ch09_patterns;

import com.qiutian.agent.common.Demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 第九章第二节：路由（Routing）演示。
 *
 * 核心思想：用轻量决策层（分类器/规则）把输入映射到某个处理分支，
 * 让“专业的人干专业的事”。本 Demo 演示两种路由：
 *   1) 规则路由：关键词命中即分派到对应处理器；
 *   2) 置信度兜底：规则命中给出得分，低于阈值则转人工。
 *
 * 全部用纯 Java 桩逻辑（离线可跑）。真实 LLM 分类器路由可加
 * {@code if (!Demo.ensureKey("OPENAI_API_KEY")) return;} 守卫后调用模型。
 */
public class RoutingDemo {

    /** 一个路由规则：匹配关键词 + 分支名 + 处理函数。 */
    public record Route(String name, List<String> keywords, Function<String, String> handler) {}

    private final List<Route> routes = new ArrayList<>();
    private Function<String, String> fallback = (s) -> "【兜底】转人工客服";

    /** 注册一条规则路由。 */
    public RoutingDemo when(List<String> keywords, String name, Function<String, String> handler) {
        routes.add(new Route(name, keywords, handler));
        return this;
    }

    /** 注册兜底分支。 */
    public RoutingDemo fallback(Function<String, String> handler) {
        this.fallback = handler;
        return this;
    }

    /**
     * 规则路由：顺序匹配第一个命中关键词的分支。
     * 返回含“命中分支名 + 处理结果”的字符串，方便观察路由去向。
     */
    public String route(String input) {
        for (Route r : routes) {
            for (String k : r.keywords()) {
                if (input.contains(k)) {
                    return "[" + r.name() + "] " + r.handler().apply(input);
                }
            }
        }
        return fallback.apply(input);
    }

    /** 带置信度的路由决策：返回命中的意图与得分（用命中关键词数模拟）。 */
    public record Scored(String intent, double score, String result) {}

    public Scored routeWithConfidence(String input, double threshold) {
        int bestHits = -1;
        Route best = null;
        for (Route r : routes) {
            long hits = r.keywords().stream().filter(input::contains).count();
            if (hits > bestHits) {
                bestHits = (int) hits;
                best = r;
            }
        }
        if (best == null || bestHits <= 0) {
            return new Scored("UNKNOWN(转人工)", 0.0, fallback.apply(input));
        }
        // 用“命中关键词比例”模拟置信度
        double score = (double) bestHits / best.keywords().size();
        if (score < threshold) {
            return new Scored("UNKNOWN(低置信转人工)", score, fallback.apply(input));
        }
        return new Scored(best.name(), score, "[" + best.name() + "] " + best.handler().apply(input));
    }

    private static String extractBranch(String routed) {
        // routed 形如 "[refund] 执行退款工作流" 或 "【兜底】转人工客服"
        int start = routed.indexOf('[');
        int end = routed.indexOf(']');
        if (start >= 0 && end > start) {
            return routed.substring(start + 1, end);
        }
        return "通用/兜底";
    }

    public static void main(String[] args) {
        Demo.banner("路由 Routing：按意图把输入分派到不同处理函数");

        RoutingDemo router = new RoutingDemo();
        router.when(List.of("退款", "退货", "钱"), "refund", s -> "执行退款工作流")
              .when(List.of("物流", "发货", "快递", "到哪"), "logistics", s -> "查询物流工作流")
              .when(List.of("bug", "报错", "崩溃", "异常"), "tech", s -> "转技术 RAG Agent")
              .fallback(s -> "转人工客服");

        // —— 演示 1：规则路由，观察命中哪个分支 ——
        Demo.note("=== 规则路由：顺序命中第一个匹配分支 ===");
        String[] samples = {"我要退款", "快递到哪了", "APP 崩溃了", "你们态度太差了"};
        for (String s : samples) {
            Demo.note("输入：「" + s + "」 → " + router.route(s));
        }

        // —— 演示 2：置信度兜底，低于阈值转人工 ——
        Demo.note("=== 置信度兜底：阈值=0.5，低于则转人工 ===");
        String[] scored = {"我的钱什么时候退", "货到哪了", "你好"};
        for (String s : scored) {
            Scored sc = router.routeWithConfidence(s, 0.5);
            Demo.note("输入：「" + s + "」 → 意图=" + sc.intent()
                    + " 置信度=" + String.format("%.2f", sc.score()) + " 结果=" + sc.result());
        }

        // —— 演示 3：与提示链组合 —— 命中分支后走对应提示链（此处仅示意）——
        Demo.note("=== 路由 + 链：选中分支后，内部可继续跑一条提示链 ===");
        Map<String, String> chainHint = Map.of(
                "refund", "退款链(分类→查单→生成→质检)",
                "logistics", "物流链(改写→检索→重排→生成)",
                "tech", "技术链(改写→检索→引用溯源)");
        String hit = router.route("我要退款");
        String branch = extractBranch(hit);
        Demo.note("命中「" + branch + "」→ 内部链=" + chainHint.getOrDefault(branch, "通用链"));

        Demo.note("提示：真实分类器路由用便宜小模型 + temperature=0 输出枚举标签，"
                + "下游再按标签 switch 分派；低置信请求务必兜底转人工。");
    }
}

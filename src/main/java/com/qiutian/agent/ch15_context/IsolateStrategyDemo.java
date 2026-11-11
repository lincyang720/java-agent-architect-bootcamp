package com.qiutian.agent.ch15_context;

import com.qiutian.agent.common.Demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 第十五章第2节 · Isolate 策略：把不同子 Agent / 租户的上下文隔开。
 *
 * 演示「共享上下文 vs 隔离上下文」的差异：
 *   - 共享：所有子 Agent 共用同一份可变上下文，A 的残留会污染 B；
 *   - 隔离：每个子 Agent 拥有独立上下文容器，互不干扰，且可加租户断言。
 *
 * 纯 Java 离线可跑。
 */
public class IsolateStrategyDemo {

    /** 上下文容器：一个子 Agent（或租户）的独立视图。 */
    static final class ContextContainer {
        final String owner;
        final List<String> items = new ArrayList<>();

        ContextContainer(String owner) {
            this.owner = owner;
        }

        void add(String s) {
            items.add(s);
        }

        List<String> snapshot() {
            return List.copyOf(items);
        }
    }

    public static void main(String[] args) {
        Demo.banner("IsolateStrategyDemo · 上下文隔离策略（子Agent/租户隔离）");

        Demo.note("场景：一个 Supervisor 派出两个子 Agent —— 规划(Planner) 与 执行(Executor)。");
        String tenantSecret = "租户A的对账规则：金额必须为分单位"; // 敏感/任务专属信息

        // ===== 共享上下文（反例）=====
        Demo.note("【反例】共享上下文：两个子 Agent 共用一个可变容器。");
        ContextContainer shared = new ContextContainer("SHARED");
        shared.add(tenantSecret);
        shared.add("用户诉求：查询订单状态");
        // 执行 Agent 在处理时往共享容器追加了自己的中间结论
        shared.add("Executor 中间结论：订单 O1 状态=已发货");
        // 规划 Agent 随后读取共享容器 —— 可能误用 Executor 残留 + 看到不应见的内容
        ContextContainer plannerSeesShared = shared; // 同一引用
        boolean leaked = plannerSeesShared.items.stream().anyMatch(s -> s.contains("Executor"));
        boolean seesSecret = plannerSeesShared.items.stream().anyMatch(s -> s.contains("租户A"));
        Demo.note("规划 Agent 从共享上下文读到了 Executor 的中间结论？ " + leaked
                + "  —— 污染发生（且误见租户A专属规则？ " + seesSecret + "）。");

        // ===== 隔离上下文（正例）=====
        Demo.note("【正例】隔离上下文：每个子 Agent 独立容器，互不引用。");
        Map<String, ContextContainer> isolated = new ConcurrentHashMap<>();
        ContextContainer planner = isolated.computeIfAbsent("Planner", ContextContainer::new);
        ContextContainer executor = isolated.computeIfAbsent("Executor", ContextContainer::new);

        planner.add("用户诉求：查询订单状态");        // 仅规划所需
        executor.add(tenantSecret);                  // 仅执行所需（如要调用对账工具）
        executor.add("Executor 中间结论：订单 O1 状态=已发货");

        boolean plannerLeaked = planner.snapshot().stream().anyMatch(s -> s.contains("Executor"));
        boolean plannerSeesTenantA = planner.snapshot().stream().anyMatch(s -> s.contains("租户A"));
        Demo.note("规划 Agent 隔离容器内是否含 Executor 残留？ " + plannerLeaked);
        Demo.note("规划 Agent 是否误见租户A专属规则？ " + plannerSeesTenantA + "（false = 隔离成功）");

        // 租户断言（合规红线）：校验某容器只属于其 owner
        Demo.note("跨租户访问断言（合规红线）：校验某容器只属于其 owner。");
        boolean ok = assertNoLeak("Planner", planner) && assertNoLeak("Executor", executor);
        Demo.note("租户隔离断言通过？ " + ok);

        Demo.note("Isolate 要点：子 Agent/租户各自独立上下文 + 跨租户访问断言 + 工具大结果隔离，"
                + "避免上下文污染与泄漏（见 lesson02 / lesson04 / 第十二章安全治理）。");
    }

    /** 跨租户访问断言：容器必须只属于其声明的 owner。 */
    static boolean assertNoLeak(String expectedOwner, ContextContainer c) {
        return expectedOwner.equals(c.owner);
    }
}

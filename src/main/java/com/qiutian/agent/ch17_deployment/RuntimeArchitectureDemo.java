package com.qiutian.agent.ch17_deployment;

import com.qiutian.agent.common.Demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 第十七章第1节配套：Agent 运行时架构——无状态 vs 有状态 + 会话保持（纯 Java，离线可跑）。
 *
 * <p>核心结论（对应 lesson01）：
 * <ul>
 *   <li>无状态运行时：进程本身不持有会话，状态全部外置（这里是传入的 {@code externalStore}），
 *       任意实例都能处理任意 conversationId，因此可水平扩展、进程挂了不丢数据；</li>
 *   <li>有状态运行时：会话状态存在进程内存 Map 里，单机好写但难扩展、进程重启即失忆；</li>
 *   <li>会话保持：无论哪种，都要能靠 {@code conversationId} 取出/续写该会话的上下文。</li>
 * </ul>
 */
public class RuntimeArchitectureDemo {

    /** 一次会话的状态：对话历史 + 最后活跃时间（对应 lesson01 的「状态清单」）。 */
    public record SessionState(
            String conversationId,
            List<String> history,
            long lastActive) {
        SessionState append(String turn) {
            List<String> next = new ArrayList<>(history);
            next.add(turn);
            return new SessionState(conversationId, next, System.currentTimeMillis());
        }
    }

    /** 无状态运行时：状态由外部存储注入，自身零内存状态 → 可水平扩展。 */
    static final class StatelessRuntime {
        private final Map<String, SessionState> externalStore;

        StatelessRuntime(Map<String, SessionState> externalStore) {
            this.externalStore = externalStore;
        }

        String chat(String conversationId, String userMsg) {
            // 每轮都从「外部存储」取历史（真实场景是 Redis / PG），本机不持有
            SessionState state = externalStore.getOrDefault(conversationId,
                    new SessionState(conversationId, new ArrayList<>(), 0L));
            String reply = "（无状态实例）基于 " + state.history().size()
                    + " 条历史回复: 收到[" + userMsg + "]";
            externalStore.put(conversationId, state.append(userMsg).append(reply));
            return reply;
        }
    }

    /** 有状态运行时：会话状态绑死在进程内存 Map 里 → 易写但难扩展。 */
    static final class StatefulRuntime {
        private final Map<String, SessionState> localSessions = new ConcurrentHashMap<>();

        String chat(String conversationId, String userMsg) {
            SessionState state = localSessions.getOrDefault(conversationId,
                    new SessionState(conversationId, new ArrayList<>(), 0L));
            String reply = "（有状态实例）基于 " + state.history().size()
                    + " 条历史回复: 收到[" + userMsg + "]";
            SessionState next = state.append(userMsg).append(reply);
            localSessions.put(conversationId, next);
            return reply;
        }
    }

    public static void main(String[] args) {
        Demo.banner("RuntimeArchitectureDemo：无状态 vs 有状态 + 会话保持");

        // ① 无状态：所有实例共享同一个「外部存储」（相当于 Redis）
        Map<String, SessionState> sharedStore = new ConcurrentHashMap<>();
        StatelessRuntime s1 = new StatelessRuntime(sharedStore);
        StatelessRuntime s2 = new StatelessRuntime(sharedStore); // 可水平扩展的另一副本
        Demo.note("无状态：两个实例（s1/s2）共享外部存储，可任意扩副本");
        System.out.println("  s1 回复: " + s1.chat("c-1001", "帮我查订单"));
        System.out.println("  s2 回复: " + s2.chat("c-1001", "它到哪了")); // s2 能续上 s1 的会话
        Demo.note("→ 即使请求打到不同实例，靠 conversationId 都能续上历史（无状态化是默认正确解）。");

        // ② 有状态：状态在进程内存，换实例即失忆
        StatefulRuntime f1 = new StatefulRuntime();
        StatefulRuntime f2 = new StatefulRuntime();
        Demo.note("有状态：会话状态在 f1 进程内，f2 看不到");
        System.out.println("  f1 回复: " + f1.chat("c-2002", "你好"));
        System.out.println("  f2 回复: " + f2.chat("c-2002", "记得我刚说啥吗")); // 历史为 0
        Demo.note("→ 有状态实例间不共享内存，负载均衡打到不同机器会「失忆」（需 Sticky Session 兜底）。");

        // ③ 会话保持：直接观察 Map<conversationId, SessionState> 的存取
        Demo.note("会话保持：用 Map 维护 conversationId→state，可随时取出/续写。");
        SessionState state = new SessionState("c-3003", new ArrayList<>(), 0L)
                .append("用户: 我要退款").append("助手: 请提供订单号");
        sharedStore.put("c-3003", state);
        System.out.println("  c-3003 当前历史条数=" + sharedStore.get("c-3003").history().size());

        // ④ 容器化 / 水平扩展要点（注释）
        Demo.note("容器化与水平扩展要点（对应 lesson01）：");
        Demo.note("  1) 默认走无状态化：ChatMemory / Plan / 权限上下文外置 Redis/PG，进程不持有状态；");
        Demo.note("  2) 长任务执行位置最难外置 → 交给 Durable Execution 或任务队列异步化解耦；");
        Demo.note("  3) 镜像里只放 ${ENV}，密钥用 K8s Secret 注入；readiness/liveness 探针等真就绪；");
        Demo.note("  4) HPA 按并发/在途任务数扩缩，而非仅 CPU（Agent 多为 IO 等待密集）；");
        Demo.note("  5) 优雅下线：server.shutdown=graceful + preStop 摘流，长任务交给可恢复框架。");
        Demo.note("小结：把状态赶出进程，是 Agent 从 Demo 走向生产级部署的第一道关。");
    }
}

package com.qiutian.agent.ch09_patterns;

import com.qiutian.agent.common.Demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 第九章第六节：事件驱动架构中的 Agent（Agent 作为微服务 AI 门面）演示。
 *
 * 核心思想：把 Agent 放进消息协作体系——既可作为“事件消费者”响应领域事件，
 * 也可作为“生产者”发出新事件驱动下游；更进一步，把遗留微服务包一层 Agent 变成“AI 门面”。
 *
 * 本 Demo 用纯 Java 实现一个内存事件总线（发布/订阅），演示：
 *   1) 一个“客诉事件”被发布后，多个 Agent 订阅者并行消费；
 *   2) 消费后产生新事件（如退款指令），体现 Saga 式长事务中的 Agent 决策节点；
 *   3) Agent 作为 AI 门面，把"说人话"的查询翻译成对遗留微服务（桩）的调用并聚合。
 * 全部离线可跑；真实项目把总线换成 Kafka/RabbitMQ、把桩换成 MCP/HTTP 即可。
 */
public class EventDrivenAgentDemo {

    /** 统一事件契约：所有 Agent 间消息遵循此结构（呼应课程事件 Schema 设计）。 */
    public record AgentEvent(
            String specVersion,
            String eventId,
            String eventType,
            String source,
            String traceId,
            Map<String, Object> data) {}

    /** 极简内存事件总线：topic -> 订阅者列表。 */
    public static class EventBus {
        private final Map<String, List<Consumer<AgentEvent>>> subscribers = new ConcurrentHashMap<>();

        /** 订阅某个 topic。 */
        public void subscribe(String topic, Consumer<AgentEvent> handler) {
            subscribers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(handler);
        }

        /** 发布事件给所有订阅者（同步、内存，真实用 Kafka 异步）。 */
        public void publish(AgentEvent event) {
            Demo.note("总线发布事件 eventType=" + event.eventType()
                    + " (traceId=" + event.traceId() + ")");
            List<Consumer<AgentEvent>> handlers = subscribers.get(event.eventType());
            if (handlers != null) {
                for (Consumer<AgentEvent> h : handlers) {
                    h.accept(event);
                }
            }
        }
    }

    public static void main(String[] args) {
        Demo.banner("事件驱动 Agent：内存事件总线 + Agent 作为微服务 AI 门面");

        EventBus bus = new EventBus();

        // —— 模式一：Agent 作为事件消费者（多订阅者并行）——
        Demo.note("=== 模式一：多个 Agent 订阅同一客诉事件流，各管一摊 ===");
        // 客诉处理 Agent：消费后生成处理建议，并发出新事件（退款指令）
        bus.subscribe("complaint.created", evt -> {
            String orderId = (String) evt.data().get("orderId");
            Demo.note("[客诉Agent] 消费客诉，生成建议：订单 " + orderId + " 建议自动退款");
            AgentEvent cmd = new AgentEvent(
                    "1.1", "evt-refund-1", "refund.command",
                    "complaint-agent", evt.traceId(),
                    Map.of("orderId", orderId, "action", "REFUND", "reason", "客诉成立"));
            bus.publish(cmd); // 生产者：发出退款指令，由支付服务消费
        });
        // 风控 Agent：并行消费，做风险标记
        bus.subscribe("complaint.created", evt -> {
            Demo.note("[风控Agent] 消费客诉，风险评级：低");
        });

        // —— 模式二：Agent 作为生产者（命令事件驱动下游）——
        Demo.note("=== 模式二：Agent 决策后发命令事件，由 owner 服务执行（Saga 风格）===");
        bus.subscribe("refund.command", evt -> {
            String orderId = (String) evt.data().get("orderId");
            Demo.note("[支付服务] 消费退款指令，执行退款 orderId=" + orderId
                    + "（真实场景：支付服务是事件 owner，Agent 只发命令不直写库）");
            AgentEvent done = new AgentEvent(
                    "1.1", "evt-refund-done-1", "refund.done",
                    "payment-service", evt.traceId(),
                    Map.of("orderId", orderId, "status", "REFUNDED"));
            bus.publish(done);
        });
        bus.subscribe("refund.done", evt -> {
            Demo.note("[通知Agent] 退款完成，已通知用户（事件闭环）");
        });

        // 触发：发布一个客诉事件
        AgentEvent complaint = new AgentEvent(
                "1.1", "evt-9f3a", "complaint.created",
                "order-service", "trace-abc123",
                Map.of("orderId", "A100", "intent", "REFUND"));
        bus.publish(complaint);

        // —— 模式三：Agent 作为 AI 门面（把遗留微服务包成"能听懂人话"的接口）——
        Demo.note("=== 模式三：Agent 作为 AI 门面，把自然语言查询翻译成对遗留微服务的调用 ===");
        // 三个遗留微服务（桩），真实是 RestTemplate/WebClient 调订单/支付/物流系统
        Map<String, String> orderSvc = Map.of("A100", "订单 A100 状态:已退款");
        Map<String, String> paymentSvc = Map.of("A100", "支付 A100 已原路退回");
        Map<String, String> logisticsSvc = Map.of("A100", "物流 A100 已拦截");

        // AI 门面：理解意图 → 选工具 → 聚合 → 返回"人话"
        String userAsk = "订单 A100 的退款进度怎么样了？";
        Demo.note("用户说人话：「" + userAsk + "」");
        // 桩：门面把问题路由到三个微服务并聚合（真实用 LLM + @Tool 选工具）
        String facadeAnswer = String.join("；",
                orderSvc.get("A100"),
                paymentSvc.get("A100"),
                logisticsSvc.get("A100"));
        System.out.println("AI 门面返回：" + facadeAnswer);

        Demo.note("提示：消息 at-least-once 投递要求消费者幂等（事件 ID / 业务幂等键去重），"
                + "否则重复客诉会重复退款；Agent 应发命令事件而非直写数据库，权限与一致性在 owner 服务收敛。");
    }
}

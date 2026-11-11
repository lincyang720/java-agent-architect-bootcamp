package com.qiutian.agent.ch11_mcp;

import com.qiutian.agent.common.Demo;

import java.util.ArrayList;
import java.util.List;

/**
 * 第十一章第4节配套：A2A（Agent-to-Agent）协作协议骨架（纯 Java，离线可跑）。
 *
 * <p>演示 A2A 最核心的「能力发现 → 任务委派 → 状态推进 → 交付产物」四步：
 * <ol>
 *   <li>{@link AgentCard}：对端 Agent 的「数字名片」，自描述它会什么（对应 HTTP 的
 *       {@code GET /.well-known/agent.json}）；</li>
 *   <li>{@link Task}：一次协作单元，带生命周期状态（submitted → working → completed）；</li>
 *   <li>{@link Message} / {@link Artifact}：Agent 间传递的内容 / 最终交付物。</li>
 * </ol>
 *
 * <p>这里用进程内方法调用模拟「编排 Agent 调对端 Agent」；真实 A2A 走 HTTP + JSON-RPC，
 * 节奏完全一致（先 discover 拉 AgentCard，再 tasks/send 委派）。
 */
public class A2ADemo {

    /** 对端 Agent 的能力自描述（A2A 的 AgentCard）。 */
    public record AgentCard(
            String protocolVersion,
            String name,
            String description,
            String url,
            List<Skill> skills,
            Capabilities capabilities) {

        public record Skill(String id, String name, String description) {}

        public record Capabilities(boolean streaming, boolean pushNotifications) {}
    }

    /** 任务生命周期状态机（与 A2A 规范一致）。 */
    enum TaskState {
        SUBMITTED, WORKING, INPUT_REQUIRED, COMPLETED, FAILED
    }

    /** Agent 间传递的一条消息。 */
    public record Message(String role, String text) {}

    /** Task 最终交付物。 */
    public record Artifact(String name, String text) {}

    /** 一次协作任务：有状态、有产物。 */
    public static final class Task {
        final String id;
        TaskState state;
        final List<Message> messages = new ArrayList<>();
        final List<Artifact> artifacts = new ArrayList<>();

        Task(String id) {
            this.id = id;
            this.state = TaskState.SUBMITTED;
        }

        void addMessage(Message m) {
            messages.add(m);
        }

        void addArtifact(Artifact a) {
            artifacts.add(a);
        }

        @Override
        public String toString() {
            return "Task{id=" + id + ", state=" + state
                    + ", messages=" + messages.size()
                    + ", artifacts=" + artifacts + "}";
        }
    }

    /** 被委派的「专精 Agent」：合同审查 Agent。 */
    static final class ContractReviewAgent {
        /** ① 能力发现：返回自己的 AgentCard（真实场景由 HTTP 端点暴露）。 */
        AgentCard card() {
            return new AgentCard(
                    "0.2.0",
                    "合同审查 Agent",
                    "审查销售合同中的风险条款",
                    "https://agents.internal/contract-review",
                    List.of(new AgentCard.Skill(
                            "risk-scan", "风险扫描", "识别合同中的不公平条款")),
                    new AgentCard.Capabilities(true, false));
        }

        /** ② 接收任务委派：解析用户消息，产出 Artifact，推进状态机。 */
        Task handleTask(Task task) {
            task.state = TaskState.WORKING;
            String request = task.messages.get(task.messages.size() - 1).text();
            Demo.note("  [对端] 收到委派，正在审查：" + request);
            // 模拟业务逻辑：识别风险点
            String report = "发现 3 处风险：免责条款过宽、管辖法院不利、违约金畸高";
            task.addArtifact(new Artifact("risk-report", report));
            task.state = TaskState.COMPLETED;
            return task;
        }
    }

    /** 编排 Agent：发现对端能力 → 创建任务 → 委派 → 收回产物。 */
    static final class OrchestratorAgent {
        private final ContractReviewAgent peer = new ContractReviewAgent();

        void run(String userIntent) {
            // ① 能力发现（discover）
            AgentCard card = peer.card();
            Demo.note("编排 Agent 拉取对端 AgentCard：name=" + card.name()
                    + "，skills=" + card.skills().get(0).name());

            // ② 创建任务并委派（tasks/send）
            Task task = new Task("task-" + System.nanoTime());
            task.addMessage(new Message("user", userIntent));
            Demo.note("编排 Agent 创建 " + task + "，委派给对端");
            Task result = peer.handleTask(task);

            // ③ 收回产物
            Demo.note("编排 Agent 收到返回：" + result);
            result.artifacts.forEach(a ->
                    System.out.println("  ▸ 交付物[" + a.name() + "]=" + a.text()));
        }
    }

    public static void main(String[] args) {
        Demo.banner("A2ADemo：Agent-to-Agent 协作（AgentCard + Task 消息交换）");

        OrchestratorAgent orchestrator = new OrchestratorAgent();
        orchestrator.run("扫描这份 NDA 的风险点");

        Demo.note("小结：MCP 管「模型连工具（向下）」，A2A 管「Agent 连 Agent（横向）」；"
                + "二者互补。真实生产里编排 Agent 同时持有 A2A Client（连同伴）与 MCP Client（连工具）。");
    }
}

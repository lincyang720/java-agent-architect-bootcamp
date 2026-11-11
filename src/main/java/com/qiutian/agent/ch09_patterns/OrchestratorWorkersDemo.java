package com.qiutian.agent.ch09_patterns;

import com.qiutian.agent.common.Demo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 第九章第四节：编排-执行（Orchestrator-Workers）演示。
 *
 * 核心思想：当“事先不知道要拆几步”时，由一个主控（Orchestrator）动态拆解任务、
 * 派发给一群专精的子 Agent（Worker），再汇总结果。与路由/链的区别在于步骤数/依赖动态未知。
 *
 * 本 Demo 用纯 Java 桩逻辑（离线可跑）：
 *   - Orchestrator 先“规划”出子任务清单（桩：按主题关键词拆领域）；
 *   - 再按领域从 Worker 注册表里选 Worker 派发（可并发）；
 *   - 收齐结果后聚合去重成报告；Worker 失败用退避重试保护。
 *
 * 真实 LLM 规划/执行可加 {@code if (!Demo.ensureKey("OPENAI_API_KEY")) return;} 守卫。
 */
public class OrchestratorWorkersDemo {

    /** 子 Agent（Worker）统一契约：吃一个子任务，产出结果字符串。 */
    public interface Worker {
        String run(String task);
    }

    /** Worker 注册表：领域 -> 对应 Worker 实现。Orchestrator 按领域选 Worker（路由思想的升级）。 */
    public static class WorkerRegistry {
        private final Map<String, Worker> registry = new LinkedHashMap<>();

        public WorkerRegistry register(String domain, Worker w) {
            registry.put(domain, w);
            return this;
        }

        public Worker get(String domain) {
            return registry.get(domain);
        }

        public boolean contains(String domain) {
            return registry.containsKey(domain);
        }
    }

    /** 一个子任务：领域 + 描述。 */
    public record SubTask(String domain, String description) {}

    /** Orchestrator：规划 + 派发 + 聚合。 */
    public static class Orchestrator {
        private final WorkerRegistry registry;
        private final int maxRetry;

        public Orchestrator(WorkerRegistry registry, int maxRetry) {
            this.registry = registry;
            this.maxRetry = maxRetry;
        }

        /**
         * 桩规划：根据主题关键词拆出 2~3 个领域子任务。
         * 真实场景可让 LLM 输出结构化计划，但代码层必须白名单校验领域合法性。
         */
        public List<SubTask> plan(String topic) {
            List<SubTask> plan = new ArrayList<>();
            if (topic.contains("市场") || topic.contains("规模") || topic.contains("客服")) {
                plan.add(new SubTask("market", "分析目标市场规模与用户画像"));
            }
            if (topic.contains("技术") || topic.contains("架构") || topic.contains("落地")) {
                plan.add(new SubTask("tech", "评估关键技术成熟度与落地风险"));
            }
            if (topic.contains("竞品") || topic.contains("对手")) {
                plan.add(new SubTask("competitor", "梳理主要竞品与差异化"));
            }
            if (plan.isEmpty()) {
                plan.add(new SubTask("general", "做通用调研"));
            }
            return plan;
        }

        /** 派发单个子任务，带指数退避重试（失败隔离）。 */
        private String dispatchWithRetry(SubTask task) {
            Worker w = registry.get(task.domain());
            if (w == null) {
                return "【" + task.domain() + "】无对应 Worker，跳过";
            }
            int attempt = 0;
            while (attempt <= maxRetry) {
                try {
                    return "【" + task.domain() + "】" + w.run(task.description());
                } catch (RuntimeException e) {
                    attempt++;
                    if (attempt > maxRetry) {
                        return "【" + task.domain() + "】失败已升级人工: " + task.description();
                    }
                    Demo.sleep(100L * attempt); // 退避
                }
            }
            return "【" + task.domain() + "】未知状态";
        }

        /**
         * 执行编排：先规划，再并发派发所有子任务，最后聚合。
         */
        public String orchestrate(String topic) {
            List<SubTask> plan = plan(topic);
            Demo.note("Orchestrator 规划出 " + plan.size() + " 个子任务："
                    + plan.stream().map(SubTask::domain).toList());

            ExecutorService pool = Executors.newFixedThreadPool(plan.size());
            Map<String, String> results = new java.util.concurrent.ConcurrentHashMap<>();
            try {
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (SubTask t : plan) {
                    futures.add(CompletableFuture.runAsync(() -> {
                        // 合法性校验：只派发注册表内的领域，杜绝不可信输入
                        if (registry.contains(t.domain())) {
                            results.put(t.domain(), dispatchWithRetry(t));
                        } else {
                            results.put(t.domain(), "【" + t.domain() + "】非法领域，拒绝执行");
                        }
                    }, pool));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } finally {
                pool.shutdown();
                try {
                    pool.awaitTermination(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            // 聚合：去重合并（LinkedHashMap 保序）
            StringBuilder report = new StringBuilder();
            for (SubTask t : plan) {
                report.append(results.get(t.domain())).append("\n");
            }
            return report.toString();
        }
    }

    public static void main(String[] args) {
        Demo.banner("编排-执行 Orchestrator-Workers：研报主控协调多子 Agent");

        // 注册专精 Worker（桩实现，真实场景背后是 MCP 工具 / 内部 API）
        WorkerRegistry registry = new WorkerRegistry();
        registry.register("market", task -> "市场规模年增 30%，目标客群以中小企业为主")
                .register("tech", task -> "关键技术已成熟，落地主要风险在数据与合规")
                .register("competitor", task -> "头部竞品 3 家，差异化空间在行业模板")
                .register("general", task -> "通用调研完成");

        Orchestrator orch = new Orchestrator(registry, 3);
        String topic = "分析 AI Agent 在企业客服的落地，并对比竞品";

        Demo.note("输入主题：" + topic);
        String report = orch.orchestrate(topic);

        Demo.note("==== Orchestrator 聚合报告 ====");
        System.out.println(report);

        Demo.note("提示：Orchestrator 的拆解与派发尽量用代码 + 结构化输出，"
                + "不要让 LLM 自由决定一切；LLM 生成的派发计划属不可信输入，必须白名单校验。");
    }
}

package com.qiutian.agent.ch17_deployment;

import com.qiutian.agent.common.Demo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 第十七章第2节配套：Durable Execution 持久化执行演示（纯 Java，离线可跑）。
 *
 * <p>模拟一个「多步长任务工作流」：每跑完一步就把「检查点（已完成的步序号）+ 该步结果」
 * 写入一个简单状态存储（这里用内存 {@link StateStore}；生产可换成 DB/Redis/文件）。
 * 中途「崩溃」后，用同一个存储重新执行，会从最后一个已完成的步骤<b>续跑</b>，
 * 已完成步骤不重跑、不重复产生副作用——这就是 Durable Execution 的精髓。
 *
 * <p>生产级等价：Temporal 的 Workflow+Activity（事件溯源 + 确定性重放）或 Restate 的
 * {@code ctx.run(...)}（只执行一次）+ {@code awakeable}（持久化等待），见文末注释。
 */
public class DurableExecutionDemo {

    /** 极简持久化状态存储：保存检查点与每步结果（可替换为文件/Redis）。 */
    static final class StateStore {
        private final Map<String, Object> data = new ConcurrentHashMap<>();

        void put(String key, Object value) {
            data.put(key, value);
        }

        Object get(String key) {
            return data.get(key);
        }

        boolean has(String key) {
            return data.containsKey(key);
        }
    }

    /**
     * 执行工作流。从「检查点之后的第一步」开始，逐歩执行并把检查点落盘。
     *
     * @param crashAfter 模拟在跑完第几步后崩溃（0-based）；传负数表示不崩溃
     */
    static void execute(String taskId, List<String> steps, StateStore store, int crashAfter) {
        // 从检查点续跑：已完成的最大步序号 + 1
        Integer checkpoint = (Integer) store.get(taskId + ":checkpoint");
        int start = (checkpoint == null) ? 0 : checkpoint + 1;

        for (int i = start; i < steps.size(); i++) {
            String step = steps.get(i);
            Demo.note("执行步骤 [" + (i + 1) + "/" + steps.size() + "]: " + step);
            // 模拟业务：每步都是「重活」（调 LLM / 外部系统），用结果落盘保证可恢复
            String result = "OK-" + step + "-" + (System.nanoTime() % 100000);
            store.put(taskId + ":step" + i, result);
            store.put(taskId + ":checkpoint", i);   // 检查点：已可靠完成第 i 步

            if (i == crashAfter) {
                Demo.note("💥 模拟进程崩溃（crashAfter=" + i + "），任务中断，但检查点已持久化到存储");
                throw new RuntimeException("simulated crash at step " + i);
            }
        }
        Demo.note("✅ 全部步骤完成（无崩溃），任务交付。");
    }

    public static void main(String[] args) {
        Demo.banner("DurableExecutionDemo：带检查点的长任务续跑（崩溃可恢复）");

        List<String> steps = List.of(
                "拉取订单系统数据",
                "调用风控引擎评分",
                "生成对账差异报告",
                "推送人工审批",
                "发布最终结果");
        StateStore store = new StateStore();
        String taskId = "recon-20261111";

        // —— 第一次运行：跑到第 2 步（index=1）后崩溃 ——
        Demo.note("=== 第一次运行：执行到第 2 步时被「杀死」===");
        try {
            execute(taskId, steps, store, 1);
        } catch (RuntimeException e) {
            Demo.note("捕获异常：" + e.getMessage() + "（生产里此处进程已退出，存储留在外部）");
        }

        // 进程「重启」后，用同一个存储重新执行 → 从检查点续跑
        Demo.note("=== 进程重启，复用同一 StateStore 重新执行（应跳过已完成步骤）===");
        Integer cp = (Integer) store.get(taskId + ":checkpoint");
        Demo.note("恢复检查点：已完成到第 " + (cp == null ? "无" : (cp + 1)) + " 步");
        execute(taskId, steps, store, -1); // 这次不再崩溃

        Demo.note("验证：第 1、2 步结果来自持久化存储，未重跑 ——");
        System.out.println("  step0 结果=" + store.get(taskId + ":step0"));
        System.out.println("  step1 结果=" + store.get(taskId + ":step1"));

        // —— 生产级等价写法（注释）——
        // Temporal：Workflow 是确定性「剧本」，Activity 是「重活」（可重试/超时/幂等）
        //   public void execute(String taskId) {
        //       String plan = activities.makePlan(taskId);
        //       String data = activities.fetchData(taskId);   // 崩溃重放时不重跑
        //       String draft = activities.draftResult(plan, data);
        //       activities.sendForApproval(taskId, draft);
        //       activities.publish(taskId, draft);
        //   }
        // Restate：ctx.run 保证花括号内只真正执行一次，awakeable 持久化等待人工确认
        //   String data = ctx.run(String.class, () -> fetchData(taskId));
        //   String decision = ctx.awakeable(String.class).await();
        Demo.note("小结：把「执行到了哪一步」交给持久化存储，而非易失内存，"
                + "崩溃后从检查点续跑、已完成步骤不重跑——这就是 Durable Execution（Temporal/Restate）的内核。");
    }
}

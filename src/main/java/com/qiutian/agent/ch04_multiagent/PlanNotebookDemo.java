package com.qiutian.agent.ch04_multiagent;

import com.qiutian.agent.common.Demo;

import java.util.ArrayList;
import java.util.List;

/**
 * PlanNotebookDemo：任务规划与状态跟踪器（Plan + 状态机）。
 *
 * <p>知识点（对应 lesson08 PlanNotebook）：纯 ReAct 容易「走一步看一步」导致重复劳动、遗漏步骤、目标漂移。
 * PlanNotebook 让 Agent 先列计划、再执行、边做边更新状态——把「隐式记忆」变成「可观测的状态机」。
 *
 * <p>本 Demo 用纯 Java 实现一个最小计划本：子任务有状态 {@code TODO / DOING / DONE / ABANDONED}，
 * 模拟 Agent 顺序领取子任务、执行、标记完成，并演示中途 {@code revise}（老板说不要 PPT 了 → 放弃该子任务）。
 * 离线可运行，无需模型或 Key。
 *
 * <p>框架对应（AgentScope Java，仅注释参考，pom 未引入该依赖）：
 * <pre>{@code
 *   PlanNotebook nb = PlanNotebook.builder()
 *       .maxSubtasks(10)              // 限制计划规模，防爆炸
 *       .needUserConfirm(true)        // 建完计划等人确认再执行（内置 HITL）
 *       .storage(new RedisPlanStorage(redisTemplate)) // 持久化，配合 threadId 断点续跑
 *       .build();
 *   ReActAgent agent = ReActAgent.builder().planNotebook(nb).build();
 *   // Agent 自动调用 create_plan / finish_subtask / revise_current_plan / finish_plan
 * }</pre>
 */
public class PlanNotebookDemo {

    /** 子任务状态机：待办 → 进行中 → 完成 / 放弃。 */
    enum Status { TODO, DOING, DONE, ABANDONED }

    /** 子任务记录。 */
    static class Subtask {
        final int id;
        final String title;
        Status status;

        Subtask(int id, String title) {
            this.id = id;
            this.title = title;
            this.status = Status.TODO;
        }

        @Override
        public String toString() {
            return "  [" + status + "] " + title;
        }
    }

    /** 极简计划本：负责增删子任务与状态流转，并打印进度。 */
    static class PlanNotebook {
        final String goal;
        final List<Subtask> subtasks = new ArrayList<>();

        PlanNotebook(String goal) {
            this.goal = goal;
        }

        Subtask add(String title) {
            Subtask t = new Subtask(subtasks.size() + 1, title);
            subtasks.add(t);
            return t;
        }

        /** 开始执行某个子任务（同时仅一个 DOING）。 */
        void start(int id) {
            subtasks.stream().filter(t -> t.id == id).findFirst()
                    .ifPresent(t -> t.status = Status.DOING);
        }

        void complete(int id) {
            subtasks.stream().filter(t -> t.id == id).findFirst()
                    .ifPresent(t -> t.status = Status.DONE);
        }

        /** revise：中途放弃某子任务（如老板说不要了）。 */
        void abandon(int id) {
            subtasks.stream().filter(t -> t.id == id).findFirst()
                    .ifPresent(t -> t.status = Status.ABANDONED);
        }

        /** 打印当前计划进度（注入 Agent 每轮提示词的，正是这段）。 */
        void view() {
            System.out.println("  计划：" + goal);
            subtasks.forEach(System.out::println);
        }
    }

    public static void main(String[] args) {
        Demo.banner("PlanNotebookDemo · 任务规划与状态跟踪（状态机）");
        Demo.note("场景：Agent 接到「生成季度经营分析报告」长任务，先建计划，再逐子任务执行并跟踪状态。");

        PlanNotebook notebook = new PlanNotebook("生成 Q3 经营分析报告");
        Subtask s1 = notebook.add("拉取各业务线销售数据");
        Subtask s2 = notebook.add("计算同比/环比增长");
        Subtask s3 = notebook.add("撰写风险章节（调用风控Agent）");
        Subtask s4 = notebook.add("生成图表");
        Subtask s5 = notebook.add("汇总成 PPT");

        Demo.note("① 已建计划（create_plan），当前进度：");
        notebook.view();

        // 模拟 Agent 顺序领取并执行子任务（finish_subtask 更新状态）
        Demo.note("② Agent 逐子任务执行（每次开始置 DOING，完成置 DONE）：");
        execute(notebook, s1, "已拉取 12 条业务线销售数据");
        execute(notebook, s2, "同比增长 8.2%，环比增长 3.1%");
        execute(notebook, s3, "風险章节完成，提示 2 项预警");

        // 中途 revise：老板说先别做 PPT 了 → 放弃 s5
        Demo.note("③ 执行中老板说「先不要 PPT」→ revise_plan 放弃该子任务（abandon）：");
        notebook.abandon(s5.id);
        execute(notebook, s4, "已生成 4 张核心图表");

        Demo.note("④ 最终计划进度（finish_plan 前的快照）：");
        notebook.view();

        long done = notebook.subtasks.stream().filter(t -> t.status == Status.DONE).count();
        long abandoned = notebook.subtasks.stream().filter(t -> t.status == Status.ABANDONED).count();
        System.out.println("\n  完成 " + done + " 项，放弃 " + abandoned + " 项，报告可交付 ✅");
        Demo.note("价值：进度显式可观测、可恢复（持久化+threadId 续跑）、可干预（needUserConfirm + revise）。"
                + "流程确定用 Graph 子图，流程不确定需规划用 PlanNotebook——两者都解决「复杂任务拆解」但控制权属不同。");
    }

    /** 模拟 Agent 执行一个子任务：置 DOING → 桩工作 → 置 DONE，并打印进度。 */
    private static void execute(PlanNotebook notebook, Subtask task, String workLog) {
        notebook.start(task.id);
        System.out.println("  ▶ 执行 [" + task.title + "] ...");
        Demo.note("    · " + workLog);
        notebook.complete(task.id);
    }
}

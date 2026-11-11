package com.qiutian.agent.ch04_multiagent;

import com.qiutian.agent.common.Demo;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * SandboxDemo：工具调用的「安全沙箱」——在独立线程 + 超时限制内执行不可信代码/命令。
 *
 * <p>知识点（对应 lesson07 安全沙箱）：能自主调工具的 Agent，必须给「执行权」套上边界，否则一个失控循环或
 * 恶意 prompt injection 就能烧钱/拖垮系统。常见风险：权限越界、资源失控（死循环狂调 API）、
 * 数据泄露、级联故障。
 *
 * <p>本 Demo 用纯 Java（{@code ExecutorService} + {@code Future.get(timeout)}）演示沙箱核心：
 * 把不可信任务丢进独立线程执行，超时则 {@code Future.cancel(true)} 中断线程；若任务启动的是子进程，
 * 还要在中断时 {@code destroyForcibly()} 强杀，防止进程泄漏。
 *
 * <p>生产级沙箱是「四件套」：① 工具权限分级（高危走 HITL，权限校验写在工具实现里，绝不依赖模型自觉）；
 * ② 资源限额（maxIters / 超时 / token 预算）；③ 运行时隔离（容器/代码沙箱，绝不在宿主直接 eval 模型生成的代码）；
 * ④ 审计（每步动作留痕）。线程 + 超时只是「资源限额」这一层的最简形态——
 * 真正执行不可信代码应放进容器/独立进程（如 E2B/Modal），线程中断无法终止非协作的本地代码。
 */
public class SandboxDemo {

    public static void main(String[] args) {
        Demo.banner("SandboxDemo · 工具调用安全沙箱（线程 + 超时中断）");

        // 用例 1：良性任务，超时内正常完成
        Demo.note("用例1：良性任务（1s 内返回）放入沙箱，超时设为 3s → 应正常完成");
        String r1 = runSandboxed(() -> "计算结果: 42（在超时内完成）", 3000);
        System.out.println("  结果: " + r1);

        // 用例 2：失控任务（死循环），超时后被中断
        Demo.note("用例2：失控任务（死循环狂算）放入沙箱，超时设为 800ms → 应被超时中断");
        String r2 = runSandboxed(() -> {
            long sum = 0;
            while (!Thread.currentThread().isInterrupted()) { // 协作式检查中断
                sum++;
            }
            return "被中断，部分累加=" + sum;
        }, 800);
        System.out.println("  结果: " + r2);

        // 用例 3：不可信命令（sleep 5s），超时 1s 强杀子进程
        Demo.note("用例3：不可信命令（sleep 5s）放入沙箱，超时设为 1s → 应超时并强杀子进程");
        String r3 = runSandboxed(() -> {
            Process p = new ProcessBuilder("sleep", "5").start();
            try {
                int code = p.waitFor();           // 沙箱中断时会抛 InterruptedException
                return "命令退出码=" + code;
            } catch (InterruptedException ie) {
                p.destroyForcibly();              // 超时：强杀子进程，杜绝进程泄漏
                Thread.currentThread().interrupt();
                return "⛔ 命令超时，已被沙箱强杀";
            }
        }, 1000);
        System.out.println("  结果: " + r3);

        Demo.note("小结：沙箱=给执行权套边界。线程+超时只是「资源限额」层，真正的不可信代码执行应放进"
                + "容器/代码沙箱；高危工具还需『权限分级 + HITL 人工确认 + 审计』多道闸。");
    }

    /**
     * 沙箱执行：在独立单线程中跑任务，超过 timeoutMs 毫秒则中断线程并返回超时标记。
     *
     * @param task       不可信任务（代码或命令）
     * @param timeoutMs  超时上限（毫秒）
     * @return 任务结果或超时/异常说明
     */
    private static String runSandboxed(Callable<String> task, long timeoutMs) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> future = executor.submit(task);
        try {
            // 关键：带超时的阻塞获取，超时即视为任务失控
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);  // 中断工作线程（协作式任务应在循环里检查 isInterrupted）
            return "⛔ 超时中断（>" + timeoutMs + "ms），已阻止资源失控";
        } catch (Exception e) {
            return "❌ 执行异常: " + e.getMessage();
        } finally {
            executor.shutdownNow(); // 释放线程池资源
        }
    }
}

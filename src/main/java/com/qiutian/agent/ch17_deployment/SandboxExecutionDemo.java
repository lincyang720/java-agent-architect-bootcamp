package com.qiutian.agent.ch17_deployment;

import com.qiutian.agent.common.Demo;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 第十七章第3节配套：沙箱执行演示（纯 Java，离线可跑）。
 *
 * <p>把「不可信逻辑」（模型生成的反射调用 / 命令 / 死循环）关进<b>独立线程 + 超时</b>的笼子：
 * 用 {@link ExecutorService} 提交任务，{@link Future#get(long, TimeUnit)} 设超时，
 * 一旦超时立即 {@link Future#cancel(boolean) cancel(true)} 中断线程——即使任务在死循环，
 * 也因线程被中断而退出，杜绝「一个恶意任务拖死宿主进程」。
 *
 * <p>对应 lesson03 的沙箱三原则：① 限制执行时长（超时强杀）；② 限制资源（单线程/限 CPU）；
 * ③ 隔离（独立线程，宿主主流程不受影响）。生产应进一步上 Docker/WASM + 断网 + 资源限额。
 */
public class SandboxExecutionDemo {

    /** 在沙箱里执行一个不可信任务，带超时；超时则中断并返回失败说明。 */
    static String runInSandbox(ExecutorService pool, String label, Callable<String> task, long timeoutSec) {
        Future<String> future = pool.submit(task);
        try {
            String result = future.get(timeoutSec, TimeUnit.SECONDS);
            Demo.note("✅ [" + label + "] 在 " + timeoutSec + "s 内正常完成");
            return result;
        } catch (TimeoutException e) {
            // 超时：立即中断线程，防止死循环继续占用资源
            boolean cancelled = future.cancel(true);
            Demo.note("⏱ [" + label + "] 超过 " + timeoutSec + "s，已中断取消（cancelled=" + cancelled + "）");
            return "TIMEOUT_INTERRUPTED";
        } catch (Exception e) {
            Demo.note("⚠ [" + label + "] 执行抛异常: " + e.getCause().getMessage());
            return "ERROR:" + e.getCause().getMessage();
        }
    }

    public static void main(String[] args) throws Exception {
        Demo.banner("SandboxExecutionDemo：独立线程 + 超时中断的不可信代码沙箱");

        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            // ① 良性任务：通过反射调用一个本地方法（模拟「模型生成的反射调用」）
            Demo.note("场景1：良性不可信代码——用反射调用本地方法（应在超时前完成）");
            Callable<String> reflectTask = () -> {
                Method m = SandboxExecutionDemo.class.getDeclaredMethod("safeCompute", int.class, int.class);
                return (String) m.invoke(null, 6, 7);
            };
            String r1 = runInSandbox(pool, "reflect", reflectTask, 3);
            System.out.println("  反射调用结果: " + r1);

            // ② 良性任务：执行一条本地命令（模拟「模型生成的命令」）
            Demo.note("场景2：良性不可信代码——执行一条命令（应在超时前完成）");
            Callable<String> cmdTask = () -> {
                Process p = new ProcessBuilder("java", "-version").start();
                p.waitFor(2, TimeUnit.SECONDS);
                return "cmd-exit=" + p.exitValue();
            };
            String r2 = runInSandbox(pool, "command", cmdTask, 3);
            System.out.println("  命令执行结果: " + r2);

            // ③ 恶意/失控任务：死循环（模拟被 prompt injection 诱导的无限循环）
            Demo.note("场景3：恶意不可信代码——死循环（应被超时中断，绝不拖死宿主）");
            Callable<String> evilTask = () -> {
                // 注意：真实沙箱里这行会一直跑；这里靠 Thread.interrupted() 让中断能生效
                while (!Thread.currentThread().isInterrupted()) {
                    // 模拟持续占用 CPU 的不可信逻辑
                }
                throw new InterruptedException("沙箱线程被中断，死循环已退出");
            };
            String r3 = runInSandbox(pool, "evil-loop", evilTask, 2);
            System.out.println("  恶意任务最终状态: " + r3);

            Demo.note("要点：宿主主线程全程不被阻塞，恶意任务被限时强杀；"
                    + "这正是「代码执行必须进沙箱、宿主绝不直接 eval」的离线最小证明。");
            Demo.note("生产强化（lesson03）：Docker(--network none --memory 256m --cpus .5 --rm) / "
                    + "E2B 微虚拟机 / WASM；再叠加权限白名单、输入校验、频率限额与审计。");
        } finally {
            pool.shutdownNow();
        }
    }

    /** 一个无害的本地计算方法，供反射调用演示。 */
    @SuppressWarnings("unused")
    private static String safeCompute(int a, int b) {
        return "reflection-sum=" + (a + b);
    }
}

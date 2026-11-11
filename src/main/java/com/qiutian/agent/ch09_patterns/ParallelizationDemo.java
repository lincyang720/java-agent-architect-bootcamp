package com.qiutian.agent.ch09_patterns;

import com.qiutian.agent.common.Demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 第九章第三节：并行化（Parallelization）演示。
 *
 * 核心思想：把彼此独立、无数据依赖的子任务并发丢出去，再聚合成一个结果。
 * 端到端耗时从“串行累加”降为“max(各子任务)”。本 Demo 用 {@code CompletableFuture}
 * + 线程池并发执行多维度“文档审核”，并对比串行 vs 并行的耗时。
 *
 * 全部用纯 Java 桩逻辑（离线可跑）。真实场景把每个子任务换成“调模型做某一维审核”
 * 即可；注意每个并行分支必须 {@code exceptionally} 隔离失败，避免一个挂了全崩。
 */
public class ParallelizationDemo {

    /** 一个独立子任务：维度名 + 处理函数（模拟一次维度审核）。 */
    public record Task(String dimension, Function<String, String> audit) {}

    /** 用桩模拟一次“某维度审核”，并人为加一点耗时以体现并行收益。 */
    public static String fakeAudit(String dimension, String doc) {
        Demo.sleep(300); // 模拟模型调用 IO 等待
        return dimension + ": " + (doc.length() > 5 ? "PASS" : "FAIL") + "(基于内容长度判断)";
    }

    /** 串行执行所有子任务，返回耗时(ms)与结果。 */
    public static Result runSerial(List<Task> tasks, String doc) {
        long t0 = System.nanoTime();
        Map<String, String> results = new java.util.LinkedHashMap<>();
        for (Task t : tasks) {
            results.put(t.dimension(), fakeAudit(t.dimension(), doc));
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return new Result(ms, results);
    }

    /** 并行执行所有子任务（CompletableFuture + 线程池），失败隔离后聚合。 */
    public static Result runParallel(List<Task> tasks, String doc, int poolSize) {
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        long t0 = System.nanoTime();
        // 用线程安全的 Map 收集结果（每个分支写独立 key，互不干扰）
        Map<String, String> safeResults = new ConcurrentHashMap<>();
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (Task t : tasks) {
                CompletableFuture<Void> f = CompletableFuture
                        .supplyAsync(() -> fakeAudit(t.dimension(), doc), pool)
                        // 失败隔离：单个分支异常降级，不影响整体
                        .exceptionally(ex -> "[" + t.dimension() + "] 降级: " + ex.getMessage())
                        .thenAccept(r -> safeResults.put(t.dimension(), r));
                futures.add(f);
            }
            // 等全部完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            pool.shutdown();
            try {
                pool.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        // 保持维度顺序输出
        Map<String, String> ordered = new java.util.LinkedHashMap<>();
        tasks.forEach(t -> ordered.put(t.dimension(), safeResults.get(t.dimension())));
        return new Result(ms, ordered);
    }

    public record Result(long costMs, Map<String, String> results) {}

    public static void main(String[] args) {
        Demo.banner("并行化 Parallelization：多维度文档审核（串行 vs 并行耗时对比）");

        List<Task> tasks = List.of(
                new Task("事实核查", d -> fakeAudit("事实核查", d)),
                new Task("合规风险", d -> fakeAudit("合规风险", d)),
                new Task("语气评估", d -> fakeAudit("语气评估", d)),
                new Task("SEO 评估", d -> fakeAudit("SEO 评估", d)));

        String doc = "本产品可治愈所有癌症，限时免费领取";

        Demo.note("子任务：4 个独立审核维度，每个桩耗时约 300ms");
        Result serial = runSerial(tasks, doc);
        Demo.note("串行总耗时=" + serial.costMs() + "ms（≈ 4×300ms）");

        Result parallel = runParallel(tasks, doc, tasks.size());
        Demo.note("并行总耗时=" + parallel.costMs() + "ms（≈ max(300ms)）");

        Demo.note("==== 并行聚合结果（合并策略：各维度互补拼装）====");
        parallel.results().forEach((k, v) -> System.out.println("  - " + k + ": " + v));

        double speedup = (double) serial.costMs() / Math.max(1, parallel.costMs());
        Demo.note("加速比 ≈ " + String.format("%.1f", speedup) + "x（子任务越多、单任务越慢，收益越大）");

        // 聚合策略扩展：投票并行（多模型交叉验证）示意
        Demo.note("==== 投票并行示意：3 个裁判对「是否违规」投票，过半即拦截 ====");
        List<Boolean> votes = List.of(true, false, true); // 桩：裁判 1、3 判违规
        long yes = votes.stream().filter(b -> b).count();
        String decision = yes >= 2 ? "拦截" : "放行";
        Demo.note("投票=" + votes + " → 决策=" + decision);

        Demo.note("提示：并行调用模型要注意 API 速率限制，可用 Semaphore 控制并发度；"
                + "聚合时若各分支结论冲突，需冲突解决策略（取最高置信或升级人工）。");
    }
}

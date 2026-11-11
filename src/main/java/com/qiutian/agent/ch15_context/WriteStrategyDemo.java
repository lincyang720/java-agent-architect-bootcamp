package com.qiutian.agent.ch15_context;

import com.qiutian.agent.common.Demo;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 第十五章第2节 · Write 策略：把信息写进上下文（记忆写入）。
 *
 * Write 不是「把聊天记录无脑存盘」，而是结构化地写入：
 *   - 带类型（短期/长期/用户画像/任务状态）
 *   - 带优先级（后续 Select 排序用）
 *   - 带标签（后续 Select 检索用）
 *   - 带时间戳与 TTL（防止无限膨胀 → 上下文腐烂）
 *
 * 纯 Java 离线可跑（无模型调用）。
 */
public class WriteStrategyDemo {

    /** 上下文存储：userId -> 该用户的记忆列表（真实项目换成 PgVector / Redis / Milvus）。 */
    private final Map<String, List<MemoryEntry>> store = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        Demo.banner("WriteStrategyDemo · 上下文写入策略（记忆写入）");

        WriteStrategyDemo writer = new WriteStrategyDemo();
        Instant now = Instant.now();

        Demo.note("向上下文存储写入若干结构化记忆条目（带元数据与时间戳）...");
        writer.write("u_amy", "USER_PROFILE",
                "Amy 是后端工程师，偏好短信通知", 8,
                List.of("profile", "notify"), now, Duration.ofDays(365));
        writer.write("u_amy", "TASK_STATE",
                "正在处理 11 月退款工单 #R1024", 6,
                List.of("refund", "task"), now, Duration.ofHours(24));
        writer.write("u_amy", "LONG_TERM",
                "退款时效为审核通过后 24 小时内原路退回", 9,
                List.of("refund", "policy"), now, Duration.ofDays(365));
        writer.write("u_bob", "USER_PROFILE",
                "Bob 是运维，关注可用性指标", 7,
                List.of("profile", "ops"), now, Duration.ofDays(365));

        Demo.note("当前存储中共有 " + writer.count() + " 条记忆，按用户隔离（Isolate 思想已在 lesson02 铺垫）。");
        writer.printAll();

        Demo.note("演示 TTL 清理：把 u_amy 第一条记忆的过期时间改成过去，再触发 purgeExpired()...");
        writer.expireFirst("u_amy");
        int removed = writer.purgeExpired(now.plus(Duration.ofHours(1)));
        Demo.note("清理了 " + removed + " 条过期记忆，剩余 " + writer.count() + " 条。");
        writer.printAll();

        Demo.note("Write 策略要点：结构(类型/优先级/标签) + 时间戳 + TTL + 清理 = 可治理的工作记忆，"
                + "避免无脑堆历史导致上下文腐烂；随后由 Select 策略按需读取（见 SelectStrategyDemo）。");
    }

    /** 写入一条记忆，自动生成 id 并加上 TTL。 */
    void write(String userId, String type, String content, int priority,
               List<String> tags, Instant now, Duration ttl) {
        String id = userId + ":" + type + ":" + now.toEpochMilli()
                + ":" + (int) (Math.random() * 1000);
        MemoryEntry e = new MemoryEntry(id, userId, type, content, priority,
                tags, now, now.plus(ttl));
        store.computeIfAbsent(userId, k -> new ArrayList<>()).add(e);
        System.out.println("  + 写 [" + type + "] p" + priority + " tags=" + tags + " :: " + content);
    }

    int count() {
        return store.values().stream().mapToInt(List::size).sum();
    }

    void printAll() {
        store.forEach((u, list) -> list.forEach(e ->
                System.out.println("    " + u + " | " + e.type() + " | p" + e.priority()
                        + " | " + e.createdAt() + " | " + e.content())));
    }

    /** 把某用户第一条记忆的过期时间改成过去，用于演示清理（record 不可变，故替换为新实例）。 */
    void expireFirst(String userId) {
        List<MemoryEntry> list = store.get(userId);
        if (list != null && !list.isEmpty()) {
            MemoryEntry old = list.get(0);
            list.set(0, new MemoryEntry(old.id(), old.userId(), old.type(),
                    old.content(), old.priority(), old.tags(), old.createdAt(),
                    old.createdAt().minus(Duration.ofHours(1))));
        }
    }

    /** 清理所有过期记忆（防止上下文无限增长）。 */
    int purgeExpired(Instant now) {
        int[] removed = {0};
        store.forEach((u, list) -> {
            int before = list.size();
            list.removeIf(e -> e.isExpired(now));
            removed[0] += (before - list.size());
        });
        return removed[0];
    }
}

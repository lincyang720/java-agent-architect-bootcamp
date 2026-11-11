package com.qiutian.agent.ch15_context;

import java.time.Instant;
import java.util.List;

/**
 * 一条结构化记忆：Write 策略写入的单元。
 *
 * 相比「把聊天记录无脑存盘」，结构化记忆带：
 *   - type：短期/长期/用户画像/任务状态，便于 Select 时按类型路由；
 *   - priority：后续 Select 排序用，重要的信息优先进窗口；
 *   - tags：后续 Select 检索用；
 *   - createdAt / expireAt：时间戳 + TTL，防止无限膨胀导致上下文腐烂。
 */
public record MemoryEntry(
        String id,
        String userId,
        String type,            // SHORT_TERM / LONG_TERM / USER_PROFILE / TASK_STATE
        String content,
        int priority,           // 1~10，越高越重要
        List<String> tags,      // 用于 Select 的关键词/标签匹配
        Instant createdAt,
        Instant expireAt) {

    /** 是否已过期（TTL 判断）。 */
    public boolean isExpired(Instant now) {
        return expireAt.isBefore(now);
    }
}

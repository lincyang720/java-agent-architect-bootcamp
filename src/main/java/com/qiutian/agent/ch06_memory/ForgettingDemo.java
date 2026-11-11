package com.qiutian.agent.ch06_memory;

import com.qiutian.agent.common.Demo;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 第六章第7节 · 遗忘机制（Forgetting）：时间衰减重排序 / 过期剔除。
 *
 * 长期记忆不是永久堆积——旧且次要的记忆应当自然淡出。常见做法是给每条记忆
 * 打时间戳，并用「时间衰减」计算当前有效分：
 *     衰减分 = 基础重要度 × e^(-λ × 经过小时数)
 * 然后按衰减分重排序（重要且新鲜的排前），并把低于阈值的视为「已遗忘」剔除。
 *
 * 纯 Java 逻辑，离线可跑。可与 ReMe（lesson06）式「被回忆则刷新时间戳」结合：
 * 每次被检索命中就重置 createdAt，从而强化常访问的记忆。
 */
public class ForgettingDemo {

    /** 一条带时间戳的长期记忆。 */
    static final class Memory {
        final String text;
        final Instant createdAt;
        final double importance; // 基础重要度 0~1

        Memory(String text, Instant createdAt, double importance) {
            this.text = text;
            this.createdAt = createdAt;
            this.importance = importance;
        }
    }

    /** 遗忘衰减系数：lambda 越大忘得越快（单位：每小时）。 */
    private static final double LAMBDA = 0.05;
    /** 低于该衰减分则视为「已遗忘」并剔除。 */
    private static final double FORGET_THRESHOLD = 0.2;

    /** 时间衰减分数：importance × e^(-lambda × ageHours)。 */
    static double decayScore(Memory m, Instant now) {
        double ageHours = Duration.between(m.createdAt, now).toHours();
        return m.importance * Math.exp(-LAMBDA * ageHours);
    }

    public static void main(String[] args) {
        Demo.banner("ForgettingDemo · 时间衰减遗忘机制");
        Instant now = Instant.now();

        Demo.note("构造若干记忆：跨不同时间创建、不同重要度。");
        List<Memory> mems = new ArrayList<>();
        mems.add(new Memory("用户身份：秋田，后端工程师", now.minus(Duration.ofHours(2)), 0.95));
        mems.add(new Memory("用户喜欢图示讲解", now.minus(Duration.ofDays(3)), 0.6));
        mems.add(new Memory("用户上周退了运动鞋", now.minus(Duration.ofDays(30)), 0.4));
        mems.add(new Memory("用户临时问了天气", now.minus(Duration.ofHours(1)), 0.1));

        Demo.note("按衰减分数重排序（重要且新鲜的排前）。");
        List<Memory> ranked = new ArrayList<>(mems);
        ranked.sort(Comparator.comparingDouble((Memory m) -> decayScore(m, now)).reversed());
        for (Memory m : ranked) {
            System.out.println("  [score=" + String.format("%.4f", decayScore(m, now)) + "] " + m.text);
        }

        Demo.note("剔除衰减分低于 " + FORGET_THRESHOLD + " 的「已遗忘」记忆。");
        List<Memory> retained = new ArrayList<>();
        List<Memory> forgotten = new ArrayList<>();
        for (Memory m : ranked) {
            (decayScore(m, now) < FORGET_THRESHOLD ? forgotten : retained).add(m);
        }
        for (Memory m : retained) {
            System.out.println("  保留：" + m.text);
        }
        for (Memory m : forgotten) {
            System.out.println("  遗忘：" + m.text);
        }

        Demo.note("结论：时间衰减让旧且次要的记忆自然淡出（lesson07），避免记忆无限膨胀。"
                + "可叠加 ReMe 式『被回忆则刷新时间戳』强化记忆。");
    }
}

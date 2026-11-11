package com.qiutian.agent.ch15_context;

import com.qiutian.agent.common.Demo;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 第十五章第2节 · Select 策略：从上下文存储「按需选择」最相关条目。
 *
 * Write 进了成千上万条，但当前任务只需其中几条。Select 用「关键词/标签打分」
 * 选出 Top-K 进窗口（这里用纯 Java 启发式打分，真实项目换成 Embedding + 重排，见第七章 RAG）。
 *
 * 纯 Java 离线可跑。
 */
public class SelectStrategyDemo {

    /** 中文意图 -> 英文标签 的同义词映射（演示用，真实用向量语义匹配）。 */
    private static final Map<String, String> ALIASES = Map.of(
            "退款", "refund", "退货", "refund", "政策", "policy",
            "物流", "logistics", "发票", "invoice");

    public static void main(String[] args) {
        Demo.banner("SelectStrategyDemo · 上下文选择策略（语义检索选择）");

        // 准备一批已写入的记忆（与 Write 策略衔接）
        List<MemoryEntry> store = seedStore("u_amy");
        Demo.note("当前用户 u_amy 的存储中共 " + store.size() + " 条记忆，下面按任务「退款到账时间」做 Select。");

        String task = "我的退款多久能到账";
        int topK = 3;
        double minScore = 0.1;

        Demo.note("对任务做关键词/标签打分，取 Top-" + topK + "（minScore=" + minScore
                + "），并按优先级辅助排序。");
        List<ScoredEntry> selected = select(store, task, topK, minScore);

        System.out.println("── 选中进窗口的上下文 ──");
        selected.forEach(s -> System.out.println(
                "  [" + String.format("%.2f", s.score()) + "] p" + s.entry().priority()
                        + " " + s.entry().type() + " :: " + s.entry().content()));

        Demo.note("若用 Embedding 语义检索（生产推荐）：把 task 与每条 entry 向量化，"
                + "用余弦相似度代替下方关键词打分——详见第七章 RAG / 第十五章 lesson02。");

        // 位置工程：把最关键条目排到首尾（lesson03 对抗「Lost in the Middle」）
        List<MemoryEntry> arranged = arrangeHeadTail(
                selected.stream().map(ScoredEntry::entry).collect(Collectors.toList()));
        Demo.note("位置工程：最高相关放最前、次高放最后，其余居中，降低「中间被忽略」风险（见 lesson03）。");
    }

    /** 关键词/标签启发式打分：任务词与 entry 标签/内容命中则加分，再按 Top-K 截断。 */
    static List<ScoredEntry> select(List<MemoryEntry> store, String task, int topK, double minScore) {
        String raw = task.toLowerCase();
        List<String> tokens = tokenize(task);
        List<ScoredEntry> scored = new ArrayList<>();
        for (MemoryEntry e : store) {
            double score = scoreOf(e, raw, tokens);
            if (score >= minScore) scored.add(new ScoredEntry(e, score));
        }
        // 先按相关性降序，再按优先级降序
        scored.sort(Comparator.<ScoredEntry>comparingDouble(ScoredEntry::score)
                .thenComparingInt(s -> s.entry().priority()).reversed());
        return scored.stream().limit(topK).collect(Collectors.toList());
    }

    static double scoreOf(MemoryEntry e, String taskRaw, List<String> taskTokens) {
        double s = 0.0;
        String searchable = (e.content() + " " + String.join(" ", e.tags())).toLowerCase();
        // 1) 字面命中（内容/标签包含任务中的字或词）
        for (String w : taskTokens) {
            if (w.isBlank()) continue;
            if (searchable.contains(w)) s += 0.5;
        }
        // 2) 同义词映射：中文意图 -> 英文标签
        for (var en : ALIASES.entrySet()) {
            if (taskRaw.contains(en.getKey()) && e.tags().contains(en.getValue())) s += 1.0;
        }
        // 3) 优先级作为轻微偏置，避免同分时随机
        return s + e.priority() * 0.01;
    }

    /** 位置工程：最高相关放最前、次高放最后，其余居中（对抗中间遗忘）。 */
    static List<MemoryEntry> arrangeHeadTail(List<MemoryEntry> items) {
        if (items.size() <= 2) return new ArrayList<>(items);
        List<MemoryEntry> result = new ArrayList<>();
        result.add(items.get(0));
        for (int i = 2; i < items.size(); i++) result.add(items.get(i));
        result.add(items.get(1));
        return result;
    }

    /** 中文逐字 + 英文按空白切分（演示用 tokenizer；生产用分词器/向量）。 */
    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        for (String w : text.toLowerCase().split("[\\s\\p{Punct}]+")) {
            if (!w.isBlank()) tokens.add(w);
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4e00 && c <= 0x9fa5) tokens.add(String.valueOf(c));
        }
        return tokens;
    }

    /** 构造一批示例记忆（与 Write 策略写入的结构一致）。 */
    static List<MemoryEntry> seedStore(String userId) {
        Instant now = Instant.now();
        return List.of(
            new MemoryEntry(userId + ":p:1", userId, "USER_PROFILE",
                "Amy 是后端工程师，偏好短信通知", 8, List.of("profile", "notify"),
                now, now.plus(Duration.ofDays(365))),
            new MemoryEntry(userId + ":p:2", userId, "LONG_TERM",
                "退款时效为审核通过后 24 小时内原路退回", 9, List.of("refund", "policy"),
                now, now.plus(Duration.ofDays(365))),
            new MemoryEntry(userId + ":p:3", userId, "TASK_STATE",
                "正在处理 11 月退款工单 #R1024", 6, List.of("refund", "task"),
                now, now.plus(Duration.ofHours(24))),
            new MemoryEntry(userId + ":p:4", userId, "LONG_TERM",
                "夏季新品满300减50，不参与退款叠加", 4, List.of("promo"),
                now, now.plus(Duration.ofDays(365))),
            new MemoryEntry(userId + ":p:5", userId, "USER_PROFILE",
                "Bob 是运维，关注可用性指标", 7, List.of("ops"),
                now, now.plus(Duration.ofDays(365))));
    }

    /** 打分后的条目（仅本类内部使用）。 */
    record ScoredEntry(MemoryEntry entry, double score) {}
}

package com.qiutian.agent.ch07_rag;

import com.qiutian.agent.common.Demo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ch07-rag · 查询改写 / 上下文压缩 / 引用溯源（纯 Java 逻辑）
 *
 * 对应 lesson07（查询改写、上下文压缩与引用溯源）。
 *
 * 检索拿到 chunk 后还有三道"最后一公里"工序：
 *   1) 查询改写：多轮指代消解 + 同义扩展，让模糊/口语问题变"检索友好"。
 *   2) 上下文压缩：把大段召回压成只相关的片段（这里用"关键词命中截断"的零成本压缩）。
 *   3) 引用溯源：给每条片段打稳定 cite_id，答案可核查、badcase 可定位。
 *
 * 本 Demo 全程纯 Java、无模型调用，可直接离线运行，演示这三道工序的逻辑骨架；
 * 真实 LLM 版改写/HyDE/LLM 压缩写法见各方法内的注释。
 */
public class QueryRewriteDemo {

    /** 引用条目：稳定 id + 来源 + 片段，用于溯源与渲染。 */
    record Citation(String id, String source, int chunkIndex, String snippet) {}

    public static void main(String[] args) {
        Demo.banner("QueryRewriteDemo · 查询改写 + 上下文压缩 + 引用溯源（纯 Java）");

        // ============ 1) 查询改写：多轮指代消解 ============
        Demo.note("第 1 步：查询改写 —— 多轮对话里的指代消解。");
        String history = "用户: 退款一般多久到账？\n助手: 审核通过后 24 小时内原路退回。";
        String current = "那换货呢？"; // 模糊指代，直接检索会丢失"换货时效"意图
        String resolved = resolveCoreference(history, current);
        System.out.println("    原始: " + current);
        System.out.println("    改写(指代消解): " + resolved);

        Demo.note("查询改写 —— 口语/同义扩展（一个短问句扩成多个检索视角）。");
        String colloquial = "钱啥时候能退回来";
        List<String> variants = expandQuery(colloquial);
        variants.forEach(v -> System.out.println("    扩展视角: " + v));

        Demo.note("HyDE(假设性文档嵌入) 思路：先让 LLM 编一段假答案再去检索（离线用纯 Java 生成假想文档演示）。");
        String hyde = pseudoHyDE(colloquialSafe(colloquial));
        System.out.println("    假想文档(用于检索，不入最终 prompt): " + hyde);

        // ============ 2) 上下文压缩：分数/关键词截断 ============
        Demo.note("第 2 步：上下文压缩 —— 只保留与问题相关的句子（零成本关键词截断）。");
        String retrieved = "退款审核需 1 个工作日。夏季新品满 300 减 50。换货需寄回瑕疵品。"
                + "冬装不支持 7 天无理由。退款一般 1-3 个工作日到账。";
        String compressed = compressByKeyword(retrieved, resolved);
        System.out.println("    压缩后上下文: " + compressed);

        // ============ 3) 引用溯源：打 cite_id + 渲染 ============
        Demo.note("第 3 步：引用溯源 —— 给召回片段打稳定 cite_id，记录来源供答案标注。");
        List<Citation> citations = List.of(
                new Citation("kb#c1", "退换货政策2024.pdf", 1, "退款审核需 1 个工作日，审核通过后原路退回。"),
                new Citation("kb#c2", "退换货政策2024.pdf", 2, "退款一般 1-3 个工作日到账。"),
                new Citation("kb#c5", "换货流程.docx", 5, "换货需先将瑕疵商品寄回仓库。")
        );
        citations.forEach(c -> System.out.println("    " + c.id + " | " + c.source + "#p" + c.chunkIndex + " | " + c.snippet));
        String cited = renderCitedAnswer("您的退款预计 1-3 个工作日到账，换货需先寄回瑕疵品。", citations);
        System.out.println("── 带来源的答案 ──");
        System.out.println(cited);

        Demo.note("小结：改写解决『问得不清』、压缩解决『给得太多』、溯源解决『说得不准查不到』，"
                + "三者分别在检索前/后/生成时把关，是企业级 RAG 可信度的基石（合规场景几乎强制要求引用）。");
    }

    // ===================== 1a) 指代消解 =====================

    /** 主题关键词表：从历史里抽取主体（生产可用 NER / LLM 抽取）。 */
    private static final String[] TOPICS = {"退款", "退货", "换货", "到账", "时效", "审核", "寄回", "仓库", "吊牌"};

    /** 从文本中抽取第一个命中的主题词（null 表示没有命中）。 */
    private static String topicOf(String text) {
        for (String t : TOPICS) {
            if (text.contains(t)) {
                return t;
            }
        }
        return null;
    }

    /**
     * 多轮指代消解：若当前问句含指代词（那/这个/它/其/前面…），
     * 从历史中找最近的主题词补齐，并保留时效/时间意图。
     */
    public static String resolveCoreference(String history, String current) {
        String[] refs = {"那", "这个", "它", "其", "前面", "上述", "这"};
        boolean hasRef = false;
        for (String r : refs) {
            if (current.contains(r)) {
                hasRef = true;
                break;
            }
        }
        if (!hasRef) {
            return current; // 无指代，原样返回
        }
        // 优先在"当前问句"里找主题词；找不到时再回退到历史中第一个命中的主题词。
        // （"那换货呢" 应解析为"换货"而非历史里的"退款"，避免指代错配）
        String topic = topicOf(current);
        if (topic == null) {
            topic = topicOf(history);
        }
        if (topic == null) {
            topic = "该问题";
        }
        // 若当前问句关心"时间/多久/什么时候"，补上时效意图
        boolean asksTime = current.matches(".*(多久|什么时候|何时|时间|呢|吗).*");
        return topic + (asksTime ? "的办理时效是多久" : "相关说明");
    }

    // ===================== 1b) 同义扩展 =====================

    /** 口语词 → 检索友好正式词的映射。 */
    private static final Map<String, String> SYNONYM = new LinkedHashMap<>();
    static {
        SYNONYM.put("钱啥时候退", "退款到账时间");
        SYNONYM.put("钱退回来", "退款到账");
        SYNONYM.put("退回来", "退款");
        SYNONYM.put("咋", "怎么");
        SYNONYM.put("咋样", "如何");
        SYNONYM.put("多久", "时效");
    }

    /** 把一个短问句扩写成多个检索视角（覆盖同义/口语-书面/不同侧重点）。 */
    public static List<String> expandQuery(String query) {
        List<String> variants = new ArrayList<>();
        variants.add(query); // 原句
        StringBuilder formal = new StringBuilder();
        String q = query;
        for (Map.Entry<String, String> e : SYNONYM.entrySet()) {
            if (q.contains(e.getKey())) {
                q = q.replace(e.getKey(), e.getValue());
            }
        }
        if (!q.equals(query)) {
            variants.add(q); // 口语→书面
        }
        variants.add("退款 到账 时间 多久"); // 关键词视角
        variants.add("退款 审核 原路退回 时效"); // 流程视角
        return variants;
    }

    // ===================== 1c) HyDE（离线伪实现） =====================

    private static String colloquialSafe(String s) {
        return s;
    }

    /**
     * HyDE 离线伪实现：真实做法是用 LLM 针对问题"编"一段假想答案文档，再拿假文档向量去检索。
     * 这里用纯 Java 拼一段包含问题关键词的假想文档做演示（假答案仅用于检索，绝不进最终 prompt）。
     */
    public static String pseudoHyDE(String query) {
        return "关于「" + query + "」的说明：用户申请退款后，系统会进行审核，审核通过后将款项原路退回，"
                + "通常在一至三个工作日内到账。该流程依据退换货相关政策执行。";
    }

    // ===================== 2) 上下文压缩 =====================

    /**
     * 零成本上下文压缩：按 "。" 断句后，只保留含 query 任一关键词的句子。
     * 对应 lesson07 的"分数截断"思路，这里用关键词命中替代 rerank 分数。
     */
    public static String compressByKeyword(String context, String query) {
        List<String> qToks = HybridSearchDemo.tokenize(query);
        StringBuilder keep = new StringBuilder();
        for (String sent : context.split("[。；]")) {
            if (sent.isBlank()) {
                continue;
            }
            boolean hit = qToks.stream().anyMatch(t -> sent.contains(t) && t.length() >= 2);
            if (hit) {
                keep.append(sent).append("。");
            }
        }
        return keep.length() > 0 ? keep.toString() : context; // 无命中则回退原文
    }

    // ===================== 3) 引用溯源渲染 =====================

    /** 把答案与引用条目拼成"每句事实带 [来源: id]"的可核查格式。 */
    public static String renderCitedAnswer(String answer, List<Citation> citations) {
        if (citations.isEmpty()) {
            return answer;
        }
        StringBuilder sb = new StringBuilder(answer);
        sb.append("\n");
        for (Citation c : citations) {
            sb.append("[来源: ").append(c.id).append(" | ").append(c.source)
                    .append("#p").append(c.chunkIndex).append("] ");
        }
        return sb.toString().trim();
    }

    /*
     * 真实 LLM 版写法（需 OPENAI_API_KEY，key 守卫，示意）：
     *
     * // 查询改写（多轮指代消解）
     * interface Rewriter {
     *     @UserMessage("基于【对话历史】把【当前问题】改写成独立无歧义的检索查询，只输出改写结果：\n"
     *             + "【对话历史】{{history}}\n【当前问题】{{current}}")
     *     String rewrite(String history, String current);
     * }
     * // HyDE：llm.generate("写一段可能回答下面问题的文本：" + query)
     * // LLM 上下文压缩：让小模型抽取与问题相关的句子（见 lesson07 第二节 LlmCompressor）
     * // 强制引用：系统提示要求"每句事实后标注 [来源: cite_id]"，前端再把 [来源] 渲染成可点击链接。
     */
}

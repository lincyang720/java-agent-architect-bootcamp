package com.qiutian.agent.ch15_context;

import com.qiutian.agent.common.Demo;
import com.qiutian.agent.common.Env;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.util.ArrayList;
import java.util.List;

/**
 * 第十五章第2节 · Compress 策略：把冗长上下文压短（摘要压缩）。
 *
 * 演示两条路径：
 *   1) 零成本截断路径（离线必跑）：超长历史先按 token 预算硬截断/分段；
 *   2) LLM 摘要路径（需密钥）：用 OpenAiChatModel 把长上下文摘要成保留关键事实的短文本。
 *      —— 模型调用前有 Demo.ensureKey 守卫，无 OPENAI_API_KEY 也能跑完截断路径。
 *
 * 运行前置（仅摘要步骤需要）：export OPENAI_API_KEY=sk-xxxx
 */
public class CompressStrategyDemo {

    /** 粗略 token 估算：中文约 2 字符/token（仅用于演示阈值判断，非精确分词）。 */
    static int estimateTokens(String text) {
        return Math.max(1, (int) Math.ceil(text.length() / 2.0));
    }

    public static void main(String[] args) {
        Demo.banner("CompressStrategyDemo · 上下文压缩策略（摘要/截断）");

        // 一段超长对话历史（客服长链路场景的简化）
        List<String> history = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            history.add("用户" + i + "：关于订单 O" + i + " 的咨询（含大量闲聊与重复背景）。");
            history.add("客服" + i + "：已记录，正在为您查询订单 O" + i + " 的状态。");
        }
        String full = String.join("\n", history);
        int tokens = estimateTokens(full);
        int budget = 200; // 目标 token 预算（演示用小值，便于触发）
        Demo.note("原始历史 " + history.size() + " 条，估算 token ≈ " + tokens
                + "，目标压缩预算 ≈ " + budget + "。");

        // ===== 路径1：零成本截断（离线必跑）=====
        String truncated = truncateByBudget(full, budget);
        Demo.note("零成本截断路径（离线必跑）：超出预算的部分丢弃/只留尾部，token≈"
                + estimateTokens(truncated) + "。");
        System.out.println("── 截断后 ──\n" + truncated);

        // ===== 路径2：LLM 摘要（需要密钥，key 守卫）=====
        Demo.note("LLM 摘要路径：把整段历史压成「只保留关键进展、不新增事实」的短摘要。需 OPENAI_API_KEY。");
        if (!Demo.ensureKey("OPENAI_API_KEY")) {
            Demo.note("（无 OPENAI_API_KEY，跳过 LLM 摘要；生产中将调用模型生成压缩摘要，"
                    + "且压缩前后需用 Faithfulness 评测确认没压没关键事实，见第十六章。）");
            return;
        }
        OpenAiChatModel model = OpenAiChatModel.builder()
                .apiKey(Env.get("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini")
                .temperature(0.2)
                .build();
        String summary = model.chat(summaryPrompt(full));
        Demo.note("LLM 摘要完成，token≈" + estimateTokens(summary) + "（远低于原始 " + tokens + "）。");
        System.out.println("── LLM 摘要 ──\n" + summary);
        Demo.note("Compress 选型口诀：日常用「截断」零成本打底；对精度敏感的任务才上 LLM 抽取；"
                + "超长历史可用「递归摘要」。压缩可能丢信息，需接第十六章 Faithfulness 评测确认。");
    }

    /** 零成本截断：优先保留尾部（最近上下文），超出预算截断。 */
    static String truncateByBudget(String text, int budget) {
        if (estimateTokens(text) <= budget) return text;
        int keepChars = budget * 2;
        if (text.length() <= keepChars) return text;
        return "…[前面历史已截断]…\n" + text.substring(text.length() - keepChars);
    }

    /** 构造摘要提示词：要求压缩历史并保留待办与关键结论，不新增事实。 */
    static String summaryPrompt(String history) {
        return "请把下面这段对话历史压缩成不超过 120 字的关键进展摘要，"
                + "只保留待办事项与关键结论，不要新增事实：\n" + history;
    }
}

package com.qiutian.agent.ch06_memory;

import com.qiutian.agent.common.Demo;
import com.qiutian.agent.common.Env;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.util.ArrayList;
import java.util.List;

/**
 * 第六章第5节 · 记忆压缩 / 摘要策略（Memory Compression）。
 *
 * 当短期/长期记忆累积的 token 超过阈值，直接进窗口既烧钱又稀释注意力。
 * 经典做法：把早期历史「压缩成一段摘要」，再与近期原文拼接（滑动窗口 + 摘要组合）。
 *
 * 这里演示一个【桩流程】：
 *   1) 用粗略 heuristic 估算 token；
 *   2) 超过阈值则触发压缩——调用 LLM 生成摘要（这一步 key 守卫）；
 *   3) 无 OPENAI_API_KEY 时跳过 LLM 摘要，演示仍可离线跑完。
 *
 * 运行前置（仅摘要步骤需要）：export OPENAI_API_KEY=sk-xxxx
 */
public class MemoryCompressDemo {

    /** token 阈值，超过则触发压缩。演示用小值便于触发。 */
    private static final int TOKEN_THRESHOLD = 50;

    /**
     * 粗略 token 估算：英文约 4 字符/token、中文约 1-2 字符/token。
     * 这里取 chars/2 作为近似（仅用于演示阈值判断，非精确分词）。
     */
    static int estimateTokens(String text) {
        return Math.max(1, (int) Math.ceil(text.length() / 2.0));
    }

    public static void main(String[] args) {
        Demo.banner("MemoryCompressDemo · 记忆压缩/摘要策略");

        List<String> turns = new ArrayList<>();
        turns.add("用户：我叫秋田，是后端工程师。");
        turns.add("AI：好的，记住你是后端工程师。");
        turns.add("用户：我在学 Agent 架构，尤其关注记忆模块。");
        turns.add("AI：记忆模块确实关键，分短期和长期。");
        turns.add("用户：长期记忆要用向量库做语义检索。");
        turns.add("AI：对，Embedding + 相似度排序。");

        StringBuilder buffer = new StringBuilder();
        for (String t : turns) {
            buffer.append(t).append("\n");
        }

        int tokens = estimateTokens(buffer.toString());
        Demo.note("累计记忆 " + turns.size() + " 条，估算 token ≈ " + tokens + "（阈值 " + TOKEN_THRESHOLD + "）。");
        System.out.println("── 压缩前 ──\n" + buffer);

        if (tokens > TOKEN_THRESHOLD) {
            Demo.note("超过阈值，触发记忆压缩：把全部历史摘要成一段，替换原缓冲。");

            // 关键：摘要需真实模型，key 守卫。无 key 时跳过，离线演示可跑完。
            if (Demo.ensureKey("OPENAI_API_KEY")) {
                OpenAiChatModel model = OpenAiChatModel.builder()
                        .apiKey(Env.get("OPENAI_API_KEY"))
                        .modelName("gpt-4o-mini")
                        .temperature(0.2)
                        .build();
                String summary = model.chat(summaryPrompt(buffer.toString()));
                buffer = new StringBuilder("【历史摘要】" + summary + "\n");
                Demo.note("已用 LLM 生成摘要，替换原缓冲。");
            } else {
                Demo.note("（无 OPENAI_API_KEY，跳过 LLM 摘要；生产中将调用模型生成压缩摘要。）");
            }
        }

        int after = estimateTokens(buffer.toString());
        System.out.println("── 压缩后（估算 token ≈ " + after + "）──\n" + buffer);
        Demo.note("结论：压缩把『长历史』变成『短摘要』，显著降低 token 成本（lesson05）。"
                + "可结合滑动窗口：近期原文 + 早期摘要，兼顾新鲜度与脉络。");
    }

    /** 构造摘要提示词：要求压缩历史并保留用户身份与关键诉求。 */
    static String summaryPrompt(String history) {
        return "请把下面这段对话历史压缩成一段不超过 60 字的摘要，保留用户身份与关键诉求：\n" + history;
    }
}

package com.qiutian.agent.ch09_patterns;

import com.qiutian.agent.common.Demo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 第九章第一节：提示链（Prompt Chain）演示。
 *
 * 核心思想：把一个复杂任务拆成若干“单职责”子步骤，用结构化提示词串成流水线，
 * 上一步的输出字符串作为下一步的输入。每一步都可独立观测、重试、替换。
 *
 * 本 Demo 用纯 Java 控制流 + 桩逻辑实现（离线可跑，无需模型 Key）。
 * 真实项目里把每个 {@code Step} 的 {@code Function} 换成“调用 ChatModel 并解析输出”即可；
 * 若想演示真实 LLM 调用，可在 main 开头加 {@code if (!Demo.ensureKey("OPENAI_API_KEY")) return;}。
 */
public class PromptChainDemo {

    /** 链中的一个步骤：名字 + 处理函数（输入上一环节的输出，产出本环节输出）。 */
    public record Step(String name, Function<String, String> action) {}

    private final List<Step> steps = new ArrayList<>();

    /** 向链尾追加一个步骤。 */
    public PromptChainDemo add(String name, Function<String, String> action) {
        steps.add(new Step(name, action));
        return this;
    }

    /**
     * 依次执行所有步骤：上一个 Step 的输出作为下一个 Step 的输入。
     * 同时记录每个步骤的耗时，便于“哪一步慢”的可观测性。
     */
    public String run(String input) {
        String acc = input;
        for (Step step : steps) {
            long t0 = System.nanoTime();
            acc = step.action().apply(acc);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            Demo.note("步骤[" + step.name() + "] 完成，耗时 " + ms + "ms，当前产物长度=" + acc.length());
        }
        return acc;
    }

    public static void main(String[] args) {
        Demo.banner("提示链 Prompt Chain：客服工单「分类→摘要→策略→回复→质检」五段链");

        PromptChainDemo chain = new PromptChainDemo();
        // 第一步：意图分类（桩：含关键词即判定对应类别）
        chain.add("classify", text -> {
            String category = text.contains("退款") ? "退款" : text.contains("物流") ? "物流" : "其他";
            return "【分类=" + category + "】" + text;
        });
        // 第二步：一句话摘要（桩：截断 + 摘要标记）
        chain.add("summarize", text -> {
            String body = text.replaceAll("【.*?】", "");
            String summary = body.length() > 15 ? body.substring(0, 15) + "…" : body;
            return text + " | 【摘要=" + summary + "】";
        });
        // 第三步：策略路由（桩：复杂走知识库，简单走标准话术）
        chain.add("strategy", text -> text + " | 【策略=标准话术】");
        // 第四步：生成回复（桩：套用模板）
        chain.add("reply", text -> text + " => 已为您安排专人处理，请保持电话畅通");
        // 第五步：质检（桩：必须包含兜底入口“人工”）
        chain.add("qa", text -> {
            boolean ok = text.contains("人工") || text.contains("专人");
            return text + " | 【质检=" + (ok ? "PASS" : "FAIL") + "】";
        });

        String ticket = "我上周买的鞋子一直没发货，要求退款";
        Demo.note("原始工单：" + ticket);
        String result = chain.run(ticket);

        Demo.note("==== 最终链产物（前一步输出即后一步输入）====");
        System.out.println(result);

        // 关键约束强调：链内“机器到机器”的步骤优先用 JSON/对象契约，
        // 只有面向人的最后一步才转自然语言（此处直接打印即为示意）。
        Demo.note("提示：真实项目应让中间步骤产出 JSON 契约（如 DiffItem POJO），"
                + "仅最后一步转自然语言，以避免“从文本里抠字段”的解析失败。");
    }
}

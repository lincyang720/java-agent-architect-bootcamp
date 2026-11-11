package com.qiutian.agent.ch10_engineering;

import com.qiutian.agent.common.Demo;

import java.util.List;

/**
 * 第十章第六节：成本治理。纯 Java 离线演示四板斧中的三块可量化部分：
 *  ① Token 估算（按字符粗略估算，真实应接 tokenizer）
 *  ② 模型路由（按任务复杂度选 cheap / expensive 模型）
 *  ③ 提示词缓存命中节省示意 + 预算墙（调用前拦截）。
 */
public class CostGovernanceDemo {

    // 单价（示意，单位：美元 / 千 token；真实以官网为准）
    static final double IN_PRICE = 0.005 / 1000;    // $5 / 1M input
    static final double OUT_PRICE = 0.015 / 1000;   // $15 / 1M output
    static final double CACHE_PRICE = IN_PRICE / 10; // 缓存命中前缀按 1/10 计费

    enum Tier {
        CHEAP("gpt-4o-mini"),
        EXPENSIVE("gpt-4o");
        final String model;

        Tier(String m) {
            this.model = m;
        }
    }

    /** 粗略 token 估算：按字符数 / 2（真实应使用 tiktoken 等 tokenizer）。 */
    static long estimateTokens(String s) {
        return Math.max(1, s.length() / 2);
    }

    /** 按任务复杂度选模型：简单 → 便宜，复杂（长 / 含分析·代码·战略·生成）→ 贵。 */
    static Tier route(String task) {
        boolean hard = task.length() > 40
                || task.contains("分析") || task.contains("代码")
                || task.contains("战略") || task.contains("生成");
        return hard ? Tier.EXPENSIVE : Tier.CHEAP;
    }

    public static void main(String[] args) {
        Demo.banner("第十章·成本治理：Token 估算 + 模型路由 + 提示词缓存");

        Demo.note("① Token 估算（按字符粗略估算，真实应接 tokenizer）");
        String system = "你是电商客服助手，政策：退款3个工作日原路返回，运费由买家承担除非质量问题。";
        String user = "我要退款";
        long inTok = estimateTokens(system) + estimateTokens(user);
        long outTok = estimateTokens("已为您登记退款，3个工作日内原路返回");
        System.out.println("  系统提示+用户输入 token≈" + inTok + "，输出 token≈" + outTok);

        Demo.note("② 模型路由：按任务复杂度选择便宜 / 贵模型（复杂模型约 10 倍单价）");
        List<String> tasks = List.of("查订单状态", "分析竞品并给出战略建议与落地代码");
        for (String t : tasks) {
            Tier tier = route(t);
            double unit = tier == Tier.CHEAP ? IN_PRICE : IN_PRICE * 10;
            double cost = (estimateTokens(system) + estimateTokens(t)) * unit;
            System.out.println("  [" + tier.model + "] " + t + " → 估算成本=$"
                    + String.format("%.6f", cost));
        }

        Demo.note("③ 提示词缓存：稳定系统提示前缀命中后按 1/10 计费，省 ~90% 固定成本");
        int calls = 1000;
        double withoutCache = calls * ((estimateTokens(system) + estimateTokens(user)) * IN_PRICE);
        double withCache = calls * (estimateTokens(system) * CACHE_PRICE
                + estimateTokens(user) * IN_PRICE);
        System.out.println("  1000 次相同前缀请求：");
        System.out.println("    无缓存成本 ≈ $" + String.format("%.4f", withoutCache));
        System.out.println("    有缓存成本 ≈ $" + String.format("%.4f", withCache));
        System.out.println("    节省 ≈ " + String.format("%.1f", (1 - withCache / withoutCache) * 100) + "%");

        Demo.note("④ 预算墙（调用前拦截）：日预算 $0.2，超则降级 / 拦截");
        double budget = 0.2;
        double spent = 0.0;
        int allowed = 0;
        for (int i = 0; i < 5000; i++) {
            double est = (estimateTokens(system) + estimateTokens(user) + outTok)
                    * (IN_PRICE + OUT_PRICE);
            if (spent + est > budget) {
                break;
            }
            spent += est;
            allowed++;
        }
        System.out.println("  日预算 $" + budget + " 下，允许约 " + allowed + " 次请求（超额即拦截 / 降级）");

        Demo.note("结尾：计费 + 缓存 + 路由 + 预算 串成成本治理中间件，业务侧只调 chat()，"
                + "token 指标上报到可观测大盘（第一节）。");
    }
}

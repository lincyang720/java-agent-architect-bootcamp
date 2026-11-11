package com.qiutian.agent.ch10_engineering;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiutian.agent.common.Demo;

import java.util.HashMap;
import java.util.Map;

/**
 * 第十章第二节：测试策略 —— 工具调用的「单元测试式」校验。
 * 核心原则：单测里不真调模型/工具，用 Mock 桩替代，只验证「工具选择 + 参数解析」逻辑。
 * 这里用纯 Java 实现一个极简断言框架（等价于 JUnit 的 assertEquals），离线可跑。
 */
public class ToolCallTestDemo {

    /** 工具契约：名字 + 执行（离线 Mock，固定返回，便于断言参数是否被正确传入）。 */
    interface Tool {
        String name();

        String call(Map<String, Object> args);
    }

    /** 固定返回的 Mock 天气工具 —— 单测里用桩代替真实 HTTP 调用。 */
    static class MockWeatherTool implements Tool {
        public String name() {
            return "weather";
        }

        public String call(Map<String, Object> args) {
            return "天气查询成功 city=" + args.get("city") + " 结果=晴26℃";
        }
    }

    /** 固定返回的 Mock 订单工具。 */
    static class MockOrderTool implements Tool {
        public String name() {
            return "query_order";
        }

        public String call(Map<String, Object> args) {
            return "订单查询成功 orderId=" + args.get("orderId");
        }
    }

    /** 被测对象：Agent 的「工具选择 + 参数解析」逻辑。 */
    static class Planner {
        private final Map<String, Tool> tools = new HashMap<>();

        Planner(Tool... ts) {
            for (Tool t : ts) {
                tools.put(t.name(), t);
            }
        }

        /** 解析「模型决策 JSON」并分派到对应工具（解析层正是单测重点）。 */
        String execute(String modelDecisionJson) {
            try {
                ToolCallDecision d = MAPPER.readValue(modelDecisionJson, ToolCallDecision.class);
                Tool t = tools.get(d.tool());
                if (t == null) {
                    return "【兜底】未知工具: " + d.tool();
                }
                return t.call(d.args() == null ? Map.of() : d.args());
            } catch (Exception e) {
                return "【兜底】参数解析失败: " + e.getMessage();
            }
        }
    }

    /** 模型输出（工具调用决策）的结构化契约。 */
    record ToolCallDecision(String tool, Map<String, Object> args) {
    }

    static final ObjectMapper MAPPER = new ObjectMapper();

    // —— 极简单元测试框架（离线可跑，等价于 JUnit 的 assertTrue / assertEquals）——
    static int passed = 0;
    static int failed = 0;

    static void check(boolean cond, String desc) {
        if (cond) {
            passed++;
            System.out.println("  ✅ " + desc);
        } else {
            failed++;
            System.out.println("  ❌ " + desc);
        }
    }

    public static void main(String[] args) {
        Demo.banner("第十章·测试：工具调用单元测试式校验（Mock 工具）");
        Demo.note("原则：单测里不真调模型/工具，用 Mock 桩验证「工具选择与参数解析」逻辑。");

        Planner planner = new Planner(new MockWeatherTool(), new MockOrderTool());

        Demo.note("[用例1] 模型决策选择 weather 工具，参数 city=北京");
        String r1 = planner.execute("{\"tool\":\"weather\",\"args\":{\"city\":\"北京\"}}");
        check(r1.contains("天气"), "应路由到 weather 工具");
        check(r1.contains("北京"), "参数 city 应正确解析为 北京");

        Demo.note("[用例2] 模型决策选择 query_order 工具，参数 orderId=A100");
        String r2 = planner.execute("{\"tool\":\"query_order\",\"args\":{\"orderId\":\"A100\"}}");
        check(r2.contains("A100"), "参数 orderId 应正确解析为 A100");

        Demo.note("[用例3] 未知工具应走兜底，不抛异常");
        String r3 = planner.execute("{\"tool\":\"delete_all\",\"args\":{}}");
        check(r3.contains("兜底"), "未知工具必须兜底而非崩溃");

        Demo.note("[用例4] 模型输出格式损坏（非 JSON）应安全兜底");
        String r4 = planner.execute("我帮你查一下天气吧");
        check(r4.contains("兜底"), "脏数据应被解析异常处理兜住");

        Demo.note("测试汇总：通过 " + passed + " / 失败 " + failed);
        if (failed > 0) {
            throw new AssertionError("存在失败用例: " + failed);
        }
        Demo.note("全部用例通过。框架写法：生产用 JUnit + Mockito 的 mock(ChatLanguageModel.class) "
                + "替换上面的 Mock 工具桩，断言方式完全一致。");
    }
}

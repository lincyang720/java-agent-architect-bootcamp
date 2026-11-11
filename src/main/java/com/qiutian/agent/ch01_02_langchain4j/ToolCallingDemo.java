package com.qiutian.agent.ch01_02_langchain4j;

import com.qiutian.agent.common.Demo;
import com.qiutian.agent.common.Env;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;

/**
 * 第 1-2 章 · LangChain4j 基础：让 Agent 自动调用「工具（Tool）」。
 *
 * 所谓 Agent 与「纯对话」的最大区别：它能在推理过程中决定调用外部函数。
 * 这里用 {@link Tool} 注解把两个普通 Java 方法声明成工具：
 *   - 计算器（本地确定性计算，无需联网）
 *   - 查天气（返回桩数据，演示「工具即外部能力」的接入范式）
 * 模型会根据用户问题，自行决定是否、以及如何调用这些工具，并把结果融进最终回答。
 *
 * 运行前置：export OPENAI_API_KEY=sk-xxxx
 */
public class ToolCallingDemo {

    /** 工具类：所有带 @Tool 的 public 方法都会被暴露给模型。 */
    static class WeatherAndCalcTools {

        @Tool("计算两个整数的和，用于需要精确算术的场景")
        public int add(@P("第一个加数") int a, @P("第二个加数") int b) {
            Demo.note("  [工具被调用] add(" + a + ", " + b + ")");
            return a + b;
        }

        @Tool("查询某个城市的当前天气（演示桩：返回模拟数据，真实场景可在此调用天气 API）")
        public String getWeather(@P("城市名称，例如 北京") String city) {
            Demo.note("  [工具被调用] getWeather(" + city + ")");
            // 桩实现：真实场景应在此发起 HTTP 请求到天气服务。
            return city + " 当前晴，气温 22℃，湿度 45%。（模拟数据）";
        }
    }

    /** 支持工具调用的 Agent 契约。 */
    interface Assistant {
        String chat(String userMessage);
    }

    public static void main(String[] args) {
        Demo.banner("ToolCallingDemo · @Tool 让 Agent 自动调用工具");

        if (!Demo.ensureKey("OPENAI_API_KEY")) {
            return;
        }

        Demo.note("构建底模型。");
        OpenAiChatModel model = OpenAiChatModel.builder()
                .apiKey(Env.get("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini")
                .temperature(0.0) // 工具调用场景建议低温度，结果更稳定
                .build();

        Demo.note("用 AiServices.builder 绑定工具实例，模型可自主选择调用。");
        Assistant assistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                .tools(new WeatherAndCalcTools())
                .build();

        Demo.note("问题一：需要精确计算 —— 观察模型是否调用 add 工具。");
        String r1 = assistant.chat("帮我算一下 137 加 258 等于多少？");
        System.out.println("回答：" + r1);

        Demo.note("问题二：需要外部信息 —— 观察模型是否调用 getWeather 工具。");
        String r2 = assistant.chat("北京今天天气怎么样？适合出门吗？");
        System.out.println("回答：" + r2);

        Demo.note("小结：工具让你把「模型不知道 / 会变 / 需精确」的能力外包给确定性的 Java 代码。");
    }
}

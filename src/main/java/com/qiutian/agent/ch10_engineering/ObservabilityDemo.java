package com.qiutian.agent.ch10_engineering;

import com.qiutian.agent.common.Demo;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.util.function.Supplier;

/**
 * 第十章第一节：可观测性。
 * 用 Micrometer 的稳定 API（Observation / ObservationRegistry + Timer / Counter）
 * 给「一次 Agent 调用」打点：链路 span（对应 Traces）+ 耗时与次数指标（对应 Metrics）。
 * 全程离线可跑（用模拟 LLM 调用），并在注释中说明如何桥接 OpenTelemetry 导出到 OTLP/Collector。
 *
 * 关键约束：只使用稳定 API（io.micrometer.*），不碰 OTel 内部类。
 */
public class ObservabilityDemo {

    public static void main(String[] args) {
        Demo.banner("第十章·可观测性：Micrometer Observation + Timer/Counter");

        // 观测基座：两个 Registry。生产环境由 Spring Boot 自动装配，
        // micrometer-tracing-bridge-otel 会把 Observation 自动桥接成 OTel span。
        ObservationRegistry obsRegistry = ObservationRegistry.create();
        MeterRegistry meterRegistry = new SimpleMeterRegistry(); // 演示用简单注册表

        Demo.note("用 Observation 包裹一次 Agent 调用：自动成为一条链路 span，并记录耗时/次数指标。");

        // 模拟被观测的「LLM 调用」（离线可跑，无需真实模型 / 无需 API Key）
        Supplier<String> llmCall = () -> {
            try {
                Thread.sleep(120);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return "退款将在3个工作日内原路返回";
        };

        String answer = traceLlmCall(obsRegistry, meterRegistry, "gpt-4o-mini", llmCall);
        System.out.println("  模型回复: " + answer);

        // 再跑几次，观察 Timer/Counter 的累积
        for (int i = 0; i < 3; i++) {
            traceLlmCall(obsRegistry, meterRegistry, "gpt-4o-mini", llmCall);
        }

        Demo.note("已注册的关键指标（Timer=耗时分布, Counter=调用次数）：");
        meterRegistry.forEachMeter(m -> System.out.println("  • " + m.getId().getName()
                + " tags=" + m.getId().getTags()));

        Demo.note("如何接 OpenTelemetry 导出到 OTLP / Collector（框架写法，非本演示运行）：");
        printOtelIntegration();

        Demo.note("结尾：把 token / 模型 / 工具成功率等也按 tag 上报，即可在 Grafana 按维度下钻。");
    }

    /** 包裹一次 LLM 调用：链路 span + 指标。稳定 API，不碰 OTel 内部类。 */
    static String traceLlmCall(ObservationRegistry reg, MeterRegistry meter,
                               String model, Supplier<String> llmCall) {
        // 链路：一条名为 agent.llm.call 的 Observation（会被 Micrometer Tracing 桥接成 OTel span）
        return Observation.createNotStarted("agent.llm.call", reg)
                .lowCardinalityKeyValue("model", model)   // 低基数 tag，便于大盘下钻
                .observe(() -> {
                    Timer.Sample sample = Timer.start(meter);
                    try {
                        String result = llmCall.get();
                        // 指标①：调用次数
                        Counter.builder("agent.llm.calls")
                                .tag("model", model)
                                .register(meter)
                                .increment();
                        return result;
                    } finally {
                        // 指标②：耗时（毫秒）
                        sample.stop(Timer.builder("agent.llm.latency")
                                .tag("model", model)
                                .register(meter));
                    }
                });
    }

    /** 框架标准写法：把 Micrometer 指标 / 链路导出到 OTel Collector（仅注释，不运行）。 */
    static void printOtelIntegration() {
        String yaml = """
            # application.yml —— 由 Micrometer Tracing 桥接 OTel，导出到 Collector
            management:
              tracing:
                sampling:
                  probability: 1.0            # 开发全采样；生产按需下调(如0.1)
              otel:
                exporter:
                  otlp:
                    endpoint: http://otel-collector:4317
                resource:
                  attributes: service.name=ch10-agent
              metrics:
                tags:
                  application: ch10-agent

            # 依赖（pom 已包含 micrometer-tracing-bridge-otel + opentelemetry-exporter-otlp）
            # 另可挂 OTel Java Agent 零侵入拿框架级(HTTP/Kafka/DB)链路：
            #   -javaagent:opentelemetry-javaagent.jar -Dotel.service.name=ch10-agent
            #
            # 真实 LLM 调用只需把 llmCall 换成 ChatClient：
            #   String ans = chatClient.prompt().user(u).system(s).call().content();
            # 业务打点（上面的 traceLlmCall）保持不变即可，无需触碰 OTel 内部类。
            """;
        System.out.println(yaml);
    }
}

# 第十章第一节：可观测性：链路追踪（OpenTelemetry）、日志聚合（ELK）、指标监控（Prometheus+Grafana）

大家好，我是秋天。

很多同学把 Agent 跑通就觉得"大功告成"，结果一上线就傻眼：用户说"回答慢"，你不知道慢在哪个环节；用户说"答错了"，你不知道是哪一步模型抽风；半夜告警响了，你连"哪个 Agent 挂了"都说不清。这节课讲**可观测性（Observability）**——给 Agent 系统装上"监控摄像头"，让每一次调用都可追溯、可量化、可告警。

学完这节课，你能掌握：

1. **可观测性三件套**：Traces（链路追踪）、Logs（日志）、Metrics（指标）分别解决什么问题。
2. **OpenTelemetry + Micrometer 的 Java 接入**：给 Agent 调用打点、串链路、出指标。
3. **ELK 日志聚合**与**Prometheus + Grafana 指标监控**的落地配置。
4. **Agent 专属观测点**：token 用量、模型延迟、工具调用成功率、幻觉率。
5. **企业场景**：高并发客服 Agent 的链路排障、成本与质量大盘。

<callout emoji="💡"><p>招聘映射：JD 里"熟悉 OpenTelemetry / Prometheus / ELK""具备可观测性建设经验"是**高频硬项**。几乎每个中高级 Java 岗都要求"出了问题能定位"，Agent 系统因为多了 LLM 这个不确定环节，可观测性比传统服务更关键。</p></callout>

---

## 一、为什么 Agent 特别需要可观测性

传统服务出问题，大概率是代码 bug 或依赖超时，定位路径清晰。Agent 系统多了三个"黑盒"：

```
传统服务：  请求 ─▶ 业务代码 ─▶ DB     (确定性高)
Agent 系统： 请求 ─▶ 路由 ─▶ LLM调用 ─▶ 工具调用 ─▶ LLM再生成 ─▶ 输出
                    ↑不确定性↑    ↑外部依赖↑      ↑可能幻觉↑

Agent 特有故障：
  1. 某个 prompt 版本导致质量下降（需版本对比）
  2. 某个工具超时拖累整体（需链路定位）
  3. 某模型配额耗尽悄悄降级（需指标告警）
  4. token 暴涨成本失控（需指标监控）
```

没有可观测性，这些全是"玄学排查"。有了它，每一条都能**定位到具体步骤、具体模型、具体 token 数**。

```
可观测性三支柱：
  Traces  ── 一次请求经过了哪些步骤、各耗时多少（排障"慢在哪"）
  Logs    ── 每一步的具体输入输出、异常堆栈（排障"错在哪"）
  Metrics ── 宏观量化：QPS、延迟分布、成功率、token 成本（看趋势、告警）
```

---

## 二、OpenTelemetry 接入（Java Agent 自动埋点）

OpenTelemetry（OTel）是云原生可观测性的标准。最简单的接入方式是用 **OTel Java Agent**（字节码增强，零代码改动）。

```bash
# 启动 Agent 应用时挂载 OTel Java Agent，数据发往 Collector
java -javaagent:opentelemetry-javaagent.jar \
     -Dotel.service.name=ch10-agent \
     -Dotel.exporter.otlp.endpoint=http://otel-collector:4317 \
     -Dotel.traces.exporter=otlp \
     -Dotel.metrics.exporter=otlp \
     -jar your-agent-app.jar
```

```
架构：
  Agent 应用 ──(OTLP协议)──▶ OTel Collector ──▶ Jaeger(Traces)
                                                  Prometheus(Metrics)
                                                  Loki/ES(Logs)
```

`opentelemetry-javaagent.jar` 会自动为 Spring、Kafka、HTTP 客户端等常见组件生成 span。但**LLM 调用、工具调用这些业务 span 需要手动打点**——这就要用到 Micrometer 和 OTel SDK。

<callout emoji="✅"><p>零侵入优先：先用 Java Agent 把框架级链路拿全（HTTP/Kafka/DB），再用 SDK 手动补 LLM/工具的业务 span。两层叠加才是完整链路。</p></callout>

---

## 三、Micrometer + OpenTelemetry 手动打点 LLM 调用

Spring Boot 3 自带 Micrometer，配合 `micrometer-tracing-bridge-otel` 即可把 span 导出到 OTel。下面给"LLM 调用"打一个业务 span 并记录 token 指标。

```java
// ch10-engineering/src/main/java/demo/obs/LlmTracingService.java
package demo.obs;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;

import java.util.concurrent.TimeUnit;

public class LlmTracingService {

    private final ObservationRegistry obsRegistry;
    private final MeterRegistry meterRegistry;

    public LlmTracingService(ObservationRegistry obsRegistry, MeterRegistry meterRegistry) {
        this.obsRegistry = obsRegistry;
        this.meterRegistry = meterRegistry;
    }

    /** 包裹一次 LLM 调用，记录链路 span + 指标 */
    public String callWithTracing(String prompt, java.util.function.Supplier<String> llmCall) {
        // 链路：创建一个名为 llm.call 的 Observation（会自动成为 span）
        return Observation.createNotStarted("llm.call", obsRegistry)
            .observe(() -> {
                long start = System.nanoTime();
                try {
                    String result = llmCall.get();
                    // 指标：记录 token 成本（示意，真实从响应解析）
                    Counter.builder("agent.llm.tokens")
                        .tag("model", "gpt-4o")
                        .register(meterRegistry)
                        .increment(estimateTokens(prompt + result));
                    return result;
                } finally {
                    long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                    Timer.builder("agent.llm.latency")
                        .tag("model", "gpt-4o")
                        .register(meterRegistry)
                        .record(ms, TimeUnit.MILLISECONDS);
                }
            });
    }

    static long estimateTokens(String s) { return s.length() / 2; } // 粗估
}
```

`Observation` 会自动与 Micrometer Tracing 桥接，生成 OTel span 并导出。这样你在 Jaeger 里能看到一条 `llm.call` span，带 `model=gpt-4o` 标签和耗时。

<callout emoji="⚠️"><p>必须给 span/指标打 `tag`（如 model、intent、step）。否则大盘只能看总数，无法按模型/业务域下钻——那是可观测性的半吊子状态。</p></callout>

---

## 四、Spring Boot 配置：Micrometer Tracing → OTel

```xml
<!-- ch10-engineering/pom.xml 关键依赖 -->
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-tracing-bridge-otel</artifactId>
    </dependency>
    <dependency>
        <groupId>io.opentelemetry</groupId>
        <artifactId>opentelemetry-exporter-otlp</artifactId>
    </dependency>
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>
</dependencies>
```

```yaml
# ch10-engineering/src/main/resources/application.yml
management:
  tracing:
    sampling:
      probability: 1.0   # 开发全采样；生产按需降低(如0.1)
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
  metrics:
    tags:
      application: ch10-agent
  otel:
    exporter:
      otlp:
        endpoint: http://otel-collector:4317
    resource:
      attributes: service.name=ch10-agent
```

这样 `/actuator/prometheus` 就会暴露指标，Prometheus 来抓；span 走 OTLP 给 Collector。

---

## 五、给 Agent 工具调用打点（区分成功/失败）

工具调用是 Agent 的"外部副作用"，必须单独观测成功率与耗时。

```java
// ch10-engineering/src/main/java/demo/obs/ToolTracing.java
package demo.obs;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.common.KeyValue;

public class ToolTracing {

    private final ObservationRegistry reg;
    private final MeterRegistry meter;

    public ToolTracing(ObservationRegistry reg, MeterRegistry meter) {
        this.reg = reg; this.meter = meter;
    }

    public String traceTool(String toolName, java.util.function.Supplier<String> action) {
        return Observation.createNotStarted("agent.tool.call", reg)
            .lowCardinalityKeyValue("tool", toolName)  // 低基数标签
            .observe(() -> {
                try {
                    String r = action.get();
                    meter.counter("agent.tool.success", "tool", toolName).increment();
                    return r;
                } catch (Exception e) {
                    meter.counter("agent.tool.failure", "tool", toolName).increment();
                    throw e;
                }
            });
    }
}
```

```
一条完整 Agent 链路（Jaeger 里长这样）：
  http.request
    └─ router.classify
    └─ chain.summarize
    └─ agent.tool.call (tool=queryOrder)   ← 成功
    └─ agent.tool.call (tool=queryPayment) ← 失败(红)
    └─ llm.call (model=gpt-4o)  latency=820ms
```

一眼就能看到"支付工具调用失败"是这次请求出错的元凶。

---

## 六、ELK 日志聚合

链路告诉你"哪步慢/错"，日志告诉你"那步具体发生了什么"。Agent 日志要结构化（JSON），才能被 ELK 检索。

```xml
<!-- logback 输出 JSON 给 Filebeat -->
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>7.4</version>
</dependency>
```

```xml
<!-- logback-spring.xml 片段：JSON 格式 -->
<appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
  <encoder class="net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder">
    <providers>
      <timestamp/>
      <logLevel/>
      <mdc/>            <!-- 把 traceId 放到 mdc，自动随日志输出 -->
      <message/>
      <arguments/>
    </providers>
  </encoder>
</appender>
```

关键是把 **traceId 注入 MDC**，这样日志能和链路关联：

```java
// ch10-engineering/src/main/java/demo/obs/TraceLogger.java
package demo.obs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class TraceLogger {
    private static final Logger log = LoggerFactory.getLogger(TraceLogger.class);

    public static void logStep(String step, String io) {
        // 从 Observation 上下文取 traceId 放进 MDC（示意：真实由 Tracing 自动注入）
        MDC.put("step", step);
        log.info("Agent step executed. io={}", io);
        MDC.remove("step");
    }

    public static void main(String[] args) {
        logStep("classify", "input=退款问题");
    }
}
```

```
数据流：
  Agent 应用(log JSON + traceId) ─▶ Filebeat ─▶ Logstash ─▶ Elasticsearch
                                                              │
                                                          Kibana(检索/看板)
```

在 Kibana 里搜 `traceId:abc123`，就能看到这次请求的所有日志，和 Jaeger 里的链路一一对应。

<callout emoji="💡"><p>黄金组合：traceId 把 Traces 和 Logs 绑在一起。排查时先去 Jaeger 看哪步红，复制 traceId 到 Kibana 看那步日志，根因秒出。</p></callout>

---

## 七、Prometheus + Grafana 指标监控

指标看宏观趋势与告警。先用 Micrometer 注册几类 Agent 关键指标，Prometheus 抓取，Grafana 出图。

```java
// ch10-engineering/src/main/java/demo/obs/AgentMetrics.java
package demo.obs;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;
import java.util.concurrent.TimeUnit;

public class AgentMetrics {

    private final MeterRegistry reg;

    public AgentMetrics(MeterRegistry reg) { this.reg = reg; }

    /** 记录一次 Agent 端到端请求 */
    public void recordRequest(long nanos, boolean success, String intent) {
        Timer.builder("agent.request.duration")
             .tag("intent", intent)
             .tag("success", String.valueOf(success))
             .register(reg)
             .record(nanos, TimeUnit.NANOSECONDS);

        Counter.builder("agent.request.total")
               .tag("intent", intent)
               .register(reg).increment();
    }

    /** 记录 token 成本 */
    public void recordTokens(long tokens, String model) {
        Counter.builder("agent.tokens.total")
               .tag("model", model)
               .register(reg).increment(tokens);
    }
}
```

```yaml
# prometheus.yml 抓取配置
scrape_configs:
  - job_name: 'ch10-agent'
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['ch10-agent:8080']
```

```
Prometheus 抓取 /actuator/prometheus
        │
        ▼
   Grafana 大盘(关键面板):
     - QPS 与 P99 延迟
     - 各模型 token 消耗量(成本趋势)
     - 工具调用成功率
     - 幻觉率/质检 FAIL 率(接第十六章评测)
```

Grafana 告警规则示例（P99 延迟超阈值告警）：

```yaml
# alert-rules.yml
groups:
  - name: agent-alerts
    rules:
      - alert: HighLatencyP99
        expr: histogram_quantile(0.99, agent_request_duration_seconds_bucket) > 3
        for: 5m
        labels: { severity: warning }
        annotations:
          summary: "Agent P99 延迟超过 3s"
```

---

## 八、Agent 专属观测指标清单

传统服务的 QPS/延迟够用吗？不够。Agent 要额外盯这些：

```
指标类别           具体指标                     告警意义
──────────────────────────────────────────────────────────────
质量            质检 FAIL 率、幻觉率          质量劣化(可能 prompt 改崩了)
成本            token/请求、token 总量        成本失控(提示词膨胀)
模型健康        各模型 5xx 率、超时率          某模型配额/服务异常
工具            工具调用成功率、耗时          下游依赖故障
路由            各意图分布、兜底率            意图漂移/分类器失效
```

<callout emoji="✅"><p>重点盯"提示词版本 ↔ 质量指标"的关联。发版新 prompt 后，如果质检 FAIL 率曲线同时抬头，立刻回滚（呼应第十章第三节 Prompt as Code 的版本治理）。这是 Agent 可观测性最值钱的能力。</p></callout>

---

## 九、企业场景：高并发客服 Agent 排障

```
故障现象：客服 Agent 下午 3 点起响应变慢，用户投诉
排查路径：
  1. Grafana 看 P99 延迟曲线 → 3:05 起陡增
  2. Jaeger 抽样慢请求 → 全部卡在 agent.tool.call(tool=queryOrder)
  3. Kibana 搜该 traceId → 日志显示订单服务连接池耗尽
  4. 结论：订单服务故障拖垮 Agent → 触发降级(第四节)返回缓存答案
```

没有可观测性，这一连串要在群里"对线"半小时；有了它，5 分钟定位。

```text
排障动线(记牢):
  慢/错 ─▶ Grafana(看趋势/告警) ─▶ Jaeger(看哪步) ─▶ Kibana(traceId查日志) ─▶ 根因
```

---

## 十、采样与成本：别把可观测性本身搞破产

全量链路追踪在百万 QPS 下存储成本爆炸。生产要采样：

```
采样策略：
  开发/测试：100% 采样（全看）
  生产：    头采样(请求入口决定) 10% ~ 1%
          或 尾部采样(只保错请求/慢请求) 更省且更有用
  关键业务(如支付类)：该意图 100% 采样
```

<callout emoji="⚠️"><p>错误请求务必 100% 保留 span（尾部采样）。你最需要的就是"出错的那 1%"的链路，把省下的配额留给它们。</p></callout>

---

## 十一、与后续章节衔接

```
可观测性(本节) ──▶ 测试(第二节): 评测指标也要进大盘
                  ──▶ 限流熔断(第四节): 熔断状态要上指标/告警
                  ──▶ 成本治理(第六节): token 指标直接驱动预算告警
                  ──▶ 评测闭环(第十六章): 质量指标长期监控
```

---

## 小结

- 可观测性三支柱：Traces（排"慢在哪"）、Logs（查"错在哪"）、Metrics（看趋势/告警）。
- OTel Java Agent 零侵入拿框架链路；业务 span（LLM/工具调用）用 Micrometer `Observation` + OTel bridge 手动打点，务必打 tag。
- traceId 注入 MDC 把日志和链路绑定，Jaeger + Kibana 联动排障。
- Agent 要额外盯质量/成本/模型健康/工具/路由五类指标；新 prompt 发版后盯质量曲线关联。
- 生产用采样控成本，错误请求 100% 保留。

## 延伸阅读 / 引用文档

1. OpenTelemetry 官方文档（Java）：https://opentelemetry.io/docs/languages/java/
2. Micrometer Tracing 文档：https://micrometer.io/docs/tracing
3. Spring Boot Actuator / Observability：https://docs.spring.io/spring-boot/docs/current/reference/htmlsingle/#actuator
4. Prometheus 文档：https://prometheus.io/docs/introduction/overview/
5. Grafana 文档：https://grafana.com/docs/
6. Elastic Stack（ELK）文档：https://www.elastic.co/guide/index.html

# 第九章第六节：事件驱动架构与微服务中的 Agent：基于消息的协作、Agent 作为微服务 AI 门面

大家好，我是秋天。

前面五节讲的都是"一次请求、一个（或一组）Agent 内部怎么跑"。但真实企业系统是分布式的：订单服务、支付服务、客服系统、风控系统各自独立部署、通过消息队列通信。这节课我们把 Agent 放到**事件驱动架构（EDA）和微服务**里，讲两件大事：①多个 Agent 通过消息（Kafka/RabbitMQ）异步协作；②Agent 作为微服务的"AI 门面（AI Facade）"——把领域能力包装成带智能的接口。

学完这节课，你能掌握：

1. **事件驱动 Agent**：Agent 作为消息消费者/生产者，响应领域事件做智能处理。
2. **Agent 作为 AI 门面**：在遗留微服务前包一层 Agent，把"调接口"变成"说人话"。
3. **基于消息的协作模式**：事件通知、命令分发、Saga 式长事务中的 Agent。
4. **Java 落地**：Spring Boot + Spring Kafka 消费事件触发 Agent；Agent 调用下游 MCP/HTTP 服务。
5. **企业场景**：客诉事件触发自动处理链、对账异常事件驱动 Agent 核查、遗留系统 AI 门面化。

<callout emoji="💡"><p>招聘映射：JD 高频"熟悉事件驱动架构""Spring Cloud / Kafka 微服务经验""Agent 与现有系统集成"。把 Agent 稳稳嵌进企业消息体系，是"能上线"和"只能 Demo"的分水岭，也是架构师岗的硬通货。</p></callout>

---

## 一、为什么 Agent 要进事件驱动架构

```
传统请求-响应 Agent：
   用户 ─▶ 调 Agent API ─▶ 同步返回
   问题：耦合重、无法应对"系统自己产生的事件"、难与现有微服务协作

事件驱动 Agent：
   订单服务 ──(OrderCreated事件)──▶ Kafka
                                        │
                                      Agent消费 ─▶ 智能处理 ─▶ 发指令/落库
```

企业里大量价值来自**系统事件**而非用户请求：订单创建、支付失败、库存告警、对账差异。这些事件天然适合交给 Agent 异步、智能地处理，且不影响主链路 RT。

```
单体 Agent                   EDA 中的 Agent
┌──────────┐                ┌────────┐   ┌─────────┐
│ 用户请求 │                │ 领域事件│──▶│ 消息队列 │
└────┬─────┘                └────────┘   └────┬────┘
     ▼                                        ▼
  [Agent]                                   [Agent消费者]
     ▲                                        │
     └──────── 同步返回 ─────────              ├─ 调下游服务
                                              └─ 发新事件
```

---

## 二、模式一：Agent 作为事件消费者

Agent 订阅领域事件，触发智能处理。这是最常见的落地形态。

```java
// ch09-patterns/src/main/java/demo/eda/ComplaintAgentConsumer.java
package demo.eda;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// 注意：这里用 Spring Kafka 注解示意；需引入 spring-kafka 依赖
@Component
public class ComplaintAgentConsumer {

    interface ComplaintHandler {
        @SystemMessage("你是客诉处理Agent，根据客诉事件生成处理建议(含是否升级人工)")
        String handle(@UserMessage String event);
    }

    private final ComplaintHandler handler;

    public ComplaintAgentConsumer() {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY")).modelName("gpt-4o-mini").build();
        this.handler = AiServices.create(ComplaintHandler.class, model);
    }

    @KafkaListener(topics = "complaint-events", groupId = "agent-group")
    public void onComplaint(String eventJson) {
        // 消费客诉事件
        String suggestion = handler.handle(eventJson);
        System.out.println("[Agent] 客诉处理建议: " + suggestion);
        // 真实场景：把建议写回工单系统 / 发新事件
    }
}
```

```
订单系统 ──OrderComplaint事件──▶ Kafka topic
                                    │
                          ┌─────────┴─────────┐
                          ▼                   ▼
                   [客诉Agent消费者]    [风控Agent消费者]  (多消费者并行)
                          │
                    生成处理建议 ─▶ 写工单 / 发 ComplaintResolved 事件
```

<callout emoji="✅"><p>多 Agent 订阅同一事件流、各管一摊，是"事件驱动多智能体"的典型。Kafka 的 consumer group 天然支持水平扩展——客诉洪峰时加 Agent 实例即可。</p></callout>

---

## 三、模式二：Agent 作为生产者（命令/事件发出）

Agent 处理完后，不只是落库，还可以**发出新事件**驱动下游，形成 Agent 编排的 Saga 式流程。

```java
// ch09-patterns/src/main/java/demo/eda/AgentEventProducer.java
package demo.eda;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class AgentEventProducer {

    private final KafkaTemplate<String, String> template;

    public AgentEventProducer(KafkaTemplate<String, String> template) {
        this.template = template;
    }

    /** Agent 决策后发出"退款指令"事件，由支付服务消费执行 */
    public void emitRefundCommand(String orderId, String reason) {
        String payload = String.format(
                "{\"orderId\":\"%s\",\"action\":\"REFUND\",\"reason\":\"%s\"}", orderId, reason);
        template.send("refund-commands", orderId, payload);
        System.out.println("[Agent] 已发出退款指令: " + payload);
    }

    // 配置侧
    static void configNote() {
        // spring:
        //   kafka:
        //     bootstrap-servers: ${KAFKA_BROKERS}
        //     consumer:
        //       group-id: agent-group
    }
}
```

```
Agent 决策 ─▶ 发 RefundCommand 事件 ─▶ 支付服务消费 ─▶ 执行退款 ─▶ 发 Refunded 事件
                                                                   │
                                                            Agent 再消费确认并通知用户
```

这就是**Saga 长事务**里的 Agent：每个步骤都是事件，Agent 是其中的智能决策节点，天然可恢复、可观测（呼应第十七章 Durable Execution）。

---

## 四、Agent 作为微服务的 AI 门面（AI Facade）

很多企业有成熟的遗留微服务（订单、库存、CRM），但接口是技术化的、难用。在它们前面包一层 Agent，把它变成"能听懂人话"的 **AI 门面**。

```
传统调用(难)：
   前端 ─▶ 拼 JSON ─▶ POST /api/orders?status=REFUND&...
   问题：调用方要懂领域模型、字段含义

AI 门面(简单)：
   前端/用户 ─▶ "帮我把订单A100的退款进度查一下" ─▶ [Agent门面]
                                                        │
                                              理解意图+选工具
                                                        │
                                            ┌───────────┼───────────┐
                                            ▼           ▼           ▼
                                      MCP:查订单   MCP:查支付   MCP:查物流
                                            │           │           │
                                            └──── 聚合答案 ──────────┘
                                                        │
                                            返回"人话": 退款已到账，物流已拦截
```

```java
// ch09-patterns/src/main/java/demo/eda/AiFacadeAgent.java
package demo.eda;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;

public class AiFacadeAgent {

    // 三个遗留微服务，封装成工具
    static class OrderTool {
        @Tool("查询订单状态")
        String queryOrder(String id) {
            // 真实: RestTemplate/WebClient 调订单服务
            return "订单" + id + "状态:已退款";
        }
    }
    static class PaymentTool {
        @Tool("查询支付流水")
        String queryPayment(String id) {
            return "支付" + id + "已原路退回";
        }
    }
    static class LogisticsTool {
        @Tool("查询物流")
        String queryLogistics(String id) {
            return "物流" + id + "已拦截";
        }
    }

    interface Facade {
        @SystemMessage("你是订单域 AI 门面，调用工具回答用户关于订单/支付/物流的问题")
        String chat(String userMsg);
    }

    public static void main(String[] args) {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY")).modelName("gpt-4o").build();
        Facade facade = AiServices.create(Facade.class, model,
                s -> s.chatMemory(MessageWindowChatMemory.withMaxMessages(10))
                    .tools(new OrderTool(), new PaymentTool(), new LogisticsTool()));
        System.out.println(facade.chat("订单A100的退款进度怎么样了？"));
    }
}
```

AI 门面的价值：**不改一行遗留代码，就能让老系统具备自然语言交互能力**。对存量系统众多的政企客户尤其香。

<callout emoji="⚠️"><p>AI 门面必须做"工具调用权限控制"——Agent 能查订单但不能改订单、能读不能写，除非显式授权。这是第十章第五节安全与第十二章治理的硬要求。</p></callout>

---

## 五、基于消息的 Agent 协作编排

多个 Agent 不通过"主控"强协调，而是**通过事件流松耦合协作**——这是去中心化多 Agent 的一种实现。

```
        ┌─ Event: 文档入库 ─▶ [抽取Agent] ─▶ Event: 要素已抽
        │                                            │
   Kafka├─ Event: 新工单   ─▶ [分类Agent] ─▶ Event: 已分类
        │                                            │
        └─ Event: 已分类+要素 ─▶ [派单Agent] ─▶ Event: 已派单
```

每个 Agent 只关心自己订阅的事件、只发出自己负责的事件。优点是**高内聚低耦合、可独立扩容、单点故障不影响全局**；缺点是流程整体可观测性变难，需要第十章第一节的链路追踪把事件串起来。

```yaml
# ch09-patterns/src/main/resources/eda-topology.yml
agents:
  extractor:
    consumes: [doc-ingested]
    produces: [doc-extracted]
  classifier:
    consumes: [ticket-created]
    produces: [ticket-classified]
  dispatcher:
    consumes: [ticket-classified, doc-extracted]
    produces: [ticket-dispatched]
message-bus:
  type: kafka
  bootstrap: ${KAFKA_BROKERS}
```

---

## 六、Spring Cloud 生态集成

事件驱动 Agent 天然落在 Spring Cloud 技术栈里：

```
                  ┌────────────┐
  业务事件 ─────▶ │  Spring   │ ──▶ Kafka/RabbitMQ
                  │ Cloud     │
   Agent 作为 ──▶ │ Stream    │ ◀── 消费事件触发 Agent
   消费者/生产者 │ (Binder)  │
                  └─────┬──────┘
                        │
              ┌─────────┼─────────┐
              ▼         ▼         ▼
          Nacos(注册/  Sentinel(限流  Gateway(API
           配置中心)    熔断,见第十章) 门面路由)
```

Spring Cloud Stream + Kafka Binder 让 Agent 的"事件进出"变成几行注解（第二节已示 `@KafkaListener`）。再叠加：

- **Nacos**：Agent 配置（模型名、prompt 版本、阈值）动态下发。
- **Sentinel**：Agent 消费速率限流、模型调用熔断（第十章第四节）。
- **Gateway**：对外暴露 Agent 能力的统一 API 门面。

```yaml
# ch09-patterns/src/main/resources/application.yml
spring:
  cloud:
    stream:
      bindings:
        complaint-in:
          destination: complaint-events
          group: agent-group
        refund-out:
          destination: refund-commands
  kafka:
    bootstrap-servers: ${KAFKA_BROKERS}
```

---

## 七、跨服务长事务：Agent + Saga

涉及"退款 + 补发 + 通知"的多服务操作，用 Saga 模式，Agent 作为决策与补偿节点。

```
Saga 步骤(每步都是事件):
  1. Agent 判定需退款 ─▶ RefundStarted
  2. 支付服务退款 ─▶ RefundDone / RefundFailed
  3. 若 Failed ─▶ Agent 触发补偿(记录待人工/重试)
  4. 库存服务补发 ─▶ RestockDone
  5. 通知服务告知用户 ─▶ Notified
```

Agent 在 Saga 里做"语义决策"（要不要退款、失败怎么补偿），具体执行交给各微服务。这种"Agent 管脑子、微服务管手脚"的分工，是企业落地最稳的姿势。

<callout emoji="💡"><p>关键认知：Agent 不该直接"写数据库改订单"，那会绕过微服务的边界与事务。正确做法是 Agent 发命令事件，由 owner 服务执行。这样权限、审计、一致性都在服务侧收敛。</p></callout>

---

## 八、可靠性与幂等

事件驱动里消息会**重复投递**（at-least-once），Agent 消费者必须幂等，否则重复处理客诉会重复退款。

```java
// ch09-patterns/src/main/java/demo/eda/IdempotentConsumer.java
package demo.eda;

import java.util.concurrent.ConcurrentHashMap;

public class IdempotentConsumer {

    // 已处理事件ID集合(生产用 Redis)
    private final ConcurrentHashMap<String, Boolean> processed = new ConcurrentHashMap<>();

    public void handle(String eventId, String payload, Runnable action) {
        if (processed.putIfAbsent(eventId, Boolean.TRUE) != null) {
            System.out.println("跳过重复事件: " + eventId);
            return; // 已处理，幂等跳过
        }
        action.run(); // 首次处理
    }

    public static void main(String[] args) {
        IdempotentConsumer c = new IdempotentConsumer();
        Runnable act = () -> System.out.println("执行退款动作");
        c.handle("evt-1", "", act); // 执行
        c.handle("evt-1", "", act); // 跳过
    }
}
```

<callout emoji="⚠️"><p>幂等是事件驱动 Agent 的生命线。用事件 ID + 去重表（Redis/数据库唯一约束）保证"同一事件只处理一次"，避免重复副作用。</p></callout>

---

## 九、企业场景串讲

```
场景A 客诉自动处理：
   用户投诉 ─▶ 客诉事件 ─▶ Agent消费者(分类+生成建议)
            ─▶ 低置信/高危 ─▶ 转人工事件
            ─▶ 简单 ─▶ 直接发退款/补发命令事件

场景B 对账异常核查：
   对账Job发现差异 ─▶ ReconDiff事件 ─▶ Agent(调取支付/订单/物流证据)
            ─▶ 生成差异说明 + 是否人为差错判定
            ─▶ 发工单事件给对应业务组

场景C 遗留系统 AI 门面：
   内部客服系统 ─▶ 包一层 Agent ─▶ 坐席用自然语言查多系统
            （不改订单/CRM 一行代码）
```

---

## 十、与全课程的连接

```
本章六节是一张"Agent 架构模式地图"：
  提示链(1)     ─ 固定步骤串行
  路由(2)       ─ 输入分流到分支
  并行化(3)     ─ 独立任务并发
  编排-执行(4)  ─ 动态拆解派发
  评估-优化(5)  ─ 生成-评估迭代
  事件驱动(6)   ─ 跨服务/异步协作  ← 本节课

模式可组合：事件触发 → 路由选链 → 链内并行 → 编排多Worker → 评估-优化自审
这就是一个生产级 Agent 系统的骨架。
```

---

## 十一、事件 Schema 设计与版本演进

多个 Agent 通过消息协作，事件格式必须**有契约**。否则一个 Agent 改了字段名，消费方全部解析失败——分布式系统的经典坑。

```json
{
  "specVersion": "1.1",
  "eventId": "evt-9f3a",
  "eventType": "complaint.created",
  "source": "order-service",
  "timestamp": "2026-11-11T10:00:00Z",
  "traceId": "abc123",
  "data": {
    "orderId": "A100",
    "channel": "app",
    "intent": "REFUND",
    "payload": { "amount": 99.0 }
  }
}
```

```java
// ch09-patterns/src/main/java/demo/eda/EventSchema.java
package demo.eda;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;

public class EventSchema {

    /** 统一事件契约: 所有 Agent 间消息遵循此结构 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentEvent(
            String specVersion,
            String eventId,
            String eventType,
            String source,
            String traceId,
            Instant timestamp,
            Map<String, Object> data) {}

    public static void main(String[] args) throws Exception {
        AgentEvent e = new AgentEvent(
                "1.1", "evt-9f3a", "complaint.created",
                "order-service", "abc123", Instant.now(),
                Map.of("orderId", "A100", "intent", "REFUND"));
        String json = new ObjectMapper().writeValueAsString(e);
        System.out.println("事件 JSON: " + json);
        // 消费方用同一 schema 反序列化, 字段变更可检测
    }
}
```

<callout emoji="✅"><p>事件设计三原则：① `specVersion` 字段必带，便于灰度兼容；② `traceId` 必带，串联链路（第一节）；③ `data` 用 Map 承载业务载荷，核心字段（eventId/type/source）固定。这样加字段不影响老消费方。</p></callout>

---

## 十二、Agent 在 EDA 中的幂等与去重（再强调）

事件总线通常 at-least-once 投递，重复事件必然发生。除第二节的 idempotent 思路外，工程上还有两种去重：

```
去重三法：
   1. 事件ID去重表(Redis SETNX / 数据库唯一约束) — 最常用
   2. 业务幂等键(如 orderId+action) — 即使 eventId 不同也能防重复副作用
   3. 消费位点+事务(消费与落库同一事务) — Kafka 精确一次语义
```

```java
// ch09-patterns/src/main/java/demo/eda/BusinessIdempotency.java
package demo.eda;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class BusinessIdempotency {
    // 用"业务幂等键"去重, 比 eventId 更稳(同业务不同 eventId 也防重)
    private final Set<String> handled = ConcurrentHashMap.newKeySet();

    public boolean tryConsume(String bizKey, Runnable sideEffect) {
        if (!handled.add(bizKey)) {
            System.out.println("业务幂等键已存在, 跳过: " + bizKey);
            return false; // 已处理
        }
        sideEffect.run();
        return true;
    }

    public static void main(String[] args) {
        BusinessIdempotency d = new BusinessIdempotency();
        d.tryConsume("A100-REFUND", () -> System.out.println("执行退款"));
        d.tryConsume("A100-REFUND", () -> System.out.println("重复执行(不应出现)"));
    }
}
```

---

## 小结

- 事件驱动 Agent 两种角色：事件**消费者**（响应领域事件智能处理）与**生产者**（发命令/新事件驱动下游）。
- AI 门面模式：在遗留微服务前包一层 Agent，把技术接口变成自然语言交互，零改造激活老系统。
- 基于消息的多 Agent 协作是去中心化松耦合形态，需链路追踪串联（第十章第一节）。
- 与 Spring Cloud（Stream/Kafka/Nacos/Sentinel/Gateway）天然契合；长事务用 Saga，Agent 管决策、服务管执行。
- 消息 at-least-once 要求消费者**幂等**（事件 ID 去重），防止重复副作用。

## 延伸阅读 / 引用文档

1. Spring Cloud Stream 官方文档（Kafka Binder）：https://docs.spring.io/spring-cloud-stream/docs/current/reference/html/
2. Apache Kafka 官方文档：https://kafka.apache.org/documentation/
3. Spring for Apache Kafka：https://docs.spring.io/spring-kafka/reference/html/
4. Microservices Patterns（Saga）：https://microservices.io/patterns/data/saga.html
5. LangChain4j Tool/AI Services：https://docs.langchain4j.dev
6. Spring AI Alibaba（Nacos/Sentinel 集成）：https://java2ai.com/docs/overview

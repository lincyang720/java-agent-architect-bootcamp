# 第十二章第3节：Agent 行为审计：决策记录、回溯分析、多租户隔离设计

大家好，我是秋天。

前两节我们做了"手"（工具安全）和"嘴"（输出安全）的防护。但安全不只是"防住",还有"查得清"——**一旦 Agent 出了事，你能不能还原它当时怎么想的、调了什么、为什么这么回？** 如果不能，你连复盘都做不了，更别提监管报送了。

这一节我们讲"Agent 行为审计"：决策记录、回溯分析、多租户隔离。这是 Agent 上生产的"可追溯性"底线。

学完这一节，你将掌握：

1. 为什么 Agent 必须"可审计"（监管 + 复盘 + 信任）；
2. 决策记录该记什么（结构化事件日志）；
3. 回溯分析怎么做（用 traceId 串起全链路）；
4. 多租户隔离的设计（数据/上下文/凭据三层隔离）；
5. 招聘里"行为审计 / 多租户隔离"对应的岗位要求。

---

## 一、为什么 Agent 必须可审计

传统系统出错，你能看代码、看日志、看数据库。Agent 出错，决策是"模型当场做的"，不可复现。所以审计不是锦上添花，是生产必需品：

```text
           不可审计 vs 可审计 的后果
 ┌────────────────────┐     ┌────────────────────┐
 │ 不可审计            │     │ 可审计              │
 │ Agent 回错话/调错工具│     │ 每个决策有结构化记录 │
 │ → 不知道为什么       │     │ → 能还原完整链路     │
 │ → 无法向监管说明     │     │ → 能定位根因         │
 │ → 只能下线了事       │     │ → 能持续改进         │
 └────────────────────┘     └────────────────────┘

 监管场景（金融/政务）：
 - 必须能"说明某次决策的依据"（可解释性要求）
 - 必须保留操作日志备查（等保/审计要求）
```

<callout emoji="💡"><p>把审计当成"第一等公民"来设计，而不是事后补日志。Agent 的每次推理、每次工具调用、每次输出，从第一行代码起就该落结构化事件。这和第10章的可观测性是一脉相承的。</p></callout>

---

## 二、决策记录：记什么、怎么记

决策记录的核心是**结构化事件**，不是一堆文本日志。给你一个事件模型：

```java
package com.qiutian.audit;

import java.time.Instant;
import java.util.Map;

/** Agent 行为审计事件：一次决策/动作的最小记录单元 */
public record AuditEvent(
        String traceId,        // 一次会话/任务的全局追踪号
        String agentId,        // 哪个 Agent
        String tenantId,       // 哪个租户（多租户隔离用）
        Instant timestamp,     // 发生时间
        String type,           // 事件类型：LLM_CALL / TOOL_CALL / OUTPUT / ERROR
        String decision,       // 决策摘要（如"调用 query_order"）
        Map<String, Object> detail, // 结构化细节（入参、结果、token 等）
        String userId          // 触发用户
) {
    public static AuditEvent llm(String traceId, String agentId,
                                 String tenantId, String userId,
                                 String promptHash, int tokens) {
        return new AuditEvent(traceId, agentId, tenantId, Instant.now(),
                "LLM_CALL", "模型推理",
                Map.of("promptHash", promptHash, "tokens", tokens), userId);
    }

    public static AuditEvent tool(String traceId, String agentId,
                                  String tenantId, String userId,
                                  String tool, Map<String, Object> args,
                                  Object result) {
        return new AuditEvent(traceId, agentId, tenantId, Instant.now(),
                "TOOL_CALL", "调用工具:" + tool,
                Map.of("tool", tool, "args", args, "result", result), userId);
    }
}
```

### 2.1 落库：用一个审计写入器

```java
package com.qiutian.audit;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 审计写入器：异步落库，不阻塞 Agent 主链路 */
public class AuditSink {

    // 生产用 Kafka / 专用审计库；这里用阻塞队列示意
    private final BlockingQueue<AuditEvent> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(true);

    public AuditSink() {
        Thread t = new Thread(this::drain, "audit-writer");
        t.setDaemon(true);
        t.start();
    }

    public void record(AuditEvent e) {
        queue.offer(e); // 非阻塞入队
    }

    private void drain() {
        while (running.get()) {
            try {
                AuditEvent e = queue.poll(1, TimeUnit.SECONDS);
                if (e != null) {
                    // 真实场景：写入审计库 / 发到 Kafka / 接 OpenTelemetry
                    System.out.println("[AUDIT] " + e);
                }
            } catch (InterruptedException ignored) { }
        }
    }
}
```

<callout emoji="⚠️"><p>审计写入必须**异步、不阻塞主链路**，否则审计反而拖垮 Agent 性能。同时审计数据本身要防篡改（Append-Only + 哈希链/WORM 存储），否则"审计日志"本身不可信，监管不认。</p></callout>

---

## 三、回溯分析：用 traceId 串全链路

单条事件没用，要能"按 traceId 还原一次完整任务"。这就要和普通分布式追踪（OpenTelemetry）打通：

```text
           一次客诉处理的审计链路（traceId=T123）
 ┌────────────────────────────────────────────────────┐
 │ T123 10:01:00 LLM_CALL   编排Agent 收到"查订单1001"  │
 │ T123 10:01:01 TOOL_CALL  调 query_order(1001)        │
 │ T123 10:01:02 TOOL_RESULT {user:张三,status:已发货}   │
 │ T123 10:01:03 LLM_CALL   编排Agent 生成回复草稿       │
 │ T123 10:01:04 OUTPUT     经护栏脱敏后返回用户         │
 │ T123 10:01:04 ERROR(无)                              │
 └────────────────────────────────────────────────────┘
 回溯时输入 T123 → 完整还原"为什么这么回、调了什么"
```

结合 OpenTelemetry 的 span 关联（Java 示例骨架）：

```java
package com.qiutian.audit;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;

/** 把审计事件与 OTel span 关联，实现全链路回溯 */
public class TracedAgent {

    private final Tracer tracer;
    private final AuditSink sink;

    public TracedAgent(Tracer tracer, AuditSink sink) {
        this.tracer = tracer;
        this.sink = sink;
    }

    public String handle(String tenantId, String userId, String input) {
        // 1. 开启一个 span，拿到 traceId
        Span span = tracer.spanBuilder("agent.handle").startSpan();
        String traceId = span.getSpanContext().getTraceId();
        try (var __ = span.makeCurrent()) {
            // 2. 每个决策都带同一个 traceId 落审计
            sink.record(AuditEvent.llm(traceId, "agent-A",
                    tenantId, userId, String.valueOf(input.hashCode()), 0));
            // ... 业务 + 工具调用，每个动作都用同一个 traceId 记录
            return "处理后的结果";
        } finally {
            span.end();
        }
    }
}
```

<callout emoji="✅"><p>把审计事件和 traceId 绑定，是"回溯分析"的关键。运维/安全同学拿到一个投诉单号，输入 traceId 就能看到这个 Agent 当时完整的行为序列——这就是可解释性的工程落地。</p></callout>

---

## 四、多租户隔离：数据 / 上下文 / 凭据 三层

企业 SaaS 里，多个客户（租户）共用一套 Agent 平台。隔离做不好，A 客户能看到 B 客户的数据，这是严重事故。三层隔离：

```text
           多租户隔离三层
 ┌────────────────────────────────────────────────┐
 │ Layer 1 数据隔离：每租户独立 schema / 行级过滤    │
 │   - DB: tenant_id 行级隔离 或 独立 schema        │
 │   - 向量库: 按 tenant 建独立 collection           │
 ├────────────────────────────────────────────────┤
 │ Layer 2 上下文隔离：会话/记忆按 tenant 分桶        │
 │   - 记忆存储 key 带 tenantId 前缀                │
 │   - MCP Server 的 Roots 按租户授权               │
 ├────────────────────────────────────────────────┤
 │ Layer 3 凭据隔离：每租户独立 API Key / 凭证        │
 │   - 调用 LLM 用租户自己的 Key（成本/配额隔离）    │
 │   - 工具访问外部系统用租户级令牌                 │
 └────────────────────────────────────────────────┘
```

### 4.1 数据隔离：行级 tenant_id 强制

```java
package com.qiutian.audit;

import java.util.List;
import java.util.Map;

/** 多租户记忆存储：所有读写强制带 tenantId */
public class TenantMemoryStore {

    // 真实用 Redis/PG；结构为 tenantId -> (sessionId -> messages)
    private final Map<String, Map<String, List<String>>> store = new java.util.concurrent.ConcurrentHashMap<>();

    public void save(String tenantId, String sessionId, String msg) {
        store.computeIfAbsent(tenantId, k -> new java.util.concurrent.ConcurrentHashMap<>())
             .computeIfAbsent(sessionId, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
             .add(msg);
    }

    /** 读取时绝对不能跨租户：参数必须带 tenantId */
    public List<String> load(String tenantId, String sessionId) {
        return store.getOrDefault(tenantId, Map.of())
                    .getOrDefault(sessionId, List.of());
    }
}
```

### 4.2 凭据隔离：租户级 LLM Key

```yaml
# 每个租户用自己的模型 Key（成本隔离 + 配额隔离 + 合规隔离）
tenants:
  tenant-acme:
    openai-api-key: ${ACME_OPENAI_KEY}
    model: gpt-4o-mini
  tenant-globex:
    openai-api-key: ${GLOBEX_OPENAI_KEY}
    model: gpt-4o
```

<callout emoji="⚠️"><p>多租户隔离最常见的漏洞是"忘记在查询里带 tenantId 过滤"，导致越权读取。工程上应该用框架层的 TenantContext（类似 Spring 的 SecurityContext）自动注入，而不是每个 DAO 手动拼条件——手动 100 处，漏 1 处就出事。</p></callout>

---

## 五、审计与合规联动：监管报送

审计数据最终要能服务于监管。给你一个"合规报送包"的结构示例：

```json
{
  "reportId": "R-2026-1101-ACME-001",
  "tenantId": "tenant-acme",
  "agentId": "contract-review",
  "traceId": "T123",
  "events": [
    {"ts": "2026-11-01T10:01:00Z", "type": "LLM_CALL", "detail": "..."},
    {"ts": "2026-11-01T10:01:01Z", "type": "TOOL_CALL", "detail": "..."}
  ],
  "decisionSummary": "Agent 判定合同含 3 处风险条款",
  "humanReviewed": true,
  "reviewer": "合规-张三"
}
```

---

## 六、实战反模式

1. **审计只记结果不记过程**：只存"最终回复"，丢了中间工具调用，无法复盘；
2. **审计同步写库拖垮主链路**：必须异步；
3. **审计日志可被业务代码修改**：必须 Append-Only + 防篡改；
4. **多租户靠"约定"不靠"强制"**：遗漏一处过滤就是数据泄露；
5. **traceId 只在日志里不在审计里**：回溯时日志和审计对不上。

<callout emoji="💡"><p>审计的设计原则就一句：**让每一次 Agent 行为都可被第三方独立还原**。如果你自己都还原不了，监管更还原不了——那就别上线。</p></callout>

---

## 七、招聘映射

```text
【岗位】Agent 平台工程师 / 资深后端（AI 方向）
【要求】
- 有 Agent 行为审计、决策记录、全链路回溯的落地经验；
- 熟悉多租户架构设计（数据/上下文/凭据隔离）；
- 理解可观测性（OpenTelemetry）与审计联动。
【加分】有等保/金融监管报送经验。
```

<callout emoji="✅"><p>"多租户 + 审计 + 可观测"是 Agent 平台工程岗的三件套。能画出三层隔离图 + 审计链路图，这节内容就是你面试的硬通货。</p></callout>

---

## 八、用数据库持久化审计事件（可查询）

前面用队列示意，生产应落库便于回溯查询。给你一个 JPA 实体示例：

```java
package com.qiutian.audit;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "agent_audit_event")
public class AuditEventEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false) private String traceId;
    @Column(nullable = false) private String agentId;
    @Column(nullable = false) private String tenantId;
    @Column(nullable = false) private Instant ts;
    @Column(nullable = false) private String type;  // LLM/TOOL/OUTPUT/ERROR
    @Column(length = 4000)   private String detail; // JSON
    @Column private String userId;

    // getters/setters 省略
}
```

回溯查询（按 traceId 还原全过程）：

```java
List<AuditEventEntity> history = repo.findByTraceIdOrderByTsAsc("T123");
history.forEach(e -> System.out.println(e.ts() + " " + e.type() + " " + e.detail()));
```

<callout emoji="💡"><p>审计表要建在独立库/独立权限，并设 Append-Only（无 UPDATE/DELETE），防止运维或攻击者篡改。等保里"日志不可篡改"是硬指标。</p></callout>

---

## 九、多租户隔离的 TenantContext 强制注入

手动在每个查询里拼 `tenantId` 易漏。用拦截器自动注入上下文：

```java
package com.qiutian.audit;

/** 租户上下文：在请求入口设置，业务/DAO 自动读取 */
public final class TenantContext {
    private static final ThreadLocal<String> TL = new ThreadLocal<>();
    public static void set(String t) { TL.set(t); }
    public static String get() {
        String t = TL.get();
        if (t == null) throw new IllegalStateException("租户上下文缺失");
        return t;
    }
    public static void clear() { TL.remove(); }
}
```

```java
// 在 Agent 请求入口（如 Filter/Interceptor）设置
TenantContext.set(request.getHeader("X-Tenant-Id"));
try {
    return agent.handle(...);
} finally {
    TenantContext.clear();
}
```

```text
   强制注入 vs 手动拼接
 ┌──────────────────────────────────────────────────┐
 │ 手动：每个 DAO 写 tenantId 过滤 → 100 处漏 1 处即 │
 │       数据泄露                                    │
 │ 注入：框架层统一带 tenantId，业务无感且不可绕过    │
 └──────────────────────────────────────────────────┘
```

<callout emoji="⚠️"><p>多租户隔离最怕"靠约定"。必须用框架机制强制，让绕过变得不可能，而不是"希望每个人都记得拼条件"。这是安全设计的基本原则：默认安全（secure by default）。</p></callout>

---

## 十、审计与可观测性（OTel）联动

审计事件要能和无障碍地关联分布式追踪。给你 OTel 配置思路：

```yaml
otel:
  traces:
    exporter: otlp
    endpoint: http://otel-collector:4317
  resource:
    service.name: agent-host
    attributes:
      tenant.id: ${TENANT_ID}   # 把租户也打进 trace，便于按租户分析
```

---

## 十一、合规报送自动化

把"回溯"升级为"自动报送"：定期从审计库聚合，生成监管包：

```java
// 每日生成合规报送包（伪代码）
ComplianceReport report = new ComplianceReport();
report.tenantId = "tenant-acme";
report.events = repo.findByTenantAndDate("tenant-acme", today);
report.humanReviewedCount = repo.countReviewed(today);
reportService.submit(report);  // 推送到监管报送系统
```

---

## 十二、面试追问：审计数据量爆炸怎么办

```text
问：每天几百万条 Agent 审计事件，怎么存怎么查？
答：① 热数据落 OLTP（近期可追溯），冷数据归档到对象存储/数仓；
   ② 用 Kafka 异步写，削峰；③ 关键检索建 traceId/tenantId 索引；
   ④ 原始明细归档，聚合指标进时序库（Prometheus）做趋势监控。
```

---

## 小结

这一节我们给 Agent 补上了"可追溯性"：

- **为什么审计**：决策不可复现，监管/复盘都要求能还原；
- **决策记录**：结构化事件（traceId/agentId/tenantId/type/detail），异步落库、防篡改；
- **回溯分析**：用 traceId 串起全链路，与 OpenTelemetry span 关联；
- **多租户隔离**：数据（行级/独立 schema）、上下文（分桶）、凭据（租户级 Key）三层；
- **联动**：审计数据服务监管报送，形成合规闭环。

到这里，第十二章"安全与治理"三节全部讲完。你已经有了一套"手-嘴-可追溯"的完整安全观。下一章我们从"工程防护"跳到"理论视野"，看 Agentic AI 的演进与未来。

---

## 延伸阅读 / 引用文档

1. OpenTelemetry 官方文档（分布式追踪）：https://opentelemetry.io/docs/
2. Spring Cloud Sleuth / Micrometer Tracing（Java 追踪）：https://spring.io/projects/spring-cloud-sleuth
3. 等保 2.0 相关要求（日志审计）：https://www.djbh.net/
4. NIST AI RMF（治理与可问责）：https://www.nist.gov/itl/ai-risk-management-framework
5. 多租户架构设计模式（Microsoft Azure 文档）：https://learn.microsoft.com/en-us/azure/architecture/
6. OWASP LLM Top 10（安全与治理总览）：https://owasp.org/www-project-top-10-for-large-language-model-applications/

# 第十章第四节：异常处理与降级：超时、限流、熔断、回退策略在 Agent 中的应用

大家好，我是秋天。

LLM 和工具调用都是"外部不确定依赖"——模型会超时、会 429、会抽风返回乱码。如果你的 Agent 不加任何保护，一次模型抖动就能把整条链路拖死，甚至引发雪崩拖垮上游。这节课讲**异常处理与降级**四件套：**超时、限流、熔断、回退**，并给 Spring Cloud Alibaba Sentinel 和 Resilience4j 的真实接法。

学完这节课，你能掌握：

1. **为什么 Agent 更需要降级**：LLM/工具是外部依赖，故障是常态不是异常。
2. **超时（Timeout）**：给每次调用设边界，避免无限等待。
3. **限流（Rate Limit）**：保护模型配额与下游，防止被打爆。
4. **熔断（Circuit Breaker）**：下游持续故障时快速失败，给其恢复时间。
5. **回退（Fallback）**：降级到规则/缓存/人工，保证"至少不崩"。
6. **Sentinel / Resilience4j 落地**：Java 代码 + 注解 + 配置。

<callout emoji="💡"><p>招聘映射：JD 里"熟悉 Sentinel/Resilience4j""理解限流熔断降级""高并发高可用经验"是 Java 岗的**万年高频项**。Agent 系统多了 LLM 这层不确定依赖，熔断限流比传统服务更重要，面试几乎必问。</p></callout>

---

## 一、Agent 的故障面比传统服务大

```
传统服务依赖：DB、缓存、内部 RPC（相对可控）
Agent 额外依赖：
   - LLM 服务(限流/超时/配额耗尽/内容审核拦截)
   - 工具后端(订单/支付/搜索, 各自可能挂)
   - 向量库/RAG 检索(慢查询)
   - 外部 API(天气/地图, SLA 不稳)

任何一个挂 → 如果不保护 → 线程阻塞 → 线程池耗尽 → 整个 Agent 不可用
```

所以 Agent 必须把"外部依赖都当作会挂"来设计。四件套就是护栏：

```
请求 ─▶ [限流] ─(超配额则快速拒绝)─▶ [超时] ─(等太久则中断)─▶ [熔断] ─(下游挂则快速失败)─▶ [回退] ─▶ 降级结果
```

---

## 二、超时：给每次调用加边界

没有超时的 LLM 调用可能卡几十秒，占住线程。Java 里用 `CompletableFuture` 或虚拟线程思路加超时。

```java
// ch10-engineering/src/main/java/demo/resilience/TimeoutDemo.java
package demo.resilience;

import java.util.concurrent.*;

public class TimeoutDemo {

    static String callWithTimeout(Callable<String> task, long ms) {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        Future<String> f = ex.submit(task);
        try {
            return f.get(ms, TimeUnit.MILLISECONDS); // 超时抛 TimeoutException
        } catch (TimeoutException e) {
            f.cancel(true); // 中断任务
            return "[降级] 模型响应超时，返回缓存/规则答案";
        } catch (Exception e) {
            return "[降级] 调用异常: " + e.getMessage();
        } finally {
            ex.shutdownNow();
        }
    }

    public static void main(String[] args) {
        String r = callWithTimeout(() -> {
            Thread.sleep(5000); // 模拟慢模型
            return "正常回复";
        }, 2000); // 2秒超时
        System.out.println(r);
    }
}
```

<callout emoji="✅"><p>超时三原则：① 每次外部调用都要有超时；② 超时值要小于上游容忍时间；③ 超时必须触发**回退**而非抛给用户一个异常页。</p></callout>

---

## 三、限流：保护配额与下游

模型 API 有速率限制（RPM/TPM），工具后端有容量。限流防止"突发流量打爆配额/下游"。

用 Resilience4j 的 `RateLimiter`：

```java
// ch10-engineering/src/main/java/demo/resilience/RateLimitDemo.java
package demo.resilience;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;

import java.time.Duration;
import java.util.function.Supplier;

public class RateLimitDemo {

    public static void main(String[] args) {
        RateLimiterConfig cfg = RateLimiterConfig.custom()
                .limitForPeriod(5)             // 每周期最多 5 次
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ofMillis(100))
                .build();
        RateLimiter rl = RateLimiter.of("llm-calls", cfg);

        Supplier<String> guarded = RateLimiter
                .decorateSupplier(rl, () -> "模型回复");

        for (int i = 0; i < 8; i++) {
            try {
                System.out.println(guarded.get());
            } catch (RequestNotPermitted e) {
                System.out.println("[限流] 拒绝, 走降级: " + e.getMessage());
            }
        }
    }
}
```

```
限流维度：
   全局: 整个 Agent 对模型的 QPS 上限(防烧钱/防封号)
   按意图: 贵操作(生成)限得严, 便宜操作(分类)限得松
   按用户: 单用户限流, 防单个用户打爆
```

---

## 四、熔断：下游挂了就快速失败

熔断器和家里电闸一样：下游持续失败时"跳闸"，后续请求直接快速失败（走回退），给下游恢复时间，半开时试探恢复。

Resilience4j `CircuitBreaker`：

```java
// ch10-engineering/src/main/java/demo/resilience/CircuitBreakerDemo.java
package demo.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerOpenException;

import java.time.Duration;
import java.util.function.Supplier;

public class CircuitBreakerDemo {

    public static void main(String[] args) throws Exception {
        CircuitBreakerConfig cfg = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)                 // 失败率>50% 跳闸
                .waitDurationInOpenState(Duration.ofSeconds(10)) // 10s 后半开试探
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)                    // 统计最近 10 次
                .build();
        CircuitBreaker cb = CircuitBreaker.of("payment-tool", cfg);

        Supplier<String> guarded = CircuitBreaker
                .decorateSupplier(cb, () -> callPaymentTool());

        for (int i = 0; i < 15; i++) {
            try {
                System.out.println(guarded.get());
            } catch (CircuitBreakerOpenException e) {
                System.out.println("[熔断] 已跳闸, 快速失败走回退");
            }
            Thread.sleep(300);
        }
    }

    static String callPaymentTool() {
        throw new RuntimeException("支付工具持续 500"); // 模拟下游故障
    }
}
```

```
熔断器三状态：
   CLOSED(正常) ──失败率超阈值──▶ OPEN(跳闸, 直接失败)
        ▲                          │
        │  半开试探成功            │ 等待 waitDuration
        │                          ▼
        └──────── CLOSED ◀── HALF_OPEN(放少量请求试探)
```

<callout emoji="⚠️"><p>熔断必须配回退：OPEN 状态直接抛异常没意义，要 catch 后返回降级答案或转人工。否则"熔断"只是把"慢失败"变成"快失败"，用户体验没改善。</p></callout>

---

## 五、回退策略：降级也要体面

回退是最后一道防线。常见回退层级：

```
回退优先级(从好到差)：
  1. 缓存答案(近期相似请求的结果)
  2. 规则话术(模板化的标准回复)
  3. 静态兜底("系统繁忙，请稍后再试/转人工")
  4. 转人工(最贵但最稳)
```

```java
// ch10-engineering/src/main/java/demo/resilience/FallbackDemo.java
package demo.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

import java.time.Duration;
import java.util.function.Supplier;

public class FallbackDemo {

    static String primary() { throw new RuntimeException("主路径挂了"); }
    static String fallback() { return "【降级】当前咨询量大，已为您记录，客服稍后联系"; }

    public static void main(String[] args) {
        CircuitBreakerConfig cfg = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .build();
        CircuitBreaker cb = CircuitBreaker.of("cs-agent", cfg);

        Supplier<String> chain = CircuitBreaker
                .decorateSupplier(cb, FallbackDemo::primary);

        // 用 andThen 思路：失败就走 fallback
        String result;
        try {
            result = chain.get();
        } catch (Exception e) {
            result = fallback(); // 体面降级
        }
        System.out.println(result);
    }
}
```

```
降级设计要点：
   - 降级答案要明确告知"这是兜底"(避免用户以为已处理)
   - 降级事件必须上报监控(第一节), 否则降级偷偷发生你不知道
   - 关键业务(支付)降级要转人工, 不能静默返回"成功"
```

---

## 六、Sentinel：Spring Cloud Alibaba 流控熔断

Sentinel 是阿里开源的流量控制组件，和 Spring Cloud Alibaba 深度集成，适合做"接口级"限流熔断。

```xml
<!-- pom.xml -->
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>
</dependency>
```

```java
// ch10-engineering/src/main/java/demo/resilience/SentinelAgent.java
package demo.resilience;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SentinelAgent {

    // value=资源名, blockHandler=限流/熔断时的降级方法
    @SentinelResource(value = "agentChat", blockHandler = "chatBlock")
    @GetMapping("/agent/chat")
    public String chat(String msg) {
        // 正常调用模型
        return "Agent 回复: " + msg;
    }

    // 降级方法签名必须一致 + 末尾加 BlockException
    public String chatBlock(String msg, BlockException ex) {
        return "【降级】当前请求过多或被熔断，请稍后或联系人工";
    }
}
```

```yaml
# application.yml 接入 Sentinel 控制台(可选, 用于可视化规则)
spring:
  cloud:
    sentinel:
      transport:
        dashboard: ${SENTINEL_DASHBOARD:localhost:8080}
      eager: true
```

```
Sentinel 控制台可动态配置：
   - 流控规则: /agent/chat QPS > 100 则拒绝
   - 熔断规则: 慢调用比例 > 50% 或异常比例 > 50% 则熔断 10s
   - 热点参数: 按 user_id 限流, 防单用户刷
```

<callout emoji="✅"><p>选型建议：接口/网关级流控用 Sentinel（和 Spring Cloud 生态无缝）；方法/调用级精细熔断用 Resilience4j（注解 + 编程式都灵）。两者也可共存：Sentinel 守入口，Resilience4j 守内部工具调用。</p></callout>

---

## 七、组合防护：一个生产级 Agent 调用模板

把四件套串起来，形成标准防护模板：

```java
// ch10-engineering/src/main/java/demo/resilience/GuardedAgentCall.java
package demo.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Supplier;

public class GuardedAgentCall {

    // 超时 + 限流 + 熔断 组合装饰一个 LLM 调用
    static <T> Supplier<T> guard(Supplier<T> call, RateLimiter rl,
                                 CircuitBreaker cb, long timeoutMs) {
        Supplier<T> withRl = RateLimiter.decorateSupplier(rl, call);
        Supplier<T> withCb = CircuitBreaker.decorateSupplier(cb, withRl);
        // 超时用 TimeLimiter + 包装成 future
        return () -> {
            ExecutorService ex = Executors.newSingleThreadExecutor();
            Future<T> f = ex.submit(withCb::get);
            try {
                return f.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                f.cancel(true);
                throw new RuntimeException("超时降级", e);
            } catch (Exception e) {
                throw new RuntimeException("调用失败", e);
            } finally {
                ex.shutdownNow();
            }
        };
    }

    public static void main(String[] args) {
        RateLimiter rl = RateLimiter.of("llm", RateLimiterConfig.custom()
                .limitForPeriod(5)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ofMillis(100))
                .build());
        CircuitBreaker cb = CircuitBreaker.ofDefaults("llm-cb");
        Supplier<String> g = guard(() -> "模型回复", rl, cb, 2000);
        try {
            System.out.println(g.get());
        } catch (Exception e) {
            System.out.println("[降级] " + e.getMessage());
        }
    }
}
```

```
完整防护链路：
   请求 → 限流(超配额即拒) → 超时(等太久中断)
        → 熔断(下游挂则快失败) → 回退(体面降级/转人工)
   每个环节都上报监控(第一节), 熔断/降级事件进告警
```

---

## 八、企业场景：高并发客服与对账

```
场景A 高并发客服(大促):
   流量洪峰 → Sentinel 入口限流(QPS 1000) → 超出转排队/缓存答案
   某模型配额耗尽 → 熔断 → 回退到规则话术 → 监控告警 → 配额恢复后自动半开恢复

场景B 对账 Agent:
   调用支付工具偶发 5xx → 熔断(失败率50%跳闸) → 10s 后半开试探
   → 恢复则继续, 否则转人工核查(关键业务不能静默降级)
```

<callout emoji="💡"><p>关键业务降级要"保守"：支付、退款这类不能静默返回"成功"的，降级必须转人工并明确告知。非关键业务（如"猜你喜欢"）可以大胆返回缓存/默认。</p></callout>

---

## 九、与全课程的连接

```
异常处理与降级 衔接：
   ── 可观测性(第一节): 熔断/降级事件必须上报监控与告警
   ── 测试(第二节): 要测"下游挂时是否走回退"(混沌/故障注入)
   ── 事件驱动(第九章第六节): 降级可发"降级事件"通知运维
   ── 成本治理(第六节): 限流直接保护 token 预算不被突发打爆
```

---

## 小结

- Agent 故障面更大（LLM/工具/向量库都是不确定外部依赖），必须四件套防护：超时、限流、熔断、回退。
- 超时给每次调用加边界并必触发回退；限流保护配额与下游（Resilience4j RateLimiter / Sentinel）；熔断下游持续故障时快速失败（CircuitBreaker 三状态）。
- 回退分四级：缓存→规则→静态兜底→转人工，关键业务降级必须转人工且明确告知。
- Sentinel 守接口/网关级流控，Resilience4j 守方法级精细熔断，可共存。
- 四件套组合成标准防护模板，所有降级/熔断事件必须进监控告警。

## 延伸阅读 / 引用文档

1. Resilience4j 官方文档：https://resilience4j.readme.io/docs
2. Sentinel 官方文档（Spring Cloud Alibaba）：https://sentinelguard.io/zh-cn/docs/introduction.html
3. Spring Cloud Alibaba Sentinel：https://sca.aliyun.com/zh-cn/docs/2023.0.0.0/introduction
4. Microsoft 熔断模式（Circuit Breaker）：https://learn.microsoft.com/en-us/azure/architecture/patterns/circuit-breaker
5. Resilience4j TimeLimiter：https://resilience4j.readme.io/docs/timelimiter
6. Spring 重试与容错：https://docs.spring.io/spring-framework/reference/integration/remoting.html

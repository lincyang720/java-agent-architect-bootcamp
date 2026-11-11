package com.qiutian.agent.ch10_engineering;

import com.qiutian.agent.common.Demo;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 第十章第四节：异常处理与降级 —— Resilience4j 编程式熔断（不使用任何注解）。
 * 用 CircuitBreaker 装饰「调用外部工具」的动作：连续失败达阈值后跳闸（OPEN），
 * 等待一段时间后进入半开（HALF_OPEN）探测，成功则恢复（CLOSED）。
 * 全程离线可跑，并打印熔断状态变化（对应可观测性：熔断事件要上指标 / 告警）。
 */
public class ResilienceDemo {

    public static void main(String[] args) throws InterruptedException {
        Demo.banner("第十章·异常降级：Resilience4j 编程式熔断（无注解）");

        // 编程式配置熔断器：失败率阈值 + 半开探测，不依赖任何注解
        CircuitBreakerConfig cfg = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)                 // 失败率 > 50% 跳闸
                .minimumNumberOfCalls(4)                  // 至少统计 4 次才开始判阈值
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)                     // 统计最近 4 次
                .waitDurationInOpenState(Duration.ofMillis(1500)) // 跳闸 1.5s 后半开试探
                .permittedNumberOfCallsInHalfOpenState(3) // 半开放 3 个探测请求
                .build();

        CircuitBreaker cb = CircuitBreaker.of("payment-tool", cfg);

        // 监听状态切换并打印（生产应把熔断事件上报监控 / 告警，呼应可观测性一节）
        cb.getEventPublisher().onStateTransition(e ->
                System.out.println("  🔁 熔断状态切换: " + e.getStateTransition()));

        // 模拟一个「先持续失败、随后自愈」的外部工具
        // 注意：OPEN 期间调用被熔断直接拒绝，不会真正执行本 lambda，
        // 因此 attempts 只统计实际调用次数；设定「实际调用 ≤4 次后恢复」便于半开探测成功。
        AtomicInteger attempts = new AtomicInteger(0);
        Supplier<String> unstableTool = () -> {
            int n = attempts.incrementAndGet();
            if (n <= 4) {
                throw new RuntimeException("支付工具第" + n + "次调用 500");
            }
            return "支付工具恢复正常, 第" + n + "次调用成功";
        };

        // 用 CircuitBreaker 装饰这个工具调用（编程式，无注解）
        Supplier<String> guarded = CircuitBreaker.decorateSupplier(cb, unstableTool);

        Demo.note("连续调用外部工具，观察 熔断→降级→半开探测→恢复 全过程：");
        for (int i = 0; i < 16; i++) {
            try {
                String r = guarded.get();
                System.out.println("  第" + (i + 1) + "次 ✅ " + r);
            } catch (CallNotPermittedException open) {
                // 熔断打开：快速失败，走降级（不把裸异常抛给用户）
                System.out.println("  第" + (i + 1) + "次 ⛔ [熔断] 已跳闸，走降级: " + fallback());
            } catch (Exception ex) {
                // 还没到熔断阈值前的真实失败，同样走降级
                System.out.println("  第" + (i + 1) + "次 ❌ [失败] " + ex.getMessage()
                        + " → 降级: " + fallback());
            }
            Thread.sleep(300);
        }

        Demo.note("结尾：外部工具自愈后，熔断器经半开探测自动恢复 CLOSED。");
        System.out.println("  最终熔断状态: " + cb.getState());
    }

    /** 降级答案：明确告知「这是兜底」，并建议转人工 / 重试（关键业务不可静默返回成功）。 */
    static String fallback() {
        return "【降级】支付服务暂不可用，已记录工单，稍后由人工 / 重试处理";
    }
}

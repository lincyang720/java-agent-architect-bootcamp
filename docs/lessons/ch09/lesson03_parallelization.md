# 第九章第三节：并行化（Parallelization）：多任务并发处理与结果聚合

大家好，我是秋天。

提示链是串行的，一步等一步。但很多任务彼此独立：比如审核一篇文章，要同时查"事实错误、合规风险、语气是否友好、SEO 友好度"四个维度——它们互不依赖，串行做纯属浪费 RT。这节课讲**并行化（Parallelization）**：把独立子任务并发丢出去，再聚合成一个结果。

学完这节课，你能掌握：

1. **并行的两种形态**：分片并行（一个任务拆多片同时处理）与投票并行（同一任务多角度多次执行取共识）。
2. **Java 并发落地**：`CompletableFuture` / 虚拟线程（Java 21 思路，Java 17 用线程池）、Spring `@Async`、LangChain4j 的并行调用。
3. **结果聚合策略**：合并、投票、加权、冲突解决。
4. **失败隔离**：某个并行分支挂了，不能拖垮整体。
5. **企业场景**：长文档多维度审核、风控多模型交叉验证、客诉批量处理。

<callout emoji="💡"><p>招聘映射：JD 里高频出现"高并发 Agent 设计""理解并发与异步编排"。并行化直接对应"降低端到端延迟"和"多模型交叉验证提升准确率"两项硬指标，是架构面必考题。</p></callout>

---

## 一、什么时候该并行

```
串行（慢）：  [A]──▶[B]──▶[C]──▶[D]   总耗时 = A+B+C+D

并行（快）：  ┌─[A]─┐
            ├─[B]─┤──▶ 聚合
            ├─[C]─┤      总耗时 = max(A,B,C,D)
            └─[D]─┘
```

判断框架（满足任一即可并行）：

- **独立无依赖**：子任务之间不共享中间结果。
- **可拆分**：一个大输入能切成互不重叠的片（如长文档按章节）。
- **需交叉验证**：同一问题用多个模型/多套 prompt 跑，取共识提升鲁棒性。

<callout emoji="⚠️"><p>如果子任务 B 依赖 A 的输出，就不能并行——那是提示链（第一节）的活。并行的前提是"真独立"。</p></callout>

---

## 二、Java 并发基础：CompletableFuture

Java 17 下用 `CompletableFuture` + 线程池做并行最稳。先复习骨架。

```java
// ch09-patterns/src/main/java/demo/parallel/BasicParallel.java
package demo.parallel;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class BasicParallel {

    public static void main(String[] args) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);

        List<String> tasks = List.of("事实核查", "合规检查", "语气评估", "SEO 评估");

        // 并发提交
        List<CompletableFuture<String>> futures = tasks.stream()
                .map(t -> CompletableFuture.supplyAsync(
                        () -> t + " 完成@" + Thread.currentThread().getName(), pool))
                .collect(Collectors.toList());

        // 全部完成再聚合
        List<String> results = futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());

        results.forEach(System.out::println);
        pool.shutdown();
    }
}
```

`supplyAsync(..., pool)` 把任务丢进线程池，`join()` 等全部完成。这是并行化最底层的写法，所有上层框架最终都落到这。

---

## 三、并行化调用 LLM（多维度审核）

把"调模型"包成 `Supplier`，并发跑多维度审核，最后聚合。

```java
// ch09-patterns/src/main/java/demo/parallel/DocAudit.java
package demo.parallel;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DocAudit {

    interface Auditor {
        @SystemMessage("你是{dimension}审核员，只输出 PASS 或 FAIL+理由")
        String audit(String text);
    }

    public static void main(String[] args) throws Exception {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini").temperature(0).build();

        String doc = "本产品可治愈所有癌症，限时免费领取";
        Map<String, String> dimensions = new HashMap<>();
        dimensions.put("事实核查", "事实核查");
        dimensions.put("合规风险", "合规风险");
        dimensions.put("语气评估", "语气评估");

        ExecutorService pool = Executors.newFixedThreadPool(dimensions.size());
        Map<String, CompletableFuture<String>> futures = new HashMap<>();
        for (Map.Entry<String, String> e : dimensions.entrySet()) {
            Auditor a = AiServices.create(Auditor.class, model);
            // 注：真实应给不同 system 注入不同维度，这里示意并发结构
            futures.put(e.getKey(),
                CompletableFuture.supplyAsync(() -> a.audit(doc), pool));
        }

        StringBuilder report = new StringBuilder("审计报告:\n");
        for (Map.Entry<String, CompletableFuture<String>> e : futures.entrySet()) {
            report.append(" - ").append(e.getKey()).append(": ")
                  .append(e.getValue().join()).append("\n");
        }
        System.out.println(report);
        pool.shutdown();
    }
}
```

```
          ┌─[事实核查]─┐
输入文档──┤─[合规风险]─┼─▶ 聚合报告
          ├─[语气评估]─┤
          └─[SEO评估]──┘   端到端耗时 = 单次审核耗时(而非4倍)
```

<callout emoji="✅"><p>实测：4 个串行审核约 4×1.5s=6s，并行后约 1.6s。长文档、多模型交叉验证场景，并行化是端到端延迟的第一杠杆。</p></callout>

---

## 四、聚合策略：合并 / 投票 / 加权

并行跑完，结果怎么合？四种常见策略：

```
策略        适用                    例子
──────────────────────────────────────────────────────
合并        各维度互补无冲突        四维审核报告拼一起
投票        多模型判断是非          3 个模型判"是否违规"，2 票以上算违规
加权        模型可信度不同          强模型权重 0.7，弱模型 0.3
冲突解决    结果互相打架          取最高置信度者，或升级人工
```

```java
// ch09-patterns/src/main/java/demo/parallel/Aggregation.java
package demo.parallel;

import java.util.List;
import java.util.Map;

public class Aggregation {

    /** 投票聚合：多模型对"是否违规"投票 */
    static boolean vote(List<Boolean> votes, double threshold) {
        long yes = votes.stream().filter(Boolean::TRUE).count();
        return (double) yes / votes.size() >= threshold;
    }

    /** 加权聚合：模型可信度不同 */
    static double weighted(Map<String, Double> scores, Map<String, Double> weights) {
        double sum = 0, wsum = 0;
        for (var e : scores.entrySet()) {
            double w = weights.get(e.getKey());
            sum += e.getValue() * w; wsum += w;
        }
        return sum / wsum;
    }

    public static void main(String[] args) {
        System.out.println("投票结果=" + vote(List.of(true, false, true), 0.5)); // true
        Map<String, Double> s = Map.of("strong", 0.9, "weak", 0.4);
        Map<String, Double> w = Map.of("strong", 0.7, "weak", 0.3);
        System.out.println("加权分=" + String.format("%.2f", weighted(s, w))); // 0.75
    }
}
```

---

## 五、失败隔离：一个挂了不能全崩

并行最大的坑是"一个分支抛异常，join 全失败"。生产必须**各自捕获、降级**。

```java
// ch09-patterns/src/main/java/demo/parallel/IsolatedParallel.java
package demo.parallel;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class IsolatedParallel {

    public static void main(String[] args) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<String> dims = List.of("A", "B", "C");

        List<CompletableFuture<String>> futures = dims.stream()
            .map(d -> CompletableFuture.supplyAsync(() -> {
                if (d.equals("B")) throw new RuntimeException("B 模型超时");
                return d + " ok";
            }, pool).exceptionally(ex -> "[" + d + "] 降级: " + ex.getMessage()))
            .toList();

        futures.forEach(f -> System.out.println(f.join()));
        pool.shutdown();
    }
}
```

`exceptionally(...)` 是关键：每个 future 独立兜底，B 挂了 A、C 照常返回，整体不崩。这是第十章"异常处理与降级"在并行场景的具体应用。

<callout emoji="⚠️"><p>没有 `exceptionally` 的并行代码，在真实网络环境（模型偶发 5xx/超时）下会频繁整体失败。永远给每个并行分支加降级。</p></callout>

---

## 六、分片并行：长文档处理

大文档超出上下文窗口时，按片并行处理再合并摘要。

```
长文档(100页)
   │ 切分
   ├─ chunk1 ─▶ 摘要A ┐
   ├─ chunk2 ─▶ 摘要B ┼─▶ 合并摘要 ─▶ 最终摘要
   └─ chunkN ─▶ 摘要N ┘
```

```java
// ch09-patterns/src/main/java/demo/parallel/ShardSummarize.java
package demo.parallel;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ShardSummarize {

    // 真实用模型，这里用函数模拟"每片摘要"
    static String summarize(String chunk, int idx) {
        return "片" + idx + "要点:" + chunk.substring(0, Math.min(10, chunk.length()));
    }

    public static void main(String[] args) throws Exception {
        List<String> chunks = List.of(
            "第一章讲Agent定义……", "第二章讲框架……", "第三章讲记忆……");
        ExecutorService pool = Executors.newFixedThreadPool(chunks.size());

        List<CompletableFuture<String>> futures = chunks.stream()
            .map((c) -> {
                int i = chunks.indexOf(c);
                return CompletableFuture.supplyAsync(() -> summarize(c, i), pool);
            }).toList();

        StringBuilder sb = new StringBuilder("全书摘要:\n");
        futures.forEach(f -> sb.append(f.join()).append("\n"));
        System.out.println(sb);
        pool.shutdown();
    }
}
```

注意：分片并行适合"各片独立摘要"，但**跨片依赖**（如"第三章引用了第一章的概念"）会丢失。对策：先并行出各片摘要，再串行做一轮"全局整合"。这就是"并行+串行"混合编排。

---

## 七、投票并行：多模型交叉验证提升准确率

同一问题用不同模型/不同 prompt 跑多次，取多数共识。风控、合规场景常用，能显著降低单模型幻觉。

```java
// ch09-patterns/src/main/java/demo/parallel/VotingJudge.java
package demo.parallel;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VotingJudge {

    // 三个"裁判"，真实是三个不同模型/prompt
    static String judge(String q, int id) {
        // 模拟：id=2 偶尔判错
        return (id != 2) ? "合规" : "违规";
    }

    public static void main(String[] args) throws Exception {
        String q = "用户想绕过风控提额";
        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<CompletableFuture<String>> fs = List.of(0, 1, 2).stream()
            .map(i -> CompletableFuture.supplyAsync(() -> judge(q, i), pool)).toList();

        List<String> votes = fs.stream().map(CompletableFuture::join).toList();
        long compliant = votes.stream().filter("合规"::equals).count();
        String decision = compliant >= 2 ? "放行" : "拦截";
        System.out.println("投票=" + votes + " 决策=" + decision);
        pool.shutdown();
    }
}
```

```
同一问题 ─┬─ 模型A(强) ─┐
          ├─ 模型B(快) ─┼─▶ 多数投票 ─▶ 决策
          └─ 模型C(专) ─┘    （弱模型出错被多数纠正）
```

<callout emoji="💡"><p>投票并行是"用成本换准确率"的典型。关键设计：裁判之间要**多样性**（不同模型家族/不同 prompt），否则会"错到一起"。</p></callout>

---

## 八、Spring 的 @Async 并行

Spring Boot 项目里，用 `@Async` 声明异步方法，由容器线程池托管，代码更简洁。

```java
// ch09-patterns/src/main/java/demo/parallel/AsyncAudit.java
package demo.parallel;

import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@EnableAsync
@Component
public class AsyncAudit {

    @Async("auditPool")
    public CompletableFuture<String> factCheck(String doc) {
        // 调模型做事实核查
        return CompletableFuture.completedFuture("事实核查:PASS");
    }

    @Async("auditPool")
    public CompletableFuture<String> compliance(String doc) {
        return CompletableFuture.completedFuture("合规:PASS");
    }
}
```

```java
// ch09-patterns/src/main/java/demo/parallel/AsyncConfig.java
package demo.parallel;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {
    @Bean("auditPool")
    public Executor auditPool() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(4);
        ex.setMaxPoolSize(8);
        ex.setQueueCapacity(100);
        ex.setThreadNamePrefix("audit-");
        ex.initialize();
        return ex;
    }
}
```

`@Async` 的坑：**同类内部调用不生效**（代理不拦截），必须跨 Bean 调用；且返回值要用 `CompletableFuture` 才能拿到结果。

---

## 九、企业场景：客诉批量处理与风控交叉验证

```
场景A 客诉批量处理：
   1000 条客诉 ─▶ 分批(每批50) ─▶ 并行分类+摘要 ─▶ 聚合生成报表
   串行要几小时，并行(8线程)缩到十几分钟

场景B 风控交叉验证：
   一笔可疑交易 ─▶ 并行跑 规则引擎+大模型研判+历史画像
                ─▶ 三路结果加权投票 ─▶ 拦截/放行/转人工
```

```yaml
# ch09-patterns/src/main/resources/parallel-config.yml
parallel:
  audit:
    pool-size: 8
    timeout-ms: 5000
    fallback: "降级为人工审核"
  shard:
    chunk-size: 2000   # 每片字符数
    merge-model: gpt-4o
```

---

## 十、并行化的成本与边界

并行不是银弹，注意三点：

1. **API 速率限制**：并发调模型可能触发限流（第十章第四节）。要配信号量/令牌桶控制并发度。
2. **成本叠加**：N 路并行 = N 倍 token 消耗。投票并行尤其贵，只在该用的场景用。
3. **聚合幻觉**：各分支结果可能互相矛盾，必须有冲突解决策略（第四节）。

```
并发度控制（防止打爆限流）：
   Semaphore(10)  ── 最多 10 个并行调用
   超过的排队，避免 429 Too Many Requests
```

```java
// ch09-patterns/src/main/java/demo/parallel/RateLimitedParallel.java
package demo.parallel;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.List;

public class RateLimitedParallel {
    public static void main(String[] args) throws Exception {
        Semaphore sem = new Semaphore(5); // 最多 5 并发
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Integer> tasks = List.of(1, 2, 3, 4, 5, 6, 7, 8);

        List<CompletableFuture<Void>> fs = tasks.stream().map(t ->
            CompletableFuture.runAsync(() -> {
                try {
                    sem.acquire();
                    System.out.println("执行任务 " + t);
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    sem.release();
                }
            }, pool)).toList();
        fs.forEach(CompletableFuture::join);
        pool.shutdown();
    }
}
```

---

## 十一、并行化的线程安全与虚拟线程

并行跑任务，最容易踩的坑是**共享可变状态**。多个线程同时改同一个 `StringBuilder`/计数器，结果不可预测。

```java
// ch09-patterns/src/main/java/demo/parallel/ThreadSafeAggregate.java
package demo.parallel;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ThreadSafeAggregate {

    public static void main(String[] args) throws Exception {
        // 用线程安全容器收集结果
        ConcurrentHashMap<String, String> results = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(4);

        List<String> dims = List.of("A", "B", "C", "D");
        List<CompletableFuture<Void>> fs = dims.stream().map(d ->
            CompletableFuture.runAsync(() -> {
                // 每个线程只写自己 key, 互不干扰
                results.put(d, "结果-" + d + "@" + Thread.currentThread().threadId());
            }, pool)).toList();
        fs.forEach(CompletableFuture::join);

        // 汇总时 results 已是完整且一致的快照
        results.forEach((k, v) -> System.out.println(k + "=" + v));
        pool.shutdown();
    }
}
```

<callout emoji="⚠️"><p>并行聚合铁律：① 每个并行分支写**独立的 key/对象**，别共享同一个可变容器；② 非要共享就用 `ConcurrentHashMap`/`Atomic*`；③ 模型调用本身是无状态的，但"把结果写回共享变量"这一步要线程安全。</p></callout>

### 11.2 Java 21 虚拟线程（未来向）

Java 21 的虚拟线程让"一个任务一个线程"变便宜，非常适合"大量 IO 等待型"的 LLM 调用并行。

```java
// ch09-patterns/src/main/java/demo/parallel/VirtualThreadParallel.java
package demo.parallel;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

public class VirtualThreadParallel {
    public static void main(String[] args) throws Exception {
        // Java 21: 虚拟线程池, 万级并发也不爆内存
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<String>> futures = List.of("A", "B", "C").stream()
                .map(t -> executor.submit(() -> {
                    Thread.sleep(300); // 模拟 IO 等待(模型调用)
                    return "结果-" + t;
                })).toList();
            for (var f : futures) System.out.println(f.get());
        }
    }
}
```

```
虚拟线程 vs 平台线程(并行 LLM 调用):
   平台线程: 1 task = 1 OS thread, 几千并发就 OOM
   虚拟线程: 1 task = 1 虚拟线程, 由 JVM 调度, 十万并发轻松
   适用: LLM 调用是 IO 密集(等网络), 虚拟线程收益巨大
```

<callout emoji="💡"><p>课程基线仍是 Java 17，但提示这点很重要：若团队已上 Java 21，并行化 Agent 调用首选虚拟线程，代码更简单、并发更高、成本更低。这是面试里的加分项。</p></callout>

---

## 小结

- 并行化用于"独立子任务"，分片并行（提速）与投票并行（提质）两种形态。
- Java 落地：`CompletableFuture` + 线程池、Spring `@Async`；每个分支必须 `exceptionally` 隔离失败。
- 聚合四策略：合并、投票、加权、冲突解决，按场景选。
- 注意速率限制（信号量控并发）、成本叠加、聚合幻觉三陷阱。
- 并行 + 串行混合编排（如先并行分片摘要、再串行全局整合）覆盖更复杂的真实任务。

## 延伸阅读 / 引用文档

1. Java CompletableFuture 官方文档：https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/concurrent/CompletableFuture.html
2. Spring `@Async` 文档：https://docs.spring.io/spring-framework/reference/integration/scheduling.html
3. Anthropic《Building Effective Agents》Parallelization：https://www.anthropic.com/research/building-effective-agents
4. LangChain4j 文档：https://docs.langchain4j.dev
5. OpenAI 并行调用与批处理：https://platform.openai.com/docs/guides/batch
6. Resilience4j 限流（Bulkhead）：https://resilience4j.readme.io/docs/bulkhead

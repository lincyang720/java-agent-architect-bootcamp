# 第九章第四节：编排-执行（Orchestrator-Workers）：主控协调多子 Agent 的场景与实现

大家好，我是秋天。

前面三节讲的都是"一个输入对应一组固定步骤"。但有些任务你事先不知道要拆几步：比如"帮我做一次竞品分析"，可能要查 3 个网站、读 2 份报告、画 1 张表——步骤数和依赖关系都是动态的。这节课讲**编排-执行（Orchestrator-Workers）**：一个主控（Orchestrator）动态拆解任务、派发给一群专精的子 Agent（Workers），再汇总结果。

学完这节课，你能掌握：

1. **编排-执行 vs 路由/链**：何时该用"动态编排"而非"静态链"。
2. **Orchestrator 的职责**：任务分解、Worker 选择、结果聚合、失败重试。
3. **Java 落地**：用 LangChain4j / Spring AI Alibaba Graph 实现主控协调多子 Agent。
4. **与 Supervisor 模式的关系**：中心化协调的本质。
5. **企业场景**：复杂研报生成、数字员工处理跨系统工单、自动代码生成与多模块联调。

<callout emoji="💡"><p>招聘映射：JD 高频"设计多 Agent 协作架构""理解 Orchestrator/Supervisor 模式""具备 Agent 任务编排经验"。这是"架构师"头衔区别于"开发"的核心能力——能从单 Agent 上升到多 Agent 系统。</p></callout>

---

## 一、三种模式怎么选

```
模式              任务结构              例子
──────────────────────────────────────────────────────────────
提示链(第一节)    固定步骤、强依赖      客服四段链
路由(第二节)      分类后走固定分支      意图分流
并行化(第三节)    独立子任务并发        多维度审核
编排-执行(本节)   步骤数/依赖动态未知   竞品分析、跨系统工单
```

判断框架：**当你写不出固定链、因为"要先看输入才知道要拆几步"时，就该上编排-执行**。

```
编排-执行结构：
            ┌──────────────┐
输入 ─────▶ │ Orchestrator │ (主控：拆解+派发+聚合)
            └──────┬───────┘
       ┌──────────┼──────────┐
       ▼          ▼          ▼
   [Worker A]  [Worker B]  [Worker C]   (各专精一领域，可带工具/记忆)
       │          │          │
       └──────────┼──────────┘
                  ▼
             Orchestrator 聚合结果 ─▶ 输出
```

---

## 二、Orchestrator 的核心职责

Orchestrator 不是"又一个 LLM 调用"，而是一个**协调程序**（可以是代码 + 一次 LLM 规划调用）。四类职责：

1. **分解（Decompose）**：把大任务拆成子任务清单（可让 LLM 生成计划）。
2. **派发（Dispatch）**：为每个子任务选合适的 Worker（路由思想的升级）。
3. **聚合（Aggregate）**：收齐 Worker 结果，去重、合并、冲突解决。
4. **重试/升级（Recover）**：某 Worker 失败，重派或升级人工。

```
Orchestrator 主循环（伪代码）：
  plan = llm.plan(input)            # 生成子任务列表
  results = {}
  for task in plan:
      worker = selectWorker(task)   # 选 Worker
      results[task] = worker.run(task)  # 执行(可并行/串行)
      if failed: retry or escalate
  return aggregate(results)         # 聚合
```

<callout emoji="⚠️"><p>关键认知：Orchestrator 的"拆解"和"派发"尽量用代码 + 结构化输出来做，别让 LLM 自由发挥决定一切。自由发挥 = 不可控、不可测、难回放（呼应第十七章 Durable Execution）。</p></callout>

---

## 三、Java 实现：主控 + 多 Worker（LangChain4j）

下面用一个"研报编排器"演示：主控让 LLM 先列子任务，再分派给不同专精 Worker。

```java
// ch09-patterns/src/main/java/demo/orchestrator/ReportOrchestrator.java
package demo.orchestrator;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

import java.util.*;

public class ReportOrchestrator {

    // Worker 接口：每个子 Agent 统一契约
    interface Worker {
        String run(String task);
    }

    // 具体 Worker：市场调研
    static class MarketWorker implements Worker {
        private final ChatLanguageModel m;
        MarketWorker(ChatLanguageModel m) { this.m = m; }
        public String run(String task) {
            var a = AiServices.create(SubAgent.class, m);
            return a.act("你是市场调研专家，" + task);
        }
    }
    // 具体 Worker：技术评估
    static class TechWorker implements Worker {
        private final ChatLanguageModel m;
        TechWorker(ChatLanguageModel m) { this.m = m; }
        public String run(String task) {
            var a = AiServices.create(SubAgent.class, m);
            return a.act("你是技术评估专家，" + task);
        }
    }
    interface SubAgent {
        @SystemMessage("{{it}}") String act(String prompt);
    }

    // Orchestrator：规划 + 派发 + 聚合
    interface Planner {
        @SystemMessage("""
            把用户的研究主题拆成 2-3 个独立子任务，每行一个，
            前缀标注领域(market/tech)。只列任务，不解释。
            例：
            market: 分析目标市场规模
            tech: 评估关键技术成熟度
            """)
        String plan(@UserMessage String topic);
    }

    public static void main(String[] args) {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY")).modelName("gpt-4o").build();

        Map<String, Worker> registry = Map.of(
                "market", new MarketWorker(model),
                "tech", new TechWorker(model));

        Planner planner = AiServices.create(Planner.class, model);
        String topic = "分析 AI Agent 在企业客服的落地";
        String plan = planner.plan(topic);
        System.out.println("=== Orchestrator 计划 ===\n" + plan);

        // 逐行解析计划，派发对应 Worker
        StringBuilder report = new StringBuilder();
        for (String line : plan.split("\n")) {
            if (line.isBlank()) continue;
            String[] parts = line.split(":", 2);
            String domain = parts[0].trim();
            String task = parts.length > 1 ? parts[1].trim() : line;
            Worker w = registry.get(domain);
            if (w == null) { report.append("未知领域: ").append(line).append("\n"); continue; }
            report.append("【").append(domain).append("】").append(w.run(task)).append("\n");
        }
        System.out.println("=== 聚合报告 ===\n" + report);
    }
}
```

这里的 `registry` 就是 Worker 注册表——Orchestrator 按领域选择 Worker，正是"路由"思想在动态任务上的应用。

---

## 四、用 Spring AI Alibaba Graph 声明编排

Spring AI Alibaba 的 `Graph` 可以把 Orchestrator 声明成图：一个 planner 节点 + 多个 worker 节点 + 聚合节点。

```java
// ch09-patterns/src/main/java/demo/orchestrator/GraphOrchestrator.java
package demo.orchestrator;

import com.alibaba.cloud.ai.graph.Graph;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.node.LlmNode;
import com.alibaba.cloud.ai.graph.overall.OverAllState;

public class GraphOrchestrator {
    public static void main(String[] args) throws Exception {
        // 声明式：planner -> (market | tech) -> aggregate
        StateGraph graph = new StateGraph("report-orchestrator");
        graph.addNode("planner", LlmNode.builder()
                .system("拆解研究主题为子任务").build());
        graph.addNode("market", LlmNode.builder()
                .system("市场调研专家").build());
        graph.addNode("tech", LlmNode.builder()
                .system("技术评估专家").build());
        graph.addNode("aggregate", LlmNode.builder()
                .system("汇总各 Worker 结果成报告").build());

        graph.addEdge("planner", "market");
        graph.addEdge("planner", "tech");
        graph.addEdge("market", "aggregate");
        graph.addEdge("tech", "aggregate");

        Graph compiled = graph.compile();
        System.out.println("编排图编译完成，节点=" + compiled.nodes().size());
    }
}
```

图的声明式写法好处是：**可视化、可持久化、节点可单独观测与重放**（呼应第十章可观测性与第十七章 Durable Execution）。

<callout emoji="✅"><p>工程建议：中小团队先用"代码版 Orchestrator"（第三节那种 Map 注册表）验证业务逻辑，等流程稳定了再升级成 Graph 声明式，避免过早抽象。</p></callout>

---

## 五、动态 Worker 选择的进阶：让 LLM 决定派发给谁

更动态的玩法：Orchestrator 不直接 `switch`，而是把"可用 Worker 清单 + 描述"喂给 LLM，让它输出"派发计划"。

```java
// ch09-patterns/src/main/java/demo/orchestrator/DynamicDispatch.java
package demo.orchestrator;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public class DynamicDispatch {

    interface Dispatcher {
        @SystemMessage("""
            可用 Worker:
            - search: 联网检索信息
            - db: 查询内部数据库
            - code: 写/改代码
            - calc: 数学计算
            根据用户任务，输出应调用的 Worker 列表(JSON数组)，只输出JSON。
            例: ["search","db"]
            """)
        String dispatch(@UserMessage String task);
    }

    public static void main(String[] args) {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini").temperature(0).build();
        Dispatcher d = AiServices.create(Dispatcher.class, model);
        String plan = d.dispatch("统计上个月退款总额并和竞品对比");
        System.out.println("派发计划=" + plan); // 例: ["db","search"]
    }
}
```

这种"LLM 生成派发计划 + 代码执行计划"的混合，是 Plan-and-Execute（第八章 Planning）与编排-执行的结合体。风险是 LLM 可能生成非法 Worker 名——代码层必须**校验计划中的 Worker 都在注册表里**，否则拒绝执行。

<callout emoji="⚠️"><p>安全红线：LLM 输出的派发计划属于"不可信输入"，必须做白名单校验。绝不直接 `eval()` 或反射调用任意类。这是 Agent 安全（第十章第五节、第十二章）的硬要求。</p></callout>

---

## 六、Worker 的结果聚合与冲突解决

多 Worker 返回结果可能重叠或冲突。聚合层要处理：

```
聚合策略：
  1. 去重：同一事实多个 Worker 都说，只留一份
  2. 冲突：Worker A 说"市场增长"，Worker B 说"市场萎缩" → 标记冲突，交 LLM 裁决或升级人工
  3. 合并：互补信息拼成完整报告
```

```java
// ch09-patterns/src/main/java/demo/orchestrator/Aggregator.java
package demo.orchestrator;

import java.util.*;

public class Aggregator {

    /** 简单聚合：去重合并 + 冲突标注 */
    static String aggregate(Map<String, String> results) {
        StringBuilder sb = new StringBuilder();
        for (var e : results.entrySet()) {
            sb.append("【").append(e.getKey()).append("】").append(e.getValue()).append("\n");
        }
        // 真实场景用 LLM 做一致性检查与冲突标记
        return sb.toString();
    }

    public static void main(String[] args) {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("market", "市场规模年增 30%");
        r.put("tech", "关键技术已成熟可落地");
        System.out.println(aggregate(r));
    }
}
```

---

## 七、失败重试与升级人工

Orchestrator 必须有恢复能力。某 Worker 失败时的决策树：

```
Worker 失败
   │
   ├─ 可重试(超时/5xx) ─▶ 退避重试(最多 N 次)
   ├─ 不可重试(输入非法) ─▶ 记录并跳过该子任务
   └─ 重试耗尽 ─▶ 升级人工 + 保留已完成结果(部分成功)
```

```java
// ch09-patterns/src/main/java/demo/orchestrator/RetryOrchestrator.java
package demo.orchestrator;

import java.util.function.Function;

public class RetryOrchestrator {

    static String runWithRetry(String task, Function<String, String> worker,
                               int maxRetry) {
        int attempt = 0;
        while (attempt <= maxRetry) {
            try {
                return worker.apply(task);
            } catch (RuntimeException e) {
                attempt++;
                if (attempt > maxRetry) {
                    return "[部分成功] 子任务失败已标记人工: " + task;
                }
                try { Thread.sleep(500L * attempt); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return "[未知] " + task;
    }

    public static void main(String[] args) {
        String r = runWithRetry("调研竞品A", (t) -> {
            if (Math.random() < 0.7) throw new RuntimeException("超时");
            return "竞品A 份额 20%";
        }, 3);
        System.out.println(r);
    }
}
```

指数退避（`500ms × attempt`）避免对失败 Worker 的"惊群"重试。

---

## 八、企业场景：数字员工处理跨系统工单

一个真实"数字员工"场景：用户报障"订单 A 钱扣了但没发货"，数字员工（Orchestrator）要协调多个系统 Worker：

```
用户报障
   │
Orchestrator(拆解)
   ├─ Worker[支付系统]: 查扣款记录 → 已扣款
   ├─ Worker[订单系统]: 查发货状态 → 未发货
   ├─ Worker[库存系统]: 查库存 → 缺货
   └─ Worker[规则引擎]: 按策略判定 → 触发自动退款+补发
        │
   聚合 → 生成处理工单 + 用户通知
```

每个 Worker 背后是 MCP 工具（第十一章）或内部 API 封装，Orchestrator 只管"拆、派、聚、救"。这种架构让"一个数字员工替代多个岗位协作"成为可能。

```yaml
# ch09-patterns/src/main/resources/orchestrator-config.yml
orchestrator:
  workers:
    - name: payment
      type: mcp
      endpoint: http://mcp-payment.internal
    - name: order
      type: mcp
      endpoint: http://mcp-order.internal
    - name: inventory
      type: mcp
      endpoint: http://mcp-inventory.internal
  retry:
    max: 3
    backoff-ms: 500
  aggregate:
    strategy: llm-consensus
```

---

## 九、与 Supervisor 模式的关系

第四章讲 Supervisor，本节讲 Orchestrator-Workers，两者关系：

```
Supervisor = Orchestrator 的"强管控"版本：
   - Supervisor 持有所有 Worker 的引用，可随时干预/回收
   - 常用于"多个同级 Agent 协作完成一个目标"

Orchestrator-Workers = 更通用的"主控-执行"范式：
   - 强调"动态拆解 + 按领域派发"
   - 可中心化(Supervisor)也可层次化(Orchestrator 之下再有子 Orchestrator)
```

层次化示例：

```
        Top Orchestrator
       /        |        \
   Sub-O1    Sub-O2    Sub-O3
    | |        |          |
  W W W      W W W      W W W
```

这种层次结构适合超大规模任务（如自动生成一整个微服务项目），但复杂度陡增，中小团队慎用。

---

## 十、Orchestrator 的状态管理与可观测

Orchestrator 分派多个 Worker，必须记录"谁在做、做到哪、结果如何"——否则中途挂了无法恢复，也排查不了。

```
Orchestrator 状态结构：
   taskId
     ├─ plan: [子任务列表]
     ├─ assignments: {子任务 → Worker + 状态(待派发/执行中/完成/失败)}
     ├─ results: {子任务 → 产出}
     └─ status: IN_PROGRESS / AGGREGATED / FAILED_PARTIAL
```

```java
// ch09-patterns/src/main/java/demo/orchestrator/OrchestratorState.java
package demo.orchestrator;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class OrchestratorState {

    enum Status { PENDING, RUNNING, DONE, FAILED }

    record SubTask(String id, String domain, Status status, String result) {}

    private final String taskId;
    private final Map<String, SubTask> tasks = new ConcurrentHashMap<>();
    private final List<String> log = Collections.synchronizedList(new ArrayList<>());

    public OrchestratorState(String taskId) { this.taskId = taskId; }

    public void assign(String id, String domain) {
        tasks.put(id, new SubTask(id, domain, Status.PENDING, null));
        log.add("分配子任务 " + id + " → " + domain);
    }

    public void complete(String id, String result) {
        SubTask t = tasks.get(id);
        tasks.put(id, new SubTask(t.id(), t.domain(), Status.DONE, result));
        log.add("子任务 " + id + " 完成");
    }

    public String snapshot() {
        long done = tasks.values().stream().filter(t -> t.status() == Status.DONE).count();
        return String.format("task=%s 进度=%d/%d 日志=%s",
                taskId, done, tasks.size(), log);
    }

    public static void main(String[] args) {
        OrchestratorState s = new OrchestratorState("T1");
        s.assign("s1", "market");
        s.assign("s2", "tech");
        s.complete("s1", "市场规模30%");
        System.out.println(s.snapshot());
    }
}
```

<callout emoji="💡"><p>把状态外挂到 Redis/数据库（而非只在内存），配合第十七章 Durable Execution（Temporal/Restate），Orchestrator 就能"挂了之后从断点恢复"，这是长任务（如"生成一整个项目"）上生产的必备能力。</p></callout>

---

## 十一、Orchestrator 与可观测性的对接

每个子任务的派发/完成都打点（呼应第十章第一节），才能在 Grafana 看到"哪个 Worker 是瓶颈"。

```java
// ch09-patterns/src/main/java/demo/orchestrator/ObservedOrchestrator.java
package demo.orchestrator;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

public class ObservedOrchestrator {
    private final MeterRegistry meter;

    public ObservedOrchestrator(MeterRegistry meter) { this.meter = meter; }

    public String dispatchAndTime(String worker, java.util.function.Supplier<String> action) {
        long start = System.nanoTime();
        try {
            return action.get();
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            Timer.builder("orchestrator.worker.latency")
                 .tag("worker", worker)
                 .register(meter)
                 .record(ms, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }
}
```

```
可观测面板(Orchestrator 视角):
   - 各 Worker 平均/ P99 耗时(找瓶颈)
   - 各 Worker 失败率(找不稳定依赖)
   - 任务整体完成率(部分失败占比)
```

---

## 小结

- 编排-执行用于"步骤数/依赖动态未知"的任务，是比静态链更灵活的范式。
- Orchestrator 四职责：分解、派发、聚合、重试升级；拆解与派发尽量用代码+结构化，避免 LLM 自由发挥失控。
- 落地：代码版用 `Map<domain, Worker>` 注册表；稳定后升级为 Spring AI Alibaba Graph 声明式。
- LLM 生成的派发计划属不可信输入，必须白名单校验；Worker 失败用指数退避重试，耗尽则升级人工并保留部分成果。
- 与 Supervisor 同源，可层次化扩展；数字员工跨系统工单是典型落地。

## 延伸阅读 / 引用文档

1. Anthropic《Building Effective Agents》Orchestrator-Workers：https://www.anthropic.com/research/building-effective-agents
2. Spring AI Alibaba Graph 文档：https://java2ai.com/docs/graph
3. LangChain4j 文档：https://docs.langchain4j.dev
4. Google ADK for Java（Sequential/Parallel Agent）：https://google.github.io/adk-docs/
5. AgentScope（多 Agent 协作）：https://github.com/modelscope/agentscope
6. Temporal（Durable Execution，长任务编排）：https://temporal.io/

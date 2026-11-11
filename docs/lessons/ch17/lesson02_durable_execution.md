# 第十七章第2节：Durable Execution：长任务执行框架（Temporal / Restate）

大家好，我是秋天。

上一节（lesson01）我们讲了 Agent 怎么无状态化、怎么水平扩展。但留了一个最难的问题没解决：**长任务的"执行位置"这层状态，没法简单塞进 Redis**。你说"对账跑 40 分钟"，中途进程被 K8s 重启了，你总不能把"跑到第 27 步"这种正在运行的自动机状态序列化到 Redis 再恢复吧？——理论上能，但自己写就是一堆脆弱的"检查点 + 补偿 + 重试"胶水代码，迟早出 bug。

这一节的主角就是 **Durable Execution（持久化执行）**：一种专门让"长时间、多步骤、可能崩溃"的任务具备"崩溃可恢复、步骤可重放、天然幂等"能力的执行模型。两大代表框架 **Temporal** 和 **Restate**，都有一等公民级的 Java SDK。我会讲清：为什么 Agent 长任务非它不可，以及给你能编译的 Java Workflow 示例。

这节课讲什么、学完掌握什么：

- 讲什么：① 为什么普通"重试 + 状态机"扛不住 Agent 长任务；② Durable Execution 的核心思想（确定性重放、事件溯源、检查点）；③ Temporal 的 Java Workflow 怎么写（含 Activity、计时器、补偿）；④ Restate 的 Java 写法与差异；⑤ 它和 Agent 编排怎么结合。
- 学完掌握：你理解"Durable Workflow 就是把普通代码变成可恢复的状态机"；你能写出一个 Temporal Java Workflow 实现"调用 LLM → 调工具 → 等待人工 → 继续"的多步 Agent 流程；你知道什么时候该上 Durable Execution，什么时候是杀鸡用牛刀。

<callout emoji="💡"><p>招聘映射：阿里/字节的 Agent 平台岗、以及做"数字员工/自动化流程"的团队，JD 里频繁出现"熟悉 Temporal / 工作流引擎 / 长事务补偿 / Saga"。这是比"会调 ChatClient"高一个层级的能力——它证明你能把不可靠的多步流程变得可靠。V2 大纲把 Durable Execution 单独成节，正是因为它在 2026 招聘里从"加分"变成了"平台岗必备"。</p></callout>

---

## 一、普通重试为什么扛不住 Agent 长任务

先看你最容易写的"朴素版"长任务：

```java
public void runLongAgentTask(String taskId) {
    String plan = llm.plan(taskId);          // 步骤1
    String data = tool.fetchData(taskId);    // 步骤2（慢，可能超时）
    String draft = llm.draft(plan, data);    // 步骤3
    tool.sendForApproval(draft);             // 步骤4
    tool.publish(draft);                     // 步骤5
}
```

它脆弱在哪？列一张"崩溃账单"：

```text
崩溃点                后果                      朴素重试的灾难
步骤2 超时重跑        fetchData 重复调用         可能重复扣费/重复拉数
步骤3 之后进程重启    前面全白干，从头再来        用户等 40 分钟发现重头跑
步骤4 发审批后崩溃    审批发了，但 publish 没跑   状态不一致（发了又没发）
步骤5 之前又崩溃      发了两次 publish           重复推送，用户收到两遍
```

根因：**步骤之间没有"持久化的边界"，且每个步骤默认不幂等**。你用 `try-catch` + `Thread.sleep` + 手动存 DB 状态，能补，但每加一个步骤就多一堆补偿逻辑，最后代码里 70% 是"容错胶水"，30% 是业务逻辑——这叫"容错代码吞噬业务代码"。

<callout emoji="⚠️"><p>真实事故（脱敏）：某对账 Agent 用朴素循环写，步骤3 调第三方接口超时，重试时没判断"上次是否已经写入"，导致同一笔差异被写入两次，对账报表出现"幽灵金额"，排查三天。这类 bug 的本质是：你把"执行到了哪一步"这件事，交给了易失的内存，而不是 durable 的存储。</p></callout>

---

## 二、Durable Execution 的核心思想

Durable Execution 框架做的事，可以一句话概括：**把你的代码执行过程"录制"成事件日志，崩溃后靠日志重放恢复，而不是靠内存状态**。

```
普通执行（易失）：
   代码执行 → 内存里的局部变量/进度 → 进程死 → 全没

Durable 执行（持久化）：
   代码执行 → 每个"步骤边界"都落事件日志(Event History)
              [Start][ActivityA 结果][Timer][ActivityB 结果]...
   进程死 → 新进程起来 → 读事件日志 → 重放已确定的部分
          → 从最后一个未完成的步骤继续（而非从头）
```

三个关键词，必须吃透：

### 1. 事件溯源（Event Sourcing）
Workflow 的"状态"不是一份随时改的内存对象，而是一串**不可变事件**。恢复时重放这串事件，就能重建状态。这保证了"崩溃前后状态一致"。

### 2. 确定性重放（Deterministic Replay）
Workflow 代码里**不能出现不确定性**——不能有 `Math.random()`、不能读当前时间 `System.currentTimeMillis()`、不能直接 `new Thread()`。所有"不确定性"都必须通过框架提供的确定性原语（`Workflow.currentTimeMillis()`、Workflow 定时器、Activity 调用）获取。这样重放时结果才和第一次一致。

### 3. 检查点（Checkpoint）与 Activity 幂等
"重活"（调 LLM、调外部 API、发消息）都放到 **Activity** 里。Activity 是普通 Java 方法，但框架保证：同一 Activity 在重试时会复用结果（靠 Activity 的 id + 重试策略），且你应把 Activity 写成幂等的。

```
Workflow（确定性、可重放、不含重活）
   │
   ├─▶ Activity: callLLM()        （重活，可重试、可超时、可补偿）
   ├─▶ Activity: fetchData()      （重活）
   ├─▶ Workflow.await(人工确认)    （可持久化的等待，不占线程）
   └─▶ Activity: publish()
```

<callout emoji="✅"><p>一句话记住：Workflow 是"剧本"（确定性、可重放），Activity 是"演员干的重活"（可重试、可超时、幂等）。剧本崩了能按事件日志重演，演员累了能换一个重来——这就是 Durable 的精髓。</p></callout>

---

## 三、Temporal Java Workflow 实战

Temporal 是 Durable Execution 的事实标准。它的 Java SDK（`io.temporal`) 让你用普通 Java 接口定义 Workflow 和 Activity。

依赖（`pom.xml`）：

```xml
<dependencies>
    <dependency>
        <groupId>io.temporal</groupId>
        <artifactId>temporal-sdk</artifactId>
        <version>1.24.1</version>
    </dependencies>
</dependencies>
```

### 3.1 定义 Activity 接口（重活都在这里）

```java
package com.example.ch17.durable;

import java.util.List;

/**
 * Agent 长任务里所有"重活"都声明为 Activity。
 * 每个方法都应是幂等的：相同输入重复调用，副作用只发生一次（或等价）。
 */
public interface AgentActivities {

    /** 步骤1：让 LLM 生成执行计划 */
    String makePlan(String taskId);

    /** 步骤2：从外部系统拉数据（慢、可能超时） */
    String fetchData(String taskId);

    /** 步骤3：LLM 基于计划+数据起草结果 */
    String draftResult(String plan, String data);

    /** 步骤4：把草稿送审批（发消息/写库，幂等靠 taskId 去重） */
    void sendForApproval(String taskId, String draft);

    /** 步骤5：发布最终结果 */
    void publish(String taskId, String draft);
}
```

### 3.2 实现 Activity（注意幂等）

```java
package com.example.ch17.durable;

import io.temporal.activity.Activity;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AgentActivitiesImpl implements AgentActivities {

    // 生产里用 DB/Redis 去重，这里用内存示意"幂等键"
    private static final Set<String> PUBLISHED = ConcurrentHashMap.newKeySet();

    @Override
    public String makePlan(String taskId) {
        // 真实场景：调 DashScope / OpenAI
        return "PLAN:" + taskId + " -> [fetch, draft, approve, publish]";
    }

    @Override
    public String fetchData(String taskId) {
        // 模拟慢调用；Temporal 的 Activity 超时/重试在此生效
        return "DATA:" + taskId;
    }

    @Override
    public String draftResult(String plan, String data) {
        return "DRAFT based on " + plan + " & " + data;
    }

    @Override
    public void sendForApproval(String taskId, String draft) {
        // 幂等：用 taskId 去重，重复调用不会重复发审批
        String key = "approve:" + taskId;
        if (PUBLISHED.contains(key)) return;
        System.out.println("[approval] 发送审批: " + draft);
        PUBLISHED.add(key);
    }

    @Override
    public void publish(String taskId, String draft) {
        String key = "publish:" + taskId;
        if (PUBLISHED.contains(key)) {
            System.out.println("[publish] 已发布过，跳过（幂等）: " + taskId);
            return;
        }
        System.out.println("[publish] 发布: " + draft);
        PUBLISHED.add(key);
    }
}
```

### 3.3 定义 Workflow 接口与实现（剧本）

```java
package com.example.ch17.durable;

/** Workflow 接口：方法即"任务入口" */
public interface AgentWorkflow {
    @WorkflowInterface
    interface LongAgentTask {
        @WorkflowMethod
        void execute(String taskId);
    }
}
```

实现（确定性，所有"等待/重活"都走框架原语）：

```java
package com.example.ch17.durable;

import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowQueue;

public class AgentWorkflowImpl implements AgentWorkflow.LongAgentTask {

    // Activity 存根：由 Temporal 在运行时注入（重试/超时/重放都框架管）
    private final AgentActivities activities =
            Workflow.newActivityStub(AgentActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(Duration.ofMinutes(5))  // 单步超时
                            .setRetryOptions(RetryOptions.newBuilder()
                                    .setMaximumAttempts(3)                  // 最多重试3次
                                    .build())
                            .build());

    @Override
    public void execute(String taskId) {
        // 步骤1-3：确定性编排，崩溃后从事件日志重放，不重复跑
        String plan = activities.makePlan(taskId);
        String data = activities.fetchData(taskId);
        String draft = activities.draftResult(plan, data);

        // 步骤4前：持久化等待人工确认（不占线程！崩溃也不丢）
        WorkflowQueue<Boolean> approval = Workflow.newQueue(1);
        activities.sendForApproval(taskId, draft);
        Boolean approved = approval.poll(Duration.ofHours(24)); // 最多等24h
        if (!Boolean.TRUE.equals(approved)) {
            System.out.println("[abort] 未获审批，终止: " + taskId);
            return;
        }

        // 步骤5：发布（幂等）
        activities.publish(taskId, draft);
    }
}
```

<callout emoji="💡"><p>注意 `WorkflowQueue.poll` 这行——它代表"等待人工确认"。在普通代码里这会阻塞一个线程 24 小时；但在 Temporal 里，这是一个**持久化的等待**：线程释放、事件日志记一笔"在等审批"，进程哪怕重启，恢复后依然在等。这就是 Durable 的魔力：长等待不烧资源。</p></callout>

### 3.4 Worker 启动与发起任务

```java
package com.example.ch17.durable;

import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;

public class AgentWorker {

    public static void main(String[] args) {
        // 连接 Temporal Server（本地 dev-server 或云端）
        WorkflowServiceStubs stubs = WorkflowServiceStubs.newLocalServiceStubs();
        WorkflowClient client = WorkflowClient.newInstance(stubs);

        // 注册 Worker：绑定 task queue 与 Workflow/Activity 实现
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker("agent-task-queue");
        worker.registerWorkflowImplementationTypes(AgentWorkflowImpl.class);
        worker.registerActivitiesImplementations(new AgentActivitiesImpl());
        factory.start();

        System.out.println("Agent Worker 已启动，等待任务...");
    }

    /** 另起一个进程/接口，提交任务（可在 REST 接口里调用） */
    public static void submit(WorkflowClient client, String taskId) {
        AgentWorkflow.LongAgentTask workflow = client.newWorkflowStub(
                AgentWorkflow.LongAgentTask.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue("agent-task-queue")
                        .setWorkflowId("agent-" + taskId)  // 同 id 不重复启动
                        .build());
        // 异步启动，立即返回；真正执行交给 Worker
        WorkflowClient.start(workflow::execute, taskId);
    }
}
```

这套代码的"抗崩溃"体现在：哪怕 `AgentWorker` 进程在执行步骤 2 时整个被杀，只要再起一个 Worker，Temporal Server 会从事件日志里看到"步骤1 已完成、步骤2 未完成"，把步骤 2 派给新 Worker 重跑（Activity 幂等保证安全），而不是从头来。

---

## 四、Restate：更轻量的 Durable 选择

Temporal 功能强但"重"（要维护 Temporal Server）。**Restate** 是 newer 的 Durable Execution 框架，特点是：把 Durable 能力做成**一行 SDK 调用**，Server 极轻，且专为"分布式服务 / Agent 编排"设计，对 Java 友好。

Restate 的核心概念：**RestateContext** 提供 `sleep`（持久化睡眠）、`invoke`（持久化调用）、`awakeable`（持久化等待信号）等原语。

依赖：

```xml
<dependency>
    <groupId>dev.restate</groupId>
    <artifactId>restate-sdk</artifactId>
    <version>1.3.0</version>
</dependency>
```

一个 Restate 版"长任务 Agent"示例（用 `awakeable` 等待人工确认）：

```java
package com.example.ch17.durable;

import dev.restate.sdk.Context;
import dev.restate.sdk.annotation.Handler;
import dev.restate.sdk.annotation.Service;
import dev.restate.sdk.common.Sleepable;

@Service(name = "LongAgentTask")
public class RestateAgentTask {

    @Handler
    public String execute(Context ctx, String taskId) {
        // 1. 调 LLM 生成计划（普通 Java 调用，但 ctx 保证可恢复）
        String plan = callLLM(ctx, "plan for " + taskId);

        // 2. 拉数据（ctx.run 保证这段只真正执行一次，重放时复用结果）
        String data = ctx.run(String.class, () -> fetchData(taskId));

        // 3. 起草
        String draft = callLLM(ctx, plan + " | " + data);

        // 4. 持久化等待人工确认（awakeable：释放线程，不烧资源）
        String approvalId = ctx.awakeable(String.class).id();
        ctx.run(() -> sendForApproval(taskId, draft, approvalId));

        // 等待外部通过 Restate 的 /restate/awakeables/{id} 唤醒
        String decision = ctx.awakeable(String.class).await();
        if (!"APPROVE".equals(decision)) {
            return "ABORTED:" + taskId;
        }

        // 5. 发布
        ctx.run(() -> publish(taskId, draft));
        return "DONE:" + taskId;
    }

    private String callLLM(Context ctx, String prompt) {
        // 真实调 DashScope / OpenAI；ctx 提供可恢复的执行保证
        return "LLM(" + prompt.length() + ")";
    }
    private String fetchData(String taskId) { return "DATA:" + taskId; }
    private void sendForApproval(String t, String d, String id) {
        System.out.println("[approval] " + d + " (awakeable=" + id + ")");
    }
    private void publish(String t, String d) { System.out.println("[publish] " + d); }
}
```

Restate 的 `ctx.run(...)` 是关键：它保证花括号里的代码**在崩溃重放时只真正执行一次**（结果被持久化），天然解决了"重试导致重复副作用"的难题。`awakeable` 则是 Restate 的"持久化等待外部信号"原语，完美对应"等人工确认"。

<callout emoji="✅"><p>Temporal vs Restate 怎么选？简版：要成熟生态、强工作流可视化、复杂 Saga 补偿 → Temporal；要轻量部署、和微服务/Agent 编排深度整合、少运维负担 → Restate。两者核心都是"Durable Execution"，思想互通，学会一个另一个很快上手。</p></callout>

---

## 五、Durable Execution 与 Agent 编排怎么结合

最自然的结合点：**把"一个多步 Agent 任务"整体包成一个 Durable Workflow**。比如"客服工单自动处理 Agent"：

```text
传统做法（脆弱）：
   收到工单 → 循环调 LLM 分类 → 调知识库 → 调工单系统 → 回复用户
   任何一步崩 → 整单重来 / 状态错乱

Durable 做法（可靠）：
   Workflow.execute(ticketId):
     category = Activity.classify(ticket)        # 可重试
     ctx      = Activity.searchKB(ticket)         # 可重试
     reply    = Activity.draftReply(category,ctx) # 可重试
     if needHuman: await(approval)                 # 持久化等待
     Activity.replyUser(ticket, reply)             # 幂等
```

这直接对应了多智能体编排（第四章）里的"Plan-and-Execute / Supervisor"——只是把"执行"这一层从内存挪进了 Durable 框架，从此：

```
崩溃前：Agent 跑到第 3 步
崩溃后：进程重启 / 换机器 → 从第 3 步继续，1-2 步结果复用，用户无感
```

<callout emoji="💡"><p>结合 lesson01：API 层无状态（可 HPA），长任务层 Durable（可恢复），两者配合才是完整的生产 Agent 架构。短问答走无状态 API，长流程走 Durable Workflow——这是企业落地的标准分层。</p></callout>

---

## 五（续）、在 Spring Boot 中落地：从 REST 提交到注入

前面给的是独立 `main`。真实项目里，你不会手写 `main` 起 Worker，而是把 Temporal/Restate 当成 Spring Bean 集成进 Agent 平台（对应 lesson01 的容器化部署）。下面是一个最小但真实的 Spring Boot 集成骨架。

`application.yml`（Server 地址走环境变量，不硬编码）：

```yaml
temporal:
  host: ${TEMPORAL_HOST:localhost:7233}
agent:
  task-queue: agent-task-queue
```

配置类：把 `WorkflowClient` 注册成 Bean，并在 `@PostConstruct` 里启动 Worker（和 lesson01 的无状态 API 跑在同一个应用或独立 Deployment 均可）：

```java
package com.example.ch17.durable.spring;

import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Configuration
public class TemporalConfig {

    @Value("${temporal.host}")
    private String temporalHost;
    @Value("${agent.task-queue}")
    private String taskQueue;

    private WorkerFactory factory;

    @Bean
    public WorkflowClient workflowClient() {
        WorkflowServiceStubs stubs =
                WorkflowServiceStubs.newServiceStubs(
                        WorkflowServiceStubsOptions.newBuilder()
                                .setTarget(temporalHost).build());
        return WorkflowClient.newInstance(stubs);
    }

    @PostConstruct
    public void startWorker() {
        WorkerFactory f = WorkerFactory.newInstance(workflowClient());
        Worker worker = f.newWorker(taskQueue);
        worker.registerWorkflowImplementationTypes(AgentWorkflowImpl.class);
        worker.registerActivitiesImplementations(new AgentActivitiesImpl());
        f.start();
        this.factory = f;
        System.out.println("Temporal Worker 已注册 taskQueue=" + taskQueue);
    }

    @PreDestroy
    public void stop() {
        if (factory != null) factory.shutdown();
    }
}
```

对外暴露一个提交任务的 REST 接口（注意它**立即返回 taskId**，长任务交给 Worker 异步跑——和 lesson01 的"异步化解耦"呼应）：

```java
package com.example.ch17.durable.spring;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/agent/task")
public class AgentTaskController {

    private final WorkflowClient client;

    public AgentTaskController(WorkflowClient client) {
        this.client = client;
    }

    public record SubmitReq(String taskId) {}
    public record SubmitResp(String workflowId, String status) {}

    @PostMapping
    public SubmitResp submit(@RequestBody SubmitReq req) {
        AgentWorkflow.LongAgentTask wf = client.newWorkflowStub(
                AgentWorkflow.LongAgentTask.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue("agent-task-queue")
                        .setWorkflowId("agent-" + req.taskId)  // 同 id 去重
                        .build());
        WorkflowClient.start(wf::execute, req.taskId);
        return new SubmitResp("agent-" + req.taskId, "SUBMITTED");
    }

    @GetMapping("/{workflowId}")
    public String status(@PathVariable String workflowId) {
        // 真实项目用 WorkflowClient.getWorkflowHandle(...).queryStatus()
        return "查询 " + workflowId + " 的持久化状态（Temporal UI 也可看）";
    }
}
```

### 可观测：Durable 任务也要被看见

Durable 不等于"看不见"。Temporal 自带 Web UI 能看每个 Workflow 的事件历史和当前停在哪一步；生产还要把指标接进 Prometheus：

```yaml
# temporal 服务端暴露 metrics，用 prometheus 抓取
# 关键指标：
# temporal_workflow_completed_total        # 完成任务数
# temporal_workflow_failed_total           # 失败任务数（突增=异常）
# temporal_activity_schedule_to_start_latency  # Activity 调度延迟
# temporal_worker_task_slots_available     # Worker 空闲槽位（满了=积压）
```

<callout emoji="✅"><p>把 Durable 任务的"卡在哪一步、等了多久审批、重试了几次"做成 Grafana 面板，是平台工程（lesson04）的标配能力。运维一眼能看出"哪个长任务卡住"，而不是等用户投诉。</p></callout>

---

## 六、什么时候该上，什么时候是杀鸡用牛刀

Durable Execution 不是银弹，上它有意义也有成本（要跑一个 Temporal/Restate Server、要学一套编程模型）。我的判断框架：

```text
上 Durable Execution 的充要条件（满足任一）：
✅ 任务横跨多个外部系统 / 多步骤，且任一步可能失败需重试
✅ 任务耗时 > 几分钟，且不能接受"中途崩溃从头再来"
✅ 需要人工介入节点（审批/HITL），等待可能数小时
✅ 要求严格一次语义（发消息/扣款/写库不能重复）

不上（朴素状态机/异步队列足够）：
❌ 单次 LLM 调用（< 1 分钟，失败直接前端重试）
❌ 纯只读检索问答（无副作用，重试零成本）
❌ 团队还不会用、且任务简单（先无状态化再说）
```

<callout emoji="⚠️"><p>反模式：为了"显得专业"把每个 ChatClient 调用都包进 Temporal Workflow。结果 Server 资源被大量短任务耗尽，反而更慢。Durable 是为"长、多步、有副作用、需可恢复"的任务准备的，别滥用。</p></callout>

---

## 七、企业落地：对账 Agent 的救赎

某金融团队的对账 Agent，原版是朴素 `for` 循环：拉 A 系统 → 拉 B 系统 → 比对 → 写差异 → 推送审批。一次跑 12 分钟。生产问题：

```
问题1：凌晨跑批时 K8s 节点维护重启，任务中断，当天对账缺失，T+1 才发现
问题2：拉 B 系统偶发超时，重试把同一条记录写两遍，差异表出现重复行
```

改成 Temporal Workflow 后：

```
- 5 个步骤分别成 Activity，各自超时/重试独立配置
- "推送审批"前插入 WorkflowQueue 持久化等待，审批人白天才处理也不占资源
- 节点重启 → Temporal 从事件日志恢复，已完成的步骤不重跑
- Activity 全部按对账批次号幂等，重试不再重复写入
```

上线后"对账任务失败率"从约 8% 降到 0.1% 以内，且再没出现过"重复写入"。这就是 Durable Execution 的真实收益——不是炫技，是消灭一类反复出现的生产事故。

---

## 八、面试自测

1. **Durable Execution 解决什么？** —— 长任务中途崩溃可恢复、步骤幂等、长等待不烧资源。
2. **Workflow 为什么不能写 random/System.currentTimeMillis？** —— 必须确定性，重放才和第一次一致；不确定原语要用框架提供的。
3. **Workflow 和 Activity 区别？** —— Workflow 是确定性剧本（可重放），Activity 是重活（可重试/超时/幂等）。
4. **Temporal vs Restate？** —— 前者成熟重生态，后者轻量微服务友好；思想一致。
5. **何时不上？** —— 短、无副作用、团队未熟悉的简单任务，杀鸡别用牛刀。

---

## 九、小结

这一节我们把"长任务执行位置"这个最难的状态问题彻底解了：

1. **朴素重试扛不住**：步骤间无持久化边界 + 默认不幂等 → 崩溃重来、重复副作用、状态不一致。
2. **核心思想**：事件溯源（状态=事件流）+ 确定性重放（Workflow 必须确定）+ 检查点/Activity 幂等。
3. **Temporal 实战**：Workflow 是剧本（确定性编排 + `WorkflowQueue` 持久化等待），Activity 是重活（超时/重试/幂等）；崩溃后从事件日志恢复、已完步骤不重跑。
4. **Restate 实战**：`ctx.run` 保证只执行一次、`awakeable` 持久化等待外部信号，更轻量。
5. **与 Agent 结合**：把多步 Agent 任务整体包成 Durable Workflow，配合 lesson01 的无状态 API 层，形成"短问答无状态、长流程 Durable"的分层。
6. **取舍**：只为"长、多步、有副作用、需可恢复"的任务上 Durable，别滥用。

下一节（lesson03）我们聊另一个生产死穴——**沙箱与安全执行环境**：Agent 调工具、执行代码时，怎么把"执行权"关进安全的笼子。

---

## 延伸阅读 / 引用文档

1. Temporal Java SDK 官方文档：https://docs.temporal.io/java/
2. Temporal 核心概念（Workflow/Activity/Deterministic）：https://docs.temporal.io/workflows
3. Restate 官方文档（Java SDK）：https://docs.restate.dev/
4. Restate Awakeables（持久化等待外部信号）：https://docs.restate.dev/develop/awakeables
5. Microsoft Durable Task / Workflow 思想（通用背景）：https://github.com/microsoft/durabletask
6. Temporal 博客：为什么 AI Agent 需要 Durable Execution：https://temporal.io/blog/ai-agents

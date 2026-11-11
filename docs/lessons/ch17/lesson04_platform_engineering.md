# 第十七章第4节：Agent 平台工程：Prompt 管理、工作流编排、权限审计、运营监控

大家好，我是秋天。

前面三节，我们把一个 Agent 从 Demo 推到生产：运行时无状态化、长任务 Durable、执行进沙箱。但当你要管的不止一个 Agent，而是**十个、上百个 Agent，给多个团队用、给多个租户用**时，问题性质变了——这不是"写 Agent"的问题，是"运营 Agent 平台"的问题。

这一节我们升到平台层，讲 **Agent 平台工程** 的四根支柱：Prompt 管理、工作流编排、权限审计、运营监控。参考阿里 AgentCore、百度千帆、Dify 这些成熟平台的思路，但它不是讲它们怎么用，而是讲**它们背后为什么这么设计**——这样你既能用平台，也能自研平台的骨架。

这节课讲什么、学完掌握什么：

- 讲什么：① Prompt 管理（Prompt as Code、版本化、灰度、A/B）；② 工作流编排（可视化 DAG + 代码双轨）；③ 权限审计（租户隔离、操作留痕、合规）；④ 运营监控（成本/质量/健康度三大看板）。
- 学完掌握：你理解"平台工程 = 把 Agent 当产品运营，而不是当脚本写"；你能写出一个 Prompt 版本管理的 Java 服务骨架；你知道一个生产 Agent 平台必不可少的监控指标；你能在面试里讲清平台四支柱对应哪些 JD 要求。

<callout emoji="💡"><p>招聘映射：2026 年"Agent 平台工程师 / AI 平台研发"是独立的岗位线，JD 高频词："平台工程""Prompt 管理""工作流编排""权限审计""运营监控""多租户"。这是 Agent 方向里相对资深的岗位，也是从"写 Agent 的人"到"让别人能写 Agent 的人"的跃迁。V2 把平台工程单列为第十七章收尾，正是因为它代表 Agent 能力的高阶形态。</p></callout>

---

## 一、为什么需要"平台"：从脚本到产品

先看一个团队不做平台时的真实演化：

```text
阶段1：每个人手搓 Agent
   张三写客服 Agent，李四写对账 Agent，王五写报告 Agent
   → Prompt 散落在各人代码里，改一句要发版

阶段2：Prompt 开始乱
   张三改了客服 Prompt，没通知李四，结果共用组件行为变了
   → 没有版本、没有回滚、没有 owner

阶段3：线上出问题没人知道
   某个 Agent token 消耗暴涨、某次回复质量下滑
   → 没有监控、没有告警，用户投诉才发现

阶段4：安全合规找上门
   监管问"谁在什么时间调了什么工具、看到了什么数据"
   → 没有审计日志，答不上来
```

平台工程要解决的，本质就是这四个阶段暴露的痛点：**可维护性、可观测性、安全性、可运营性**。下面四节一一对应。

---

## 二、Prompt 管理：Prompt as Code

Prompt 不是"写在代码里的一行字符串"，它是 Agent 的"核心业务逻辑"，必须被**版本化、可回滚、可灰度、可 A/B**。

### 2.1 把 Prompt 外置 + 版本化（别硬编码）

错误示范：

```java
// ⚠️ Prompt 硬编码在代码里，改一句要重新发版
String prompt = "你是一个客服助手，请友好地回答用户问题。";
```

正确做法：Prompt 存到"Prompt 仓储"（数据库 / 专门的 Prompt 服务 / 配置中心 Nacos），用 `promptKey + version` 拉取：

```java
package com.example.ch17.platform;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt 仓储：按 key+version 管理，支持灰度与回滚。
 * 生产可替换为 Nacos / 自建 Prompt 服务 / LangSmith 类工具。
 */
public class PromptRepository {

    // key= promptKey, value= version -> 内容
    private final Map<String, Map<String, String>> store = new ConcurrentHashMap<>();

    public void publish(String key, String version, String content) {
        store.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(version, content);
    }

    public String get(String key, String version) {
        Map<String, String> versions = store.get(key);
        if (versions == null) throw new IllegalArgumentException("prompt not found: " + key);
        return versions.getOrDefault(version, versions.get("latest"));
    }

    /** 灰度：按 userId 哈希比例放新版本，其余走稳定版 */
    public String getWithCanary(String key, String stable, String canary, String userId, double ratio) {
        int h = Math.abs(userId.hashCode()) % 100;
        return h < (int) (ratio * 100) ? get(key, canary) : get(key, stable);
    }
}
```

### 2.2 在 Agent 里用版本化 Prompt

```java
package com.example.ch17.platform;

import org.springframework.ai.chat.client.ChatClient;

public class PromptDrivenAgent {

    private final ChatClient chatClient;
    private final PromptRepository prompts;

    public PromptDrivenAgent(ChatClient.Builder builder, PromptRepository prompts) {
        this.chatClient = builder.build();
        this.prompts = prompts;
    }

    public String answer(String userId, String message) {
        // 灰度取 Prompt：5% 用户走 canary 版，其余稳定版
        String system = prompts.getWithCanary(
                "customer_service", "v2", "v3-canary", userId, 0.05);
        return chatClient.prompt()
                .system(system)
                .user(message)
                .call()
                .content();
    }
}
```

<callout emoji="✅"><p>Prompt 管理三铁律：① 永不硬编码（外置到 Prompt 仓储）；② 每次变更有版本、可回滚；③ 上线走灰度/ A/B，用指标决定是否全量。这和传统"配置中心 + 灰度发布"是一脉相承的，只是管理对象从配置变成了 Prompt。</p></callout>

### 2.3 参考平台做法

```text
阿里 AgentCore / 百度千帆 / Dify 的 Prompt 管理共性：
- Prompt 模板可视化编辑 + 变量占位（{user_name} 等）
- 版本历史、一键回滚
- 在线调试（填变量即时预览输出）
- 与评测打通：新版本先跑评测集，达标才放量
```

这就是为什么第十六章（评测闭环）和这一节是绑定的——**没有评测，Prompt 灰度就是盲放**。

---

## 三、工作流编排：可视化 DAG + 代码双轨

当 Agent 不再是"一句话问答"，而是"多步骤流程"（检索→生成→审核→发布），你需要**工作流编排**。

### 3.1 为什么要编排而非硬写

```java
// ⚠️ 硬写流程：步骤耦合、难改、难观测、难复用
public void run() {
    var a = step1(); var b = step2(a); var c = step3(b);
    if (c.needHuman()) waitApprove(c); step4(c);
}
```

问题：流程改一下要改代码；每步耗时/成功率看不见；步骤不能复用给其他流程。

正解：把流程声明成 **DAG（有向无环图）**，用编排引擎驱动。Spring AI Alibaba 的 Graph 就是这种思路（第三章 lesson06）。

### 3.2 用 Spring AI Alibaba Graph 声明工作流（Java）

```java
package com.example.ch17.platform;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.GraphRepresentation;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.OverAllState;

import java.util.Map;

public class AgentWorkflowGraph {

    public CompiledGraph build() throws Exception {
        // 用 StateGraph 声明节点与边，而非硬写 if/else
        StateGraph graph = new StateGraph("customer-refund-flow")
                .addNode("classify", (NodeAction) this::classify)     // 分类
                .addNode("retrieve", (NodeAction) this::retrieve)     // 检索知识
                .addNode("draft",   (NodeAction) this::draft)         // 起草
                .addNode("humanReview", (NodeAction) this::humanReview) // 人工审核
                .addNode("reply",   (NodeAction) this::reply)         // 回复

                .addEdge("__start__", "classify")
                .addEdge("classify", "retrieve")
                .addEdge("retrieve", "draft")
                .addConditionalEdges("draft",
                        this::needHuman,             // 条件：是否需人工
                        Map.of("yes", "humanReview", "no", "reply"))
                .addEdge("humanReview", "reply")
                .addEdge("reply", "__end__");

        return graph.compile();
    }

    private Map<String, Object> classify(OverAllState s) { /* ... */ return Map.of(); }
    private Map<String, Object> retrieve(OverAllState s) { /* ... */ return Map.of(); }
    private Map<String, Object> draft(OverAllState s)    { /* ... */ return Map.of(); }
    private Map<String, Object> humanReview(OverAllState s){ /* ... */ return Map.of(); }
    private Map<String, Object> reply(OverAllState s)     { /* ... */ return Map.of(); }
    private String needHuman(OverAllState s) { return "no"; }
}
```

这种声明的价值：流程可视化（Dify 的画布本质就是 DAG 编辑器）、步骤可复用、每步可单独观测、可接入 Human-in-the-Loop（第四章）。

<callout emoji="💡"><p>"双轨"是我建议你采用的实践：核心稳定流程用可视化 DAG（产品和运营能看懂、能改），复杂分支逻辑用代码（NodeAction 里写 Java）。纯可视化容易表达能力不够，纯代码又失去可编排性——双轨取两者之长。</p></callout>

---

## 四、权限审计：租户隔离 + 操作留痕 + 合规

平台服务多团队/多租户，安全边界从"单 Agent"升到"平台级"。回望 lesson03 的沙箱与多租户隔离，在平台工程里固化为制度。

### 4.1 租户级隔离（平台底座）

```yaml
# 每个租户独立 K8s Namespace + 资源配额 + 网络策略
apiVersion: v1
kind: ResourceQuota
metadata:
  name: tenant-a-quota
  namespace: tenant-a
spec:
  hard:
    requests.cpu: "4"
    requests.memory: 8Gi
    pods: "10"
---
# 租户密钥独立空间，绝不共享
apiVersion: v1
kind: Secret
metadata:
  name: tenant-a-keys
  namespace: tenant-a
type: Opaque
# data: DASHSCOPE_API_KEY=...（从 KMS 注入，不落地明文）
```

### 4.2 操作审计：谁在什么时间做了什么

平台必须记录每个 Agent 的"决策与动作"，用于合规追溯与 badcase 复盘（对应第十二章）：

```java
package com.example.ch17.platform;

import java.time.Instant;

/** 平台级审计记录（脱敏后落库，防篡改、可追溯） */
public record PlatformAudit(
        String tenantId,
        String userId,
        String agentId,
        String action,          // 调了什么工具
        String paramsMasked,    // 参数（脱敏后）
        String resultMasked,    // 结果（脱敏后）
        boolean humanApproved,  // 是否经人工确认
        Instant ts
) {
    public static PlatformAudit of(String t, String u, String a, String act,
                                   String p, String r, boolean h) {
        return new PlatformAudit(t, u, a, act,
                Desensitizer.mask(p), Desensitizer.mask(r), h, Instant.now());
    }
}
```

审计数据要防篡改（只读存储 / 区块链存证可选），并保留足够时长满足合规（金融通常 5–10 年）。

<callout emoji="⚠️"><p>合规红线：监管问"某用户某时某 Agent 看了什么数据、调了什么工具"，你必须能答。没有审计 = 金融/政企场景直接否决。审计不是可选项，是合规门票。审计里的 PII 必须脱敏，否则你又制造了数据泄露点。</p></callout>

---

## 五、运营监控：成本 / 质量 / 健康度三大看板

平台上线后，运营靠三块看板活着：

### 5.1 成本看板（老板最关心）

```text
成本看板核心指标：
- token 消耗（按租户/按 Agent/按模型 拆分）
- 单次对话平均成本 / P99 成本
- 模型路由分布（贵模型 vs 便宜模型占比）
- 预算执行率（是否超月度预算，对应第十章成本治理）
```

```java
// 概念：每次调用记录成本，按维度聚合
record TokenCost(String tenantId, String agentId, String model,
                 long inputTokens, long outputTokens, double cost) {}
// 聚合后推送 Prometheus：agent_token_cost_total{tenant,agent,model}
```

### 5.2 质量看板（用户最关心）

```text
质量看板核心指标（结合第十六章评测）：
- 评测集通过率（离线，每次 Prompt/模型变更跑）
- 在线 badcase 率（用户点"不满意"/人工纠偏比例）
- LLM-as-Judge 评分（在线采样评分）
- 任务完成率（多步 Agent 跑到终点的比例）
```

### 5.3 健康度看板（运维最关心）

```text
健康度看板核心指标：
- Agent 成功率 / 失败率（突增=异常）
- P99 延迟（模型调用 + 工具调用）
- Durable 任务卡住数（lesson02 指标）
- sandbox_blocked_actions（突增=注入攻击，lesson03）
- 限流/熔断触发次数
```

```yaml
# Prometheus 抓取 Agent 平台指标示例
scrape_configs:
  - job_name: 'agent-platform'
    static_configs:
      - targets: ['agent-platform:8080']
# 关键告警规则：
# - rate(agent_failed_total[5m]) > 0.05  → 失败率>5% 告警
# - increase(sandbox_blocked_actions[10m]) > 50 → 疑似注入攻击
# - agent_token_cost_sum / 预算 > 0.9 → 预算临界告警
```

<callout emoji="✅"><p>三块看板对应三个角色：成本看板给老板（钱花哪了）、质量看板给用户（好不好用）、健康度看板给运维（稳不稳）。一个成熟的 Agent 平台，这三块缺一不可，且告警要能直接定位到"哪个租户、哪个 Agent、哪个步骤"。</p></callout>

---

## 六、平台整体架构：把四支柱串起来

一张图收尾，这是 Agent 平台工程的完整骨架（参考阿里 AgentCore / 百度千帆 / Dify 思路提炼）：

```text
┌──────────────────────────────────────────────────────────────┐
│                       Agent 平台工程                          │
│                                                              │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │ Prompt 管理  │  │ 工作流编排   │  │ 权限审计             │  │
│  │ as Code      │  │ DAG+代码双轨 │  │ 租户隔离+操作留痕    │  │
│  │ 版本/灰度/A-B│  │ Graph/HITL  │  │ +合规                │  │
│  └──────┬──────┘  └──────┬──────┘  └──────────┬──────────┘  │
│         │                │                      │             │
│         └────────────────┼──────────────────────┘             │
│                          ▼                                    │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ Agent 运行时（lesson01 无状态 + lesson02 Durable）      │   │
│  │ + 沙箱与安全边界（lesson03）                            │   │
│  └──────────────────────────────────────────────────────┘   │
│                          │                                    │
│                          ▼                                    │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ 运营监控：成本/质量/健康度 三大看板（OTel+Prom+Grafana）│  │
│  └──────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────┘
```

这张图把第十七章四节课全串起来了：lesson01 运行时是底座，lesson02 Durable 是长任务保障，lesson03 沙箱是安全边界，lesson04 平台工程是把它们"产品化运营"的四支柱。

---

## 六（续）、成本治理：预算拦截与模型路由（代码骨架）

成本看板之外，平台必须能**主动拦截**超预算行为，而不是事后看报表。下面是基于 Redis 的预算拦截骨架（对应第十章成本治理）：

```java
package com.example.ch17.platform;

import java.util.concurrent.TimeUnit;

/**
 * 平台级预算治理：按租户/模型累计 token 成本，超阈值拦截。
 */
public class CostGovernance {

    private final BudgetStore store;

    public CostGovernance(BudgetStore store) { this.store = store; }

    /** 调用前检查预算，返回是否放行 */
    public boolean allow(String tenantId, String model, double estimatedCost) {
        Budget b = store.getBudget(tenantId);
        double used = store.getUsed(tenantId);
        if (used + estimatedCost > b.monthlyLimit) {
            // 超预算：降级到便宜模型，或直接拦截
            if (b.policy == BudgetPolicy.DOWNGRADE_CHEAP) {
                return true; // 实际应改路由到便宜模型（见下）
            }
            return false; // 拦截
        }
        return true;
    }

    /** 模型路由：贵模型仅用于复杂任务，简单任务走便宜模型省成本 */
    public String routeModel(String tenantId, boolean complex) {
        if (!complex) return "qwen-turbo";     // 便宜
        if (store.getUsed(tenantId) > store.getBudget(tenantId).monthlyLimit * 0.8)
            return "qwen-turbo";                // 预算临界也走便宜
        return "qwen-max";                      // 贵但强
    }

    enum BudgetPolicy { BLOCK, DOWNGRADE_CHEAP }
    interface BudgetStore {
        Budget getBudget(String t);
        double getUsed(String t);
    }
    record Budget(double monthlyLimit, BudgetPolicy policy) {}
}
```

<callout emoji="✅"><p>成本治理三板斧：① 缓存（Prompt 缓存、相似问题复用，见第十五章上下文工程）；② 模型路由（简单任务便宜模型，复杂任务才上贵模型）；③ 预算拦截（超阈值降级/阻断）。这三招通常能把 Agent 推理成本砍掉 40%–70%，是平台运营的核心竞争力。</p></callout>

## 六（再续）、灰度 A/B：用指标决定放量

Prompt / 模型变更上线，要靠 A/B 数据决策，而不是拍脑袋。骨架：

```java
package com.example.ch17.platform;

import java.util.Map;

/** 灰度实验：对比两个版本在质量/成本上的表现，决定是否全量 */
public class CanaryDecision {

    public record Metric(double qualityScore, double avgCost, long sampleN) {}

    /** 当 canary 质量不显著低于 stable 且成本更低，或质量显著更高，则放量 */
    public boolean shouldPromote(Metric stable, Metric canary) {
        boolean qualityOk = canary.qualityScore >= stable.qualityScore - 0.02;
        boolean cheaper  = canary.avgCost <= stable.avgCost;
        boolean better   = canary.qualityScore > stable.qualityScore + 0.03;
        return (qualityOk && cheaper) || better;
    }

    // 指标来自第五节的质量/成本看板，定期跑评测集 + 在线采样得到
    public static void main(String[] a) {
        CanaryDecision d = new CanaryDecision();
        boolean promote = d.shouldPromote(
                new Metric(0.82, 0.012, 1000),
                new Metric(0.83, 0.009, 1000));
        System.out.println("是否全量放量: " + promote);
    }
}
```

## 六（补）、主流平台能力对比（选型参考）

```text
能力                Dify        阿里AgentCore   百度千帆    自建(本课)
─────────────────────────────────────────────────────────────────
Prompt 管理         强          强             强          自研(中)
可视化编排         强          中             强          中(Graph)
权限审计           中          强             强          强(可控)
多租户隔离         中          强             强          强(可控)
成本治理           中          强             强          自研(强)
私有模型接入       中          强             强          强
合规/数据不出域    弱          强             强          强
起步成本           低          中             中          高(人力)
```

<callout emoji="💡"><p>选型不是"哪个最好"，是"哪个最贴合你的约束"。金融政企数据不出域 → 自建或私有化平台；创业公司要快 → Dify 改造；已有阿里云栈 → AgentCore。理解每张表背后的"为什么"，比记住名字重要。</p></callout>

---

## 七、企业落地：自建 vs 用平台怎么选

很多同学会问"我到底要不要自建平台"。我的判断框架：

```text
用成熟平台（Dify / 阿里 AgentCore / 百度千帆 / 腾讯元器）：
✅ 业务早期、团队小、要快上线
✅ 标准场景（客服/知识库问答/工作流审批）
✅ 不想养平台研发团队

自建平台（基于本课程内容）：
✅ 强合规/数据不出域（金融、政企、军工）
✅ 业务深度定制（独特工作流、私有模型、特殊审计）
✅ 已有强大 Java 工程团队，要完全掌控

混合（最常见）：
✅ 用开源底座（如 Dify 改造 / LangChain4j + 自研编排）
✅ 自研差异化的权限审计与成本治理
```

<callout emoji="💡"><p>现实是大多数企业走"混合"：用开源/云平台的运行时，自研自己最在意的差异化能力（权限审计、成本治理、私有模型接入）。作为 Java 工程师，你能用 Spring AI Alibaba + Temporal + 沙箱 + 监控，搭出一个"够用、可控、合规"的轻量平台，这本身就是很强的交付能力。</p></callout>

---

## 八、面试自测

1. **平台四支柱？** —— Prompt 管理、工作流编排、权限审计、运营监控。
2. **Prompt 管理三铁律？** —— 不硬编码、有版本可回滚、灰度/ A-B 上线。
3. **为什么编排而非硬写？** —— 步骤可复用、可观测、可 HITL、可视化。
4. **审计为什么必须？** —— 合规门票，监管要能追溯"谁何时调了什么"。
5. **三大看板？** —— 成本（老板）、质量（用户）、健康度（运维）。

---

## 九、小结

这一节我们把单个 Agent 升维成"Agent 平台"，四根支柱：

1. **Prompt 管理**：Prompt as Code，外置 + 版本化 + 灰度/A-B，永硬编码；与第十六章评测闭环绑定。
2. **工作流编排**：把流程声明成 DAG（Spring AI Alibaba Graph），可视化 + 代码双轨，支持 HITL 与单步观测。
3. **权限审计**：租户级隔离（Namespace/配额/密钥独立）+ 操作留痕（脱敏、防篡改、可合规追溯）。
4. **运营监控**：成本（老板）、质量（用户）、健康度（运维）三大看板，告警直接定位到租户/Agent/步骤。
5. **整体架构**：四支柱建在 lesson01 运行时 + lesson02 Durable + lesson03 沙箱之上，把 Agent "产品化运营"。
6. **选型**：早期/标准场景用平台，强合规/深度定制自建，多数走"开源底座 + 自研差异化"混合。

到这一节，第十七章四节课完整闭合——你已经有能力把 Agent 从 Demo 一路交付成可运营的生产平台。第十八章我们聊最后一个加分项：模型微调与推理优化。

---

## 延伸阅读 / 引用文档

1. Spring AI Alibaba Graph 工作流编排文档：https://java2ai.com/docs/1.0.0.2/
2. 阿里云 AgentCore（平台工程参考）：https://www.aliyun.com/product/bailian/agentcore
3. 百度智能云千帆 Agent 平台：https://cloud.baidu.com/product/qianfan.html
4. Dify 开源 Agent 平台（编排/Prompt 管理参考）：https://docs.dify.ai/
5. OpenTelemetry 官方文档（Agent 可观测底座）：https://opentelemetry.io/docs/
6. Prometheus 告警规则文档：https://prometheus.io/docs/prometheus/latest/configuration/alerting_rules/

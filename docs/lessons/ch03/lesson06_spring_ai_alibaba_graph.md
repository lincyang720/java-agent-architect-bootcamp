# 第三章第6节：【新增】Spring AI Alibaba：Graph 工作流编排与 DashScope 集成

大家好，我是秋天。

前面五节我们都在用 Spring AI 原生的 `ChatClient` 玩单智能体。但真实企业的 Agent，很少是"一句话进、一句话出"那么简单——它往往是**多步工作流**：先检索知识，再让模型分析，然后调工具，最后把结果写回。这种有向图式的编排，Spring AI 原生的 `ChatClient` 表达力就不够了。

这一节我们引入 **Spring AI Alibaba** 的两大核心：**DashScope（通义千问）模型接入** 和 **Graph 工作流编排框架**。这是阿里云基于 Spring AI 标准做的扩展，也是目前 Java 生态里最成熟的 Agent 编排方案之一。

这节课讲什么、学完掌握什么：

- 讲什么：Spring AI Alibaba 的依赖与 BOM（`1.0.0.2`）、用 DashScope 跑通 `ChatClient`；Graph 的核心抽象 `StateGraph` / `NodeAction` / `CompiledGraph`、节点（Node）与边（Edge）的编写、同步 `invoke` 与 `RunnableConfig`。
- 学完掌握：你能用 DashScope 跑起一个 Spring AI 应用；你能用 `StateGraph` 拼一张"START → 节点A → 节点B → END"的工作流，并在节点里读写共享状态 `OverAllState`；你理解 Graph 和单纯 `ChatClient` 调用的本质区别。

<callout emoji="💡"><p>招聘映射：JD 高频出现"熟悉 Spring AI Alibaba / Graph 工作流 / 通义千问 DashScope / 百炼平台"。阿里系、政企、金融客户尤其看重这套栈。会 Graph 编排，意味着你能独立交付"多步骤 Agent 应用"，而不仅是聊天 Demo。</p></callout>

---

## 一、为什么需要 Graph：从线性调用到图编排

先用一张图看清差异：

```
【纯 ChatClient】                      【Graph 工作流】
用户 ─▶ ChatClient ─▶ 模型 ─▶ 回答       START ─▶[检索节点]─▶[分析节点]─▶[工具节点]─▶ END
                                        ↑________ 条件边回退 ________↓

特点：一条直线，模型一次推理            特点：有向图，节点可循环/分支/并行，
                                         状态在节点间传递，适合复杂业务流
```

什么时候该上 Graph？
- 步骤多于 2 步，且步骤间有数据依赖（上一步输出是下一步输入）。
- 需要**条件分支**（分析完决定是走"人工审核"还是"自动通过"）。
- 需要**循环**（如 ReAct 的"思考-行动-观察"反复迭代，见 ch04 lesson06）。
- 需要**状态持久化**（长任务断点续跑，配合 `RunnableConfig.threadId`）。

如果只是"问一句答一句"，`ChatClient` 足够，别为了用 Graph 而用 Graph。

---

## 二、工程地基：依赖与 BOM

Spring AI Alibaba 用 `spring-ai-alibaba-bom` 统一版本管理。当前 GA 版是 `1.0.0.2`。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.5</version>
        <relativePath/>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>ch03-alibaba-graph</artifactId>
    <version>1.0.0</version>

    <properties>
        <java.version>17</java.version>
        <spring-ai-alibaba.version>1.0.0.2</spring-ai-alibaba.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <!-- DashScope 模型接入（通义千问） -->
        <dependency>
            <groupId>com.alibaba.cloud.ai</groupId>
            <artifactId>spring-ai-alibaba-starter-dashscope</artifactId>
        </dependency>
        <!-- Graph 工作流编排核心 -->
        <dependency>
            <groupId>com.alibaba.cloud.ai</groupId>
            <artifactId>spring-ai-alibaba-graph-core</artifactId>
        </dependency>
    </dependencies>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>com.alibaba.cloud.ai</groupId>
                <artifactId>spring-ai-alibaba-bom</artifactId>
                <version>${spring-ai-alibaba.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

配置文件 `application.yml`，密钥走环境变量：

```yaml
server:
  port: 8080
spring:
  application:
    name: ch03-alibaba-graph
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}
      chat:
        options:
          model: qwen-plus   # 或 qwen-max / qwen-turbo
```

<callout emoji="⚠️"><p>版本坑：`spring-ai-alibaba-graph-core` 和 `spring-ai-alibaba-starter-dashscope` 的版本必须跟 BOM 一致（都来自 `1.0.0.2`）。混用 M 版（如 `1.0.0-M6`）会导致 `StateGraph` API 签名对不上，编译直接挂。这是新手第一坑。</p></callout>

---

## 三、用 DashScope 跑通 ChatClient

好消息：Spring AI Alibaba 完全兼容 Spring AI 的 `ChatClient` API，只是底层的 `ChatModel` 换成了 `DashScopeChatModel`。你的 lesson02~05 代码几乎一行不改，只换依赖和配置。

```java
package com.example.ch03.graph;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DashScopeController {

    private final ChatClient chatClient;

    public DashScopeController(ChatClient.Builder builder) {
        this.chatClient = builder
                .defaultSystem("你是一名严谨的 Java 架构师。")
                .build();
    }

    @GetMapping("/ask")
    public String ask(@RequestParam String q) {
        return chatClient.prompt().user(q).call().content();
    }
}
```

注入的就是 `DashScopeChatModel`，由 `spring-ai-alibaba-starter-dashscope` 自动配置。这样 lesson02~05 的所有能力（提示词、结构化输出、工具调用）在通义千问上原样可用。

<callout emoji="✅"><p>这说明一个重要的设计哲学：Spring AI Alibaba 不是另起炉灶，而是"在 Spring AI 标准之上做扩展"。底层 `ChatClient`/`ChatModel` 接口一致，上层多了 Graph、Prompt 管理、Nacos MCP 等 Alibaba 生态能力。学习成本很低。</p></callout>

---

## 四、Graph 核心概念一：StateGraph 与节点

Graph 的本质是一张**有向图**，由"节点（Node）"和"边（Edge）"组成，节点间传递一份**共享状态（OverAllState）**。

先看一个最简工作流：`START → A → END`，节点 A 读取输入、加工、写回状态。

```java
package com.example.ch03.graph;

import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.GraphStateException;
import com.alibaba.cloud.ai.graph.RunnableConfig;

import java.util.Map;
import java.util.Optional;

public class FirstGraph {

    // 节点实现 NodeAction 接口：输入 OverAllState，返回要写入的键值对
    static class NodeA implements NodeAction {
        @Override
        public Map<String, Object> apply(OverAllState state) throws Exception {
            // 读取名为 "input" 的状态（带默认值）
            String input = state.value("input", "").toString();
            // 业务加工：这里简单加前缀，真实场景可调用 ChatClient
            String output = "已处理: " + input;
            // 返回要合并进全局状态的增量
            return Map.of("output", output);
        }
    }

    public static void main(String[] args) throws GraphStateException {
        // KeyStrategyFactory：定义状态里有哪些 key（这里空实现演示）
        var keyStrategyFactory = (com.alibaba.cloud.ai.graph.KeyStrategyFactory)
                () -> Map.of();

        StateGraph graph = new StateGraph("firstGraph", keyStrategyFactory);
        graph.addNode("A", com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async(new NodeA()));
        graph.addEdge(StateGraph.START, "A");
        graph.addEdge("A", StateGraph.END);

        CompiledGraph compiled = graph.compile();

        // 同步执行，传入初始状态 + 运行配置（threadId 用于状态隔离/持久化）
        Optional<OverAllState> result = compiled.invoke(
                Map.of("input", "请分析2024年AI行业趋势"),
                RunnableConfig.builder().threadId("task-001").build());

        result.ifPresent(s -> {
            System.out.println("输入: " + s.value("input").orElse("无"));
            System.out.println("输出: " + s.value("output").orElse("无"));
        });
    }
}
```

拆开讲四个关键角色：
- **`StateGraph`**：图的构建器，你往里加节点和边。
- **`NodeAction`**：节点逻辑接口，`apply(OverAllState)` 返回 `Map`——这个 Map 会被合并进全局状态。
- **`OverAllState`**：贯穿整张图的共享状态字典，`state.value("key", default)` 读，`返回 Map` 写。
- **`CompiledGraph`**：`compile()` 后的可执行图，`invoke(input, config)` 同步跑。

<callout emoji="💡"><p>和 `ChatClient` 的本质区别：Graph 让"多个步骤"之间有了**显式的状态传递**和**可编排的流转**。每个节点是无副作用（或可控副作用）的纯函数式加工，状态集中管理，便于调试、持久化、回放——这正是企业级工作流要的确定性。</p></callout>

---

## 五、Graph 核心概念二：边与条件分支

边决定"下一步去哪"。除了普通边，还有**条件边（Conditional Edge）**——根据节点返回或状态值动态路由，这是实现"判断/循环"的基石。

```java
package com.example.ch03.graph;

import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.alibaba.cloud.ai.graph.GraphStateException;

import java.util.Map;
import java.util.Optional;

public class BranchGraph {

    // 分析节点：判断内容风险等级，写入 state 的 "risk"
    static class AnalyzeNode implements NodeAction {
        @Override
        public Map<String, Object> apply(OverAllState state) {
            String text = state.value("input", "").toString();
            String risk = text.contains("转账") ? "HIGH" : "LOW";
            return Map.of("risk", risk);
        }
    }

    // 人工审核节点
    static class HumanReviewNode implements NodeAction {
        @Override
        public Map<String, Object> apply(OverAllState state) {
            return Map.of("decision", "转人工审核");
        }
    }

    // 自动通过节点
    static class AutoPassNode implements NodeAction {
        @Override
        public Map<String, Object> apply(OverAllState state) {
            return Map.of("decision", "自动通过");
        }
    }

    // 路由函数：读 state.risk，返回目标节点名
    static class RiskRouter implements EdgeAction {
        @Override
        public String apply(OverAllState state) {
            String risk = state.value("risk", "LOW").toString();
            return "HIGH".equals(risk) ? "human_review" : "auto_pass";
        }
    }

    public static void main(String[] args) throws GraphStateException {
        var ksf = (com.alibaba.cloud.ai.graph.KeyStrategyFactory) () -> Map.of();
        StateGraph g = new StateGraph("riskGraph", ksf);
        g.addNode("analyze", com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async(new AnalyzeNode()));
        g.addNode("human_review", com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async(new HumanReviewNode()));
        g.addNode("auto_pass", com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async(new AutoPassNode()));

        g.addEdge(StateGraph.START, "analyze");
        // 条件边：从 analyze 出来，按 RiskRouter 的返回值路由
        g.addConditionalEdges("analyze",
                com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async(new RiskRouter()),
                Map.of("human_review", "human_review", "auto_pass", "auto_pass"));
        g.addEdge("human_review", StateGraph.END);
        g.addEdge("auto_pass", StateGraph.END);

        CompiledGraph cg = g.compile();
        Optional<OverAllState> r = cg.invoke(Map.of("input", "请帮我转账5000元"),
                com.alibaba.cloud.ai.graph.RunnableConfig.builder().threadId("t1").build());
        r.ifPresent(s -> System.out.println("决策: " + s.value("decision").orElse("?")));
    }
}
```

这里 `addConditionalEdges` 接受一个 `EdgeAction`，它读状态返回目标节点名，再靠 `Map` 把返回值映射到真实节点。这就是"if/else 流转"在 Graph 里的表达。第四章我们会用这个机制实现 Supervisor 路由、循环 Agent。

---

## 六、在节点里接入大模型（真实 Agent 节点）

前面的节点是纯 Java 逻辑，真实 Agent 节点往往要调 `ChatClient`。把 `ChatModel`/`ChatClient` 注入节点即可：

```java
package com.example.ch03.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Map;

public class LlmAnalyzeNode implements NodeAction {

    private final ChatClient chatClient;

    public LlmAnalyzeNode(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        String doc = state.value("input", "").toString();
        String summary = chatClient.prompt()
                .system("你是一名文档分析专家，输出不超过50字摘要。")
                .user("摘要以下文档：\n" + doc)
                .call()
                .content();
        return Map.of("summary", summary);
    }
}
```

这样每个节点就是"一次 LLM 调用 + 状态写入"，多个节点串起来就是多步 Agent。配合 lesson05 的工具调用，节点里还能让模型自动调工具——这就是"Graph 中的 ReAct Agent"（第四章 lesson06）。

---

## 七、企业场景：客诉处理的 Graph 编排

真实客诉处理不是一句话能搞定的，典型流程：

```
START ─▶[分类节点]─▶[高风险?]─条件─▶[人工审核节点]─▶ END
                       │LOW
                       ▼
                   [自动回复节点]─▶ END
```

用 Graph 表达：

```java
// 伪代码骨架（完整可运行版见配套 code 模块 ch03-spring-ai）
StateGraph g = new StateGraph("complaint", ksf);
g.addNode("classify", node_async(new ClassifyNode(chatClient)));
g.addNode("human_review", node_async(new HumanReviewNode()));
g.addNode("auto_reply", node_async(new AutoReplyNode(chatClient)));
g.addEdge(START, "classify");
g.addConditionalEdges("classify", edge_async(new RiskRouter()),
        Map.of("human_review", "human_review", "auto_reply", "auto_reply"));
g.addEdge("human_review", END);
g.addEdge("auto_reply", END);
```

分类节点用 `ChatClient` + 结构化输出（lesson04）给出 `category` 和 `risk`，路由节点读 `risk` 决定走人工还是自动。这种"确定性流程 + 模型决策点"的组合，是企业落地最稳的 Agent 形态——既利用了 LLM 的理解力，又用 Graph 兜住了流程边界，不会出现模型"自由发挥跑偏"。

<callout emoji="💡"><p>设计哲学：企业落地别迷信"全自主 Agent"。把"流程骨架"用 Graph 写死（哪些步骤、什么顺序），把"每个步骤里的判断/生成"交给模型。这种"骨架确定 + 节点智能"的混合架构，比纯自主 Agent 可控、可审计、可回滚，是政企客户最买单的形态。</p></callout>

---

## 九、Graph 的持久化与断点续跑

长任务（如"生成 50 页报告"）可能跑几分钟甚至更久，中途进程重启不能从头来。Graph 用 `RunnableConfig.threadId` + 状态存储支持断点续跑。Spring AI Alibaba 允许你接入自定义 `StateRepository` 把状态落库（Redis/DB）。

```java
package com.example.ch03.graph;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;

import java.util.Map;
import java.util.Optional;

public class PersistenceDemo {

    public static void resume(CompiledGraph g, String threadId, Map<String,Object> resumeInput) {
        // 用相同 threadId 恢复，框架从持久化状态继续，而非从头
        Optional<OverAllState> r = g.invoke(
                resumeInput,
                RunnableConfig.builder().threadId(threadId).build());
        r.ifPresent(s -> System.out.println(s.value("summary").orElse("")));
    }
}
```

<callout emoji="💡"><p>这与第十七章"Durable Execution（Temporal/Restate）"是同一诉求：长任务要可恢复、可观测、可重试。Graph 的状态机制是轻量级实现，超长跨天任务再上 Temporal 这类专业框架。选型看任务时长与可靠性要求。</p></callout>

---

## 十、条件边进阶：多分支与默认路由

真实业务不止二分支。比如客诉可分"物流/质量/退换/其他"四类，每类走不同节点。条件边的 `Map` 支持任意多映射，并建议设默认分支防"路由到不存在的节点"：

```java
g.addConditionalEdges("classify", edge_async(new MultiRouter()),
        Map.of(
            "logistics", "logistics_node",
            "quality",   "quality_node",
            "return",    "return_node",
            "other",     "general_node"));   // 兜底，避免悬空
```

若 `EdgeAction` 返回的值不在 Map 的 key 里，Graph 会报错或卡住——所以务必覆盖所有已知分支，并设一个 `default` 兜底节点。

---

## 十一、Graph vs 直接写代码编排

有人问："我直接写 Java 代码 if/else 调 ChatClient 不行吗？"可以，但有代价：

| 维度 | 手写代码编排 | Graph |
|------|------------|-------|
| 可视化 | 藏在代码逻辑里 | 结构即图，易画易懂 |
| 状态管理 | 自己维护 Map | OverAllState + 通道归约 |
| 持久化/恢复 | 自己实现 | 内置 threadId + StateRepository |
| 调试 | 断点 + 日志 | 节点级 trace + 状态快照 |
| 动态性 | 改代码 | 改图结构配置 |

结论：简单流程手写 OK；流程复杂、要可观测可恢复、要非研发也能看懂，用 Graph。

---

## 十二、面试自测

1. **Graph 四大件？** —— StateGraph（构建）、NodeAction（节点）、OverAllState（共享状态）、CompiledGraph（执行）。
2. **条件边干嘛用？** —— 按状态动态路由，实现分支/循环；循环=目标指回自身（需上限）。
3. **为什么要通道归约？** —— 防止多节点写同一 key 时覆盖丢数据。
4. **Graph 和 ChatClient 关系？** —— ChatClient 在节点里被调用，Graph 负责编排多个节点。

---

## 十四、Graph 子图的输入/输出契约

lesson06 提了子图（subgraph），这里补父子图之间的"状态契约"——子图只读取父图传入的特定 key，只写回特定 key，避免状态污染。

```java
// 父图调用子图时，显式传输入、取输出
OverAllState subResult = subGraph.invoke(
        Map.of("input", data),          // 父→子：只传需要的 key
        RunnableConfig.builder().build()).orElseThrow();
String output = subResult.value("output", "").toString();  // 子→父：只取约定 key
```

契约原则：
- **最小传入**：只传子图需要的 key，不多传（防子图误改父图状态）。
- **最小传出**：子图只写约定输出 key，不回写无关状态。
- **命名空间**：复杂系统用前缀（如 `sub_a_*`）隔离子图状态，防 key 冲突。

<callout emoji="✅"><p>经验：父图/子图状态污染是 Graph 复杂化的主因。把"状态契约"当 API 接口一样设计——明确输入输出，其余内部状态不外泄。这是 Graph 可维护的关键。</p></callout>

---

## 十五、Graph 的条件边与 Loop 的终止保障

lesson06 讲了条件边做循环，这里强调终止保障的"双保险"写法（生产必加）：

```java
static class SafeLoopRouter implements com.alibaba.cloud.ai.graph.action.EdgeAction {
    public String apply(OverAllState s) {
        boolean done = (Boolean) s.value("done", false);
        int iters = (Integer) s.value("iterations", 0) + 1;
        s.value("iterations", iters);   // 记迭代次数
        if (done) return "exit";
        if (iters >= 5) return "exit";  // 安全阀：到第5次强制退出
        return "loop";
    }
}
```

即便 `done` 永远为 false（模型判断失误），`iters >= 5` 也强制退出。两个条件任一满足即停——这是 Loop 不出事故的底线。

---

## 十六、Graph 在 Spring AI Alibaba 生态的位置

Graph 是 Spring AI Alibaba 的"编排层"，它和 DashScope(模型)、Nacos(MCP)、百炼(RAG) 构成完整栈：

```
┌─────────────── Spring AI Alibaba 栈 ───────────────┐
│  Graph (编排层：节点/边/状态)                        │
│     │ 节点内调用                                     │
│     ▼                                              │
│  ChatClient / DashScopeChatModel (模型层)           │
│     │ 工具/检索                                      │
│     ▼                                              │
│  Nacos MCP / 百炼 RAG / 工具 (能力层)               │
└──────────────────────────────────────────────────┘
```

学完 Graph，你就握住了"把模型能力编排成生产工作流"的钥匙——这是阿里系 Agent 落地的主战力。

---

## 十七、小结

这一节我们上了 Spring AI Alibaba 的车：

1. Spring AI Alibaba 是"在 Spring AI 标准之上扩展"，`ChatClient` API 完全一致，只换 `DashScopeChatModel` 和依赖。
2. 依赖：`spring-ai-alibaba-bom:1.0.0.2` + `starter-dashscope` + `graph-core`；密钥走 `DASHSCOPE_API_KEY` 环境变量。
3. Graph 四大件：`StateGraph`（构建）、`NodeAction`（节点逻辑，读写 `OverAllState`）、`OverAllState`（共享状态）、`CompiledGraph`（编译后 `invoke` 执行）。
4. 边有普通边 `addEdge` 和条件边 `addConditionalEdges(EdgeAction)`；条件边是分支/循环的基石。
5. 节点里可注入 `ChatClient`，把 LLM 调用和工具调用包进节点，组合成多步 Agent。
6. 企业落地用"骨架确定 + 节点智能"的混合架构（客诉处理 Graph），可控、可审计。

下一节（lesson07）我们在这个基础上，看 Spring AI Alibaba 的**企业级能力**：Prompt 管理、RAG 知识库、可观测性、以及和 Nacos / Higress 的集成——这才是它区别于"玩具框架"的地方。

---

## 延伸阅读 / 引用文档

1. Spring AI Alibaba 官方文档（Graph）：https://java2ai.com/docs/1.0.0.2/tutorials/graph/
2. Spring AI Alibaba 快速开始：https://java2ai.com/docs/1.0.0.2/tutorials/quick-start/
3. Spring AI Alibaba GitHub 仓库：https://github.com/alibaba/spring-ai-alibaba
4. Spring AI Alibaba 1.0 GA 发布说明：https://developer.aliyun.com/article/1666891
5. 通义千问 DashScope 接入文档：https://help.aliyun.com/zh/model-studio/spring-ai-alibaba-integrate-llm-application
6. LangGraph（Graph 编排思想源头，Python）：https://langchain-ai.github.io/langgraph/

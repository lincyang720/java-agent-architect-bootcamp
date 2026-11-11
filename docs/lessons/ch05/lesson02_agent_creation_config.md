# 第五章第2节：Agent 的创建与配置——基础 Agent、子 Agent、并行 Agent

大家好，我是秋天。

上节课我们把 ADK 的全局架构和六个核心概念过了一遍，跑通了第一个 `LlmAgent`。这节课我们往深里走一步：**Agent 到底有哪几种形态，怎么创建、怎么配置、什么时候该用哪一种。**

学完这节课，你要能答出三件事：

1. 基础 Agent（单 Agent）怎么配指令、模型、工具、输出格式；
2. 子 Agent（Sub-Agent）怎么挂到父 Agent 上，父子之间怎么传话、怎么切换；
3. 并行 Agent（ParallelAgent）怎么把多个独立任务并发跑完再汇总。

这节课是"Agent 组装"的基本功，后面第三节的循环编排、第四节的工具、第五节的部署全都建立在这之上。

---

## 一、基础 Agent：三要素与进阶配置

最朴素的 Agent 就是 `LlmAgent`，三个必填项：**name（名字）、instruction（指令）、model（模型）**。但生产环境远不止这三样，你还得管工具、输出格式、生成参数、安全护栏。

### 1.1 最小基础 Agent 与完整配置对比

```java
package com.camp.adk;

import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.Annotations.AdkTool;
import com.google.adk.tools.FunctionTool;

/**
 * 一个"生产级"基础 Agent 的完整配置样板。
 * 注：工具方法用 @AdkTool 标注，框架会自动抽取 schema 注册给模型。
 */
public class BasicAgentDemo {

    // 自定义工具：查天气（这里用假数据演示，真实接入气象 API）
    @AdkTool("根据城市名查询当前天气")
    public String getWeather(@AdkTool.Param("城市名称，例如 北京") String city) {
        // 真实场景这里调用气象服务；demo 返回固定结构
        return String.format("{\"city\":\"%s\",\"temp\":22,\"condition\":\"晴\"}", city);
    }

    public LlmAgent buildAgent() {
        return LlmAgent.builder()
                .name("weather_agent")
                .instruction("""
                        你是一个严谨的天气助手。
                        规则：
                        1. 如果用户问天气，必须调用 getWeather 工具，禁止编造数据。
                        2. 回答用中文，先给结论再给数据。
                        3. 如果用户没说城市，先反问城市。
                        """)
                .model("gemini-2.0-flash")
                // 工具：把带 @AdkTool 注解的方法包成 FunctionTool
                .tools(new FunctionTool(this::getWeather))
                // 生成参数：温度低一点更稳，topP 控制多样性
                .generationConfig(config -> config
                        .temperature(0.2)
                        .topP(0.8)
                        .maxOutputTokens(1024))
                .build();
    }
}
```

把这段配置拆开，重点说四个生产要点：

1. **instruction 要写"规则"，不是写"人设"**。很多同学把 instruction 写成"你是一个乐于助人的助手"，这是废话。真正有用的是约束条件——"必须调工具、禁止编造、先结论后数据"。模型靠这些硬规则收敛行为。
2. **工具用 `@AdkTool` 标注 + `FunctionTool` 包装**。`@AdkTool` 上的描述是给模型看的"工具说明书"，越具体，模型越会用。
3. **generationConfig 控制"性格"**。客服要稳（低 temperature），创意要发散（高 temperature）。这不是玄学，是工程质量。
4. **工具参数用 `@AdkTool.Param` 标注说明**，这是给模型理解参数语义用的。

<callout emoji="💡"><p>为什么 ADK 用注解而不是 JSON 描述工具？因为注解让工具定义和 Java 代码同处一地，编译期就能发现参数类型错误，而且不用手写 OpenAPI schema。这是 ADK "code-first" 哲学在工具层的体现。</p></callout>

### 1.2 结构化输出：让 Agent 吐出可解析对象

企业里 Agent 的输出经常要进系统（下单、建工单），你要的是 JSON 而不是散文。ADK 支持声明输出结构：

```java
package com.camp.adk;

import com.google.adk.agents.LlmAgent;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;

import java.util.Map;

/**
 * 结构化输出：要求模型按给定 JSON Schema 返回。
 */
public class StructuredOutputDemo {

    public LlmAgent buildAgent() {
        Schema orderSchema = Schema.newBuilder()
                .type(Type.OBJECT)
                .properties(Map.of(
                        "city", Schema.newBuilder().type(Type.STRING).build(),
                        "needUmbrella", Schema.newBuilder().type(Type.BOOLEAN).build(),
                        "advice", Schema.newBuilder().type(Type.STRING).build()
                ))
                .required("city", "needUmbrella", "advice")
                .build();

        return LlmAgent.builder()
                .name("weather_advisor")
                .instruction("分析天气并给出是否带伞的建议，严格按输出结构返回。")
                .model("gemini-2.0-flash")
                .outputSchema(orderSchema)   // 关键：约束输出结构
                .build();
    }
}
```

<callout emoji="✅"><p>招聘映射：JD 里常写"熟悉 LLM 结构化输出 / 函数调用"。这里 `outputSchema` 就是结构化输出，和 Spring AI 的 `StructuredOutputConverter`、LangChain4j 的 `AiServices` 返回 POJO 是同一个能力，只是 ADK 用 Gemini 原生 JSON mode。面试时能横向对比三者，是加分项。</p></callout>

---

## 二、子 Agent：父子结构与意图路由

真实业务很少一个 Agent 包打天下。上节课画的"客服分层"就是典型：**一个 Root Agent 当总控，下面挂多个专家子 Agent**。ADK 里用 `subAgents` 声明。

### 2.1 子 Agent 的基本写法

```java
package com.camp.adk;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.BaseAgent;

import java.util.List;

/**
 * 父 Agent（Root）+ 两个子 Agent（订单 / 政策）。
 * Root 根据用户意图，把任务转交给对应子 Agent。
 */
public class SubAgentDemo {

    // 子 Agent 1：订单查询
    private LlmAgent orderAgent() {
        return LlmAgent.builder()
                .name("order_agent")
                .instruction("你负责查询和处理订单。只能回答订单相关问题，其它一律说'请咨询对应专员'。")
                .model("gemini-2.0-flash")
                .build();
    }

    // 子 Agent 2：政策问答
    private LlmAgent policyAgent() {
        return LlmAgent.builder()
                .name("policy_agent")
                .instruction("你负责退换货政策问答。只能基于政策知识回答，不要编造。")
                .model("gemini-2.0-flash")
                .build();
    }

    // 父 Agent：根据意图分发
    public LlmAgent rootAgent() {
        return LlmAgent.builder()
                .name("root_agent")
                .instruction("""
                        你是客服总控。根据用户问题，把任务转交给对应子 Agent：
                        - 涉及订单、物流、发货 → 转 order_agent
                        - 涉及退换货、保修政策 → 转 policy_agent
                        不要自己回答专业问题，必须委派。
                        """)
                .model("gemini-2.0-flash")
                .subAgents(List.of(orderAgent(), policyAgent()))  // 关键：挂子 Agent
                .build();
    }
}
```

这里有个关键认知：**子 Agent 不是"被调用的方法"，而是"被委派的控制权"**。当 Root 判断该转交时，ADK 的 Flow 会把后续推理交给子 Agent，子 Agent 跑完再把结果交回 Root。整个过程对话历史共享同一个 Session。

```text
用户："我的订单到哪了？"
        │
        ▼
   [root_agent] 判断 → 意图=订单 → 委派
        │
        ▼
   [order_agent] 接管，调用查单工具，生成回答
        │
        ▼
   [root_agent] 收回控制权，把答案呈现给用户
```

<callout emoji="⚠️"><p>常见坑：子 Agent 的 instruction 一定要写"边界"——"只能回答 XX，其它一律转交"。否则子 Agent 容易越界乱答，或者 Root 一直不委派。指令的边界感决定了多 Agent 系统的稳定性。</p></callout>

### 2.2 子 Agent 之间怎么共享状态

父子和子 Agent 之间靠 `Session` 共享上下文，但如果你想显式传递变量（比如"当前订单号"），ADK 提供会话状态（session state）。在指令里用占位符引用：

```java
LlmAgent.builder()
    .name("order_agent")
    .instruction("当前用户正在查询订单 {order_id}，请基于该订单号调用工具。")
    .model("gemini-2.0-flash")
    .build();
```

运行时通过 `InvocationContext` 把 `order_id` 写进 session state，指令里的 `{order_id}` 会被自动替换。这让父子之间能"带参数交接"，而不是只能靠自然语言传话。

<callout emoji="💡"><p>为什么需要显式状态而不是全靠对话？因为自然语言传参会丢失精度（"订单号 12345" 模型可能记成 "12354"）。订单号、用户 ID 这类结构化信息，必须走 session state 而非自然语言，这是企业级多 Agent 的硬要求。</p></callout>

---

## 三、并行 Agent：ParallelAgent 并发执行

有些任务天然独立，没必要串行等。比如"同时总结三份文档""同时查天气、查汇率、查股价"。ADK 用 `ParallelAgent` 把多个子 Agent 并发跑，全部完成后汇总。

### 3.1 ParallelAgent 写法

```java
package com.camp.adk;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.parallel.ParallelAgent;

import java.util.List;

/**
 * 并行 Agent：同时执行多个独立子 Agent，全部完成后汇总。
 */
public class ParallelAgentDemo {

    private LlmAgent weatherAgent() {
        return LlmAgent.builder()
                .name("p_weather")
                .instruction("查询北京今天天气，一句话输出。")
                .model("gemini-2.0-flash")
                .build();
    }

    private LlmAgent exchangeAgent() {
        return LlmAgent.builder()
                .name("p_exchange")
                .instruction("查询美元兑人民币汇率，一句话输出。")
                .model("gemini-2.0-flash")
                .build();
    }

    private LlmAgent stockAgent() {
        return LlmAgent.builder()
                .name("p_stock")
                .instruction("查询腾讯控股今日股价，一句话输出。")
                .model("gemini-2.0-flash")
                .build();
    }

    public ParallelAgent build() {
        return ParallelAgent.builder()
                .name("morning_brief")
                .subAgents(List.of(weatherAgent(), exchangeAgent(), stockAgent()))
                .build();
    }
}
```

运行机制：

```text
              ┌─────────────────────────────┐
  触发 ──────▶ │     ParallelAgent           │
              └──────┬──────────┬───────────┘
         ┌──────────┘          └──────────┐
         ▼                                ▼
   [p_weather]                     [p_exchange]   ── 并发执行，互不等待
         │                                │
         ▼                                ▼
   [p_stock] 也并发 ──▶ 三者全部完成 ──▶ 汇总 Event 流
```

并行 Agent 的价值是**延迟优化**：三个串行要 3T，并行只要 max(T)。对"凑齐多个信息再生成报告"的场景，收益直接。

<callout emoji="✅"><p>面试考点：ParallelAgent 和 SequentialAgent 怎么选？判断标准是"子任务之间有没有数据依赖"。有依赖（B 要用 A 的结果）→ 串行；无依赖（各自独立）→ 并行。这是 Agent 架构设计模式里"并行化（Parallelization）"那一章的核心，我们第九章会系统讲。</p></callout>

### 3.2 并行 + 汇总：一个完整"晨报"示例

并行跑完通常还要一个汇总 Agent 把结果整合。组合方式是用 `SequentialAgent` 把 `ParallelAgent` 和汇总 Agent 串起来（SequentialAgent 下一节细讲，这里先给个组合示意）：

```java
package com.camp.adk;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.sequential.SequentialAgent;
import com.google.adk.agents.parallel.ParallelAgent;

import java.util.List;

public class MorningBriefPipeline {

    public SequentialAgent build() {
        ParallelAgent gather = ParallelAgent.builder()
                .name("gather")
                .subAgents(List.of(/* 上面的三个 Agent */))
                .build();

        LlmAgent summarize = LlmAgent.builder()
                .name("summarize")
                .instruction("把前面收集到的天气、汇率、股价整合成一段中文晨报。")
                .model("gemini-2.0-flash")
                .build();

        // 先并行采集，再汇总
        return SequentialAgent.builder()
                .name("morning_brief_pipeline")
                .subAgents(List.of(gather, summarize))
                .build();
    }
}
```

```text
[ParallelAgent: 采集] ──▶ [summarize: 汇总] ──▶ 最终晨报
   (并发三任务)              (基于采集结果生成)
```

<callout emoji="💡"><p>这种"并行采集 + 串行汇总"是 RAG 多路召回、情报收集类 Agent 的标准套路。先 fan-out 再 fan-in，工程上叫 scatter-gather。记住这个模式，后面 RAG 章节的混合检索重排序会用到同源思想。</p></callout>

---

## 四、三种 Agent 形态对比与选型

我们把这节讲的三种形态放一起对比，给你一个选型清单：

```text
┌──────────────┬──────────────────────┬───────────────────────┬─────────────────┐
│ 形态         │ 适用场景             │ 关键 API              │ 注意点          │
├──────────────┼──────────────────────┼───────────────────────┼─────────────────┤
│ 基础 Agent   │ 单一职责、单工具链   │ LlmAgent.builder()    │ 指令要写边界规则│
│ 子 Agent     │ 意图分发、专家协作   │ .subAgents(...)       │ 父子共享 Session│
│ 并行 Agent   │ 无依赖多任务并发     │ ParallelAgent.builder │ 结果需汇总      │
└──────────────┴──────────────────────┴───────────────────────┴─────────────────┘
```

### 4.1 企业落地：对账 Agent 的真实形态

举个企业里"财务对账"的例子。每天要对"银行流水"和"系统账单"，还要"生成差异报告"。这就是"并行采集 + 汇总"：

```java
package com.camp.adk;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.parallel.ParallelAgent;
import com.google.adk.agents.sequential.SequentialAgent;

import java.util.List;

/**
 * 财务对账 Agent：并行拉两端数据，再汇总差异。
 * 真实场景里两个子 Agent 会调用内部记账/银行接口工具。
 */
public class ReconcileAgent {

    private LlmAgent bankSide() {
        return LlmAgent.builder()
                .name("bank_agent")
                .instruction("读取银行流水文件，提取今日所有交易，结构化输出。")
                .model("gemini-2.0-flash")
                .build();
    }

    private LlmAgent systemSide() {
        return LlmAgent.builder()
                .name("system_agent")
                .instruction("从内部账单系统提取今日所有交易，结构化输出。")
                .model("gemini-2.0-flash")
                .build();
    }

    private LlmAgent diffAgent() {
        return LlmAgent.builder()
                .name("diff_agent")
                .instruction("对比两端交易，列出金额/笔数差异，输出对账报告。")
                .model("gemini-2.0-flash")
                .build();
    }

    public SequentialAgent build() {
        ParallelAgent fetch = ParallelAgent.builder()
                .name("fetch_both")
                .subAgents(List.of(bankSide(), systemSide()))
                .build();
        return SequentialAgent.builder()
                .name("reconcile_pipeline")
                .subAgents(List.of(fetch, diffAgent()))
                .build();
    }
}
```

<callout emoji="⚠️"><p>现实提醒：财务对账这类场景，Agent 的输出必须可审计、可追溯。不要只让模型"说一句对上了"。要让子 Agent 输出结构化差异清单，落库，再让人复核。AI 辅助而非 AI 替你拍板——这是企业级 Agent 的安全底线。</p></callout>

---

## 五、配置管理：把硬编码降到零

生产环境 Agent 的配置（模型名、温度、工具开关）应该外置。ADK 不强制配置中心，但你可以和 Spring 的 `@ConfigurationProperties` 配合：

```yaml
# application.yml —— 所有可变配置外置
adk:
  agents:
    weather:
      model: gemini-2.0-flash
      temperature: 0.2
      max-output-tokens: 1024
      enabled-tools:
        - weather
    root:
      model: gemini-2.5-pro
      temperature: 0.4
```

```java
package com.camp.adk;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "adk.agents")
public class AdkAgentConfig {
    private Map<String, AgentSpec> specs;

    public record AgentSpec(
            String model,
            Double temperature,
            Integer maxOutputTokens,
            List<String> enabledTools) {}

    public Map<String, AgentSpec> getSpecs() { return specs; }
    public void setSpecs(Map<String, AgentSpec> specs) { this.specs = specs; }
}
```

把配置抽出来后，Agent 的 builder 从配置读参数，改模型、调温度不用重新编译。这是企业级工程的标配，也是第十六章"Prompt as Code / 配置即代码"思想的前奏。

<callout emoji="💡"><p>为什么这么较真配置外置？因为生产环境要能"不改代码热调参"。比如大促期间把客服 Agent 温度调低避免乱承诺，活动结束调回来。配置进配置中心（Nacos / Apollo），运维点一下就生效，比发版快十倍。</p></callout>

---

## 六、调试 Agent：看清每一次调用

Agent 配错了很难一眼看出。ADK 提供执行追踪，你可以打印每次 Event 的类型和内容。调试口诀：**先单独测工具，再测单 Agent，最后测编排**。

```java
package com.camp.adk;

import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;

import java.util.Optional;

/**
 * 调试助手：把事件流分类打印，看清"模型在想什么、调了什么工具"。
 */
public class AgentDebugger {

    public static void inspect(Flowable<Event> events) {
        events.blockingForEach(event -> {
            if (event.partial()) {
                System.out.println("[流式片段] " + event.text());
            } else if (event.hasToolCall()) {
                System.out.println("[工具调用] " + event.toolCalls());
            } else if (event.hasToolResponse()) {
                System.out.println("[工具返回] " + event.toolResponses());
            } else {
                Optional.ofNullable(event.textContent())
                        .ifPresent(t -> System.out.println("[最终文本] " + t));
            }
        });
    }
}
```

<callout emoji="✅"><p>调试心法：多 Agent 系统 80% 的 bug 出在"委派没发生"或"工具没被调用"。用上面的分类打印，一眼就能看到模型到底走了哪条路。这比盲目改 instruction 高效得多。</p></callout>

---

## 七、招聘能力映射小结

这节课的能力在 JD 里对应哪些？我给你点出来：

- "**熟悉多 Agent 协作 / Supervisor 模式**" → 你写的 Root + 子 Agent 就是 Supervisor 的雏形。
- "**理解 Agent 编排（顺序/并行/循环）**" → 这节课的 ParallelAgent 和 SequentialAgent 是基础。
- "**LLM 函数调用 / 结构化输出**" → `@AdkTool` 和 `outputSchema` 直接对应。

<callout emoji="⚠️"><p>注意：JD 写"多 Agent 协作"时，面试官更想听的是"你怎么划分 Agent 边界、怎么处理父子状态共享、怎么避免委派失败"，而不是"我会调 ParallelAgent API"。面试准备时，把这节课的边界规则和 session state 讲清楚，比背 API 有用。</p></callout>

---

## 九、Agent 配置的版本管理与灰度

生产环境里，Agent 的指令（instruction）经常要调。直接改代码发版太重，正确做法是把 instruction 当"配置"管理，支持版本与灰度。

```yaml
# agent-prompts.yaml —— 指令版本化，存在配置中心
agents:
  weather_agent:
    versions:
      v1: "你是天气助手，回答简洁。"
      v2: "你是天气助手。规则：1.必调工具 2.先结论后数据 3.反问城市"
    active: v2
    gray:
      v3: 0.1   # 10% 流量走 v3 实验版
```

```java
package com.camp.adk;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Random;

/**
 * 指令灰度：按流量比例选择版本。生产用配置中心动态下发。
 */
@Component
public class PromptVersionRouter {
    @Value("#{${agents.weather_agent.versions}}")
    private Map<String, String> versions;
    @Value("${agents.weather_agent.active}")
    private String active;
    @Value("${agents.weather_agent.gray.v3:0}")
    private double grayV3;

    private final Random rnd = new Random();

    public String resolveInstruction() {
        if (rnd.nextDouble() < grayV3 && versions.containsKey("v3")) {
            return versions.get("v3");          // 实验桶
        }
        return versions.getOrDefault(active, "默认指令");
    }
}
```

<callout emoji="💡"><p>为什么指令要灰度？因为改一句指令可能让准确率暴跌。灰度让你用 10% 流量试错，出问题立刻回滚，不影响全体用户。这是 Prompt as Code（第十六章）思想在生产的最小实践。</p></callout>

## 十、对 Agent 做单元测试

很多同学以为"Agent 没法测"。错。因为一切皆对象，工具可 mock，Agent 可断言输出。

```java
package com.camp.adk;

import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.FunctionTool;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

public class AgentUnitTest {

    // 被测工具：用假实现，不调真实外部服务
    String fakeWeather(String city) {
        return "{\"city\":\"" + city + "\",\"temp\":22}";
    }

    @Test
    void agent_should_register_tool() {
        LlmAgent agent = LlmAgent.builder()
                .name("w")
                .instruction("用工具查天气")
                .model("gemini-2.0-flash")
                .tools(new FunctionTool(this::fakeWeather))
                .build();
        // 断言：工具已注册、名字正确
        assertNotNull(agent);
        assertEquals("w", agent.name());
    }
}
```

<callout emoji="✅"><p>测试心法：单测不验证"模型答得对"（那是评测，第十六章），而是验证"结构正确"——工具注册了没、子 Agent 挂上了没、循环上限设了没。把可测性当成 Agent 设计的硬指标。</p></callout>

## 十一、编排延迟与性能基线

不同编排形态的延迟差异巨大，上生产前要测基线。

```text
编排形态延迟基线（示意，单位 ms，单轮）：
  基础 Agent：       ~800（一次模型调用）
  子 Agent（1跳）：   ~1200（路由+委派）
  并行 Agent（3子）： 取最慢子Agent，~1000（并发省时）
  LoopAgent（3轮）：  ~2400（3×单次）
```

```java
package com.camp.adk;

public class LatencyBaseline {
    public static void main(String[] args) {
        long start = System.currentTimeMillis();
        // 跑一次编排，记录耗时
        // runner.runAsync(...)...
        long cost = System.currentTimeMillis() - start;
        System.out.println("编排耗时=" + cost + "ms");
        // 把基线打点到 Prometheus，超阈值告警
    }
}
```

<callout emoji="⚠️"><p>性能铁律：LoopAgent 的延迟是"轮数 × 单次"。用户不会等一个 10 秒的回答。要么限制轮数，要么把中间进度流式推给用户（"正在第 2/5 轮…"）。体验优化也是工程的一部分。</p></callout>

## 十二、避坑与检查清单

```text
Agent 创建配置避坑：
✅ 指令写"硬规则"而非"人设"
✅ 子 Agent 写清边界，避免越界/不委派
✅ 并行子任务确认无数据依赖
✅ 结构化输出用 outputSchema 而非靠模型自觉
✅ 大模型干细活、小模型干粗活（成本）
✅ 配置外置 + 灰度 + 可单测
❌ 工具描述模糊导致模型选错
❌ 一次性把 10 个工具全塞给一个 Agent
```

<callout emoji="💡"><p>这节课的工程含量比想象高：配置灰度、单测、延迟基线、成本分级，每一项都是面试追问点。能把"Agent 创建"讲到这个深度，说明你真做过生产。</p></callout>

## 小结

这节课把 Agent 的三种核心形态讲透了：

1. **基础 Agent**：三要素 name/instruction/model，进阶要管工具、`generationConfig`、结构化输出 `outputSchema`。指令要写"硬规则"而非"人设"。
2. **子 Agent**：通过 `.subAgents()` 挂到父 Agent，父负责意图路由、子负责专家执行；父子共享 Session，结构化信息走 session state 而非自然语言。
3. **并行 Agent**：`ParallelAgent` 并发执行无依赖子任务，延迟从"求和"降为"取最大"；常和汇总 Agent 组合成 scatter-gather 模式。
4. **选型原则**：单职责用基础、有分发用子 Agent、无依赖多任务用并行。
5. **工程化**：配置外置（YAML + 配置中心）、调试按"工具→单 Agent→编排"分层。

记住一句话：**Agent 形态的选择，本质是"任务依赖关系"的选择——有依赖就串行/委派，无依赖就并行。**

---

## 延伸阅读 / 引用文档

1. ADK Agents 概念文档：<https://google.github.io/adk-docs/agents/>
2. ADK 多 Agent 与子 Agent：<https://google.github.io/adk-docs/agents/multi-agents/>
3. ADK 工具（Tools）定义：<https://google.github.io/adk-docs/tools/>
4. Gemini 结构化输出（JSON mode）：<https://ai.google.dev/gemini-api/docs/structured-output>
5. Spring Boot Configuration Properties：<https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.external-config.typesafe-configuration-properties>
6. Agent 设计模式（Parallelization）：<https://www.anthropic.com/research/building-effective-agents>

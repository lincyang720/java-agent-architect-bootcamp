# 第五章第4节：内置工具集成——Google Search、Code Interpreter 的使用与原理

大家好，我是秋天。

前两节课我们把 Agent 的"身体"（创建、配置、编排）搭好了。这节课装"手脚"——**工具（Tool）**。特别是 ADK 官方给你准备好的两个杀手级内置工具：**Google Search** 和 **Code Interpreter**。

学完这节课你能：

1. 把 Google Search 接进 Agent，让模型用"实时联网"补知识盲区；
2. 用 Code Interpreter 让模型真的跑 Python 算数据、画图表；
3. 理解这两个工具背后的原理——它们不是"普通函数"，而是"带沙箱/带检索增强的特殊通道"；
4. 学会写你自己的工具，并理解工具设计的坑。

工具是 Agent 从"嘴炮"变"干活的"关键一步，也是招聘 JD 里"函数调用 / Tool Use"能力的具体落点。

---

## 一、为什么工具是 Agent 的分水岭

先说一个残酷事实：**没有工具的 LLM 只是"概率接话机器"，有工具的 Agent 才是"能办事的系统"**。模型的知识有截止日期、不会算精确数、碰不了你的私有数据。工具就是给它装上"手和眼"。

ADK 把工具分成两类：

```text
┌──────────────────────────────────────────────────────────────┐
│  ADK 工具分类                                                 │
│                                                                │
│  1) 自定义工具（FunctionTool）                                 │
│     @AdkTool 标注的 Java 方法 → 框架抽取 schema → 注册给模型   │
│                                                                │
│  2) 内置/官方工具（Built-in Tools）                            │
│     - GoogleSearchTool：联网检索（检索增强）                   │
│     - CodeInterpreterTool：沙箱执行 Python（计算/绘图）        │
│     - 其它：如 Retrieval（RAG）、Vertex 系列工具               │
└──────────────────────────────────────────────────────────────┘
```

<callout emoji="💡"><p>认知升级：工具不是"锦上添花"，而是 Agent 能力的"边界"。模型能做什么，完全由你给的工具集决定。所以工具设计（给什么、不给什么、怎么描述）是 Agent 工程里最值得花心思的事之一。</p></callout>

---

## 二、Google Search 工具：给 Agent 装上"实时网"

### 2.1 为什么需要 Google Search

模型训练数据有截止日，问"今天金价""刚发布的政策"它只能瞎编。接上 Google Search 后，Agent 在回答前会先联网检索，把真实结果带进上下文。这本质上就是**检索增强生成（RAG）的一种"开箱即用"形态**——只不过检索源是公开网页而非你的知识库。

### 2.2 接法（代码）

ADK 里 Google Search 是内置工具，直接用 `GoogleSearchTool`：

```java
package com.camp.adk;

import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.google_search.GoogleSearchTool;

/**
 * 接了 Google Search 的 Agent：能回答"实时性"问题。
 */
public class SearchAgentDemo {

    public LlmAgent build() {
        return LlmAgent.builder()
                .name("search_agent")
                .instruction("""
                        你是事实型问答助手。
                        规则：
                        1. 任何涉及"最新/今天/近期/价格/新闻"的问题，必须先用 Google Search 核实。
                        2. 回答要标注信息来源（搜索结果的链接）。
                        3. 如果搜索结果冲突，说明存在分歧。
                        """)
                .model("gemini-2.0-flash")        // 注意：Search 需支持 grounding 的模型
                .tools(new GoogleSearchTool())     // 关键：挂上搜索工具
                .build();
    }
}
```

运行后，模型在生成答案前会自动发起搜索，并把检索到的网页片段作为"grounding"证据带入回答。你在 Event 里能看到 `grounding_metadata`（搜索来源链接）。

```text
用户："今天 OpenAI 发布了什么？"
        │
        ▼
[search_agent] ──▶ Google Search 检索 ──▶ 拿到 5 条最新结果
        │
        ▼
模型基于检索结果生成答案 + 附来源链接
```

<callout emoji="✅"><p>招聘映射：这直接对应 JD 里的"RAG / 检索增强生成经验"。Google Search 工具是"外部知识 RAG"的零配置版本。面试时能说清"内置 Search 和我自建向量库 RAG 的区别与取舍"，是加分项（第七章会系统讲 RAG 全链路）。</p></callout>

### 2.3 原理剖析：Grounding 与来源归因

Google Search 工具底层走的是 Gemini 的 **Google Search Grounding** 能力。它的工作流是：

```text
1. 模型判断"我需要外部事实" → 触发 grounding 请求
2. Gemini 调用 Google Search 检索，返回候选网页片段
3. 模型基于片段生成答案，同时记录每段话对应的来源
4. 输出时附带 grounding_metadata（来源 URL、标题）
```

这带来两个工程价值：

1. **减少幻觉**：答案是"有出处"的，不是模型瞎编。
2. **可溯源**：用户/审计能看到"这句话来自哪个网页"，满足合规要求（金融、医疗尤其要）。

<callout emoji="⚠️"><p>坑点提醒：Grounding 不等于"答案一定对"。搜索结果本身可能错、可能过时。企业场景别把 Search 当"真理来源"，要配合"来源展示 + 人工复核"。另外 Search 工具依赖 Google 服务，数据出境和合规要评估（国内业务慎用或走 Vertex 区域配置）。</p></callout>

---

## 三、Code Interpreter：让 Agent 真的"算"和"画"

### 3.1 为什么模型自己算数不靠谱

LLM 做算术、数据处理、画图都很弱——它擅长"生成看起来对的代码"，但不擅长"精确执行"。Code Interpreter 给它一个**真实运行的 Python 沙箱**，让它把"算"和"画"交给解释器，结果才可靠。

### 3.2 接法（代码）

```java
package com.camp.adk;

import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.code_interpreter.CodeInterpreterTool;

/**
 * 接了 Code Interpreter 的 Agent：能跑 Python 做数据分析/绘图。
 */
public class CodeAgentDemo {

    public LlmAgent build() {
        return LlmAgent.builder()
                .name("data_agent")
                .instruction("""
                        你是数据分析助手。
                        规则：
                        1. 涉及计算、统计、绘图，必须写 Python 代码并用 Code Interpreter 执行。
                        2. 不要口头估算数值，用代码跑出真实结果。
                        3. 绘图后说明图表含义。
                        """)
                .model("gemini-2.0-flash")
                .tools(new CodeInterpreterTool())   // 关键：沙箱执行 Python
                .build();
    }
}
```

模型会生成 Python 代码，框架把它丢进沙箱执行，再把 stdout / 图片结果回填给模型继续推理。

```text
用户："算一下这组数的标准差并画直方图"
        │
        ▼
[data_agent] 生成 Python 代码
        │
        ▼
Code Interpreter 沙箱执行 → 得到数值 + 图片
        │
        ▼
模型拿到真实结果，组织成中文回答 + 附图
```

<callout emoji="💡"><p>为什么沙箱必要？因为"让 LLM 直接执行代码"等于把服务器交给它——它能 `rm -rf`。Code Interpreter 的核心是"隔离的执行环境"：受限文件系统、受限网络、超时控制、资源限额。安全执行是工具设计的生命线，第十二章会专门展开。</p></callout>

### 3.3 原理剖析：沙箱与状态

Code Interpreter 的运行模型：

```text
┌──────────────────────────────────────────────────────────┐
│  Code Interpreter 运行时                                   │
│                                                            │
│  模型代码 ──▶ 沙箱(隔离容器/进程)                          │
│                  ├─ 受限文件系统（临时空间）               │
│                  ├─ 受限网络（默认禁出网）                 │
│                  ├─ 超时（如 60s 强杀）                    │
│                  ├─ 资源限额（CPU/内存）                   │
│                  └─ 状态可保持（同一会话多段代码共享变量） │
│                          │                                │
│                          ▼                                │
│                   stdout / 图片 / 错误 ──▶ 回填模型        │
└──────────────────────────────────────────────────────────┘
```

重点：**同一会话内多段代码共享状态**（变量、导入的库保留），所以模型可以"先加载数据，再分析，再画图"分多步完成。但每次会话之间隔离，互不污染。

<callout emoji="✅"><p>工程价值：Code Interpreter 把"数据分析师"的能力装进了 Agent。财报分析、日志统计、实验数据处理这些场景，比纯文本模型可靠一个量级。对应 JD 里"具备 Agent Tool Use / 让模型调用外部执行环境"的能力。</p></callout>

---

## 四、自定义工具：@AdkTool 的标准写法

内置工具再强，也覆盖不了你的业务。自定义工具是日常主力。回顾并深化 `@AdkTool` 写法，强调"好工具的三要素"。

### 4.1 完整自定义工具示例

```java
package com.camp.adk;

import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.Annotations.AdkTool;
import com.google.adk.tools.Annotations.AdkTool.Param;
import com.google.adk.tools.FunctionTool;

import java.util.Map;

/**
 * 业务工具示例：查询用户积分。演示"好工具"的写法。
 */
public class PointsToolDemo {

    // 1) 方法级 @AdkTool：给模型看的"工具说明书"
    @AdkTool("查询指定用户的会员积分余额")
    public Map<String, Object> queryPoints(
            // 2) 参数级 @Param：说明每个参数含义与约束
            @Param("用户ID，格式 U+数字，如 U12345") String userId,
            @Param("积分类型：total(总积分) 或 usable(可用积分)") String type) {

        // 3) 真实逻辑：这里接入积分服务；demo 返回结构
        if (!userId.matches("^U\\d+$")) {
            return Map.of("error", "用户ID格式非法");
        }
        int balance = switch (type) {
            case "usable" -> 820;
            case "total" -> 1500;
            default -> 0;
        };
        return Map.of("userId", userId, "type", type, "balance", balance);
    }

    public LlmAgent build() {
        return LlmAgent.builder()
                .name("member_agent")
                .instruction("你是会员助手。查积分必须调用 queryPoints，禁止编造余额。")
                .model("gemini-2.0-flash")
                .tools(new FunctionTool(this::queryPoints))
                .build();
    }
}
```

**好工具的三要素**（记下来，面试能背）：

1. **描述精确**：`@AdkTool` 说清"做什么、何时用"，模型才知道什么时候调。
2. **参数有约束**：`@Param` 写清格式和枚举，减少模型传错参。
3. **返回值结构化**：用 `Map` / POJO，别返回一大段自然语言，模型才好解析。

<callout emoji="⚠️"><p>工具设计第一大坑：描述模糊导致"该调不调"或"乱调"。比如工具名叫 `getInfo`，模型永远猜不准。名字和描述要像"函数文档"一样自解释。第二大坑：工具内部没做输入校验，模型传个 SQL 注入字符串进来就出事——工具边界安全是第十二章重点。</p></callout>

---

## 五、工具组合：Search + Code + 业务工具同框

真实 Agent 往往同时挂多个工具，模型自己选。举个例子："竞品分析 Agent"——先用 Search 抓竞品新闻，再用 Code 算对比指标，最后用业务工具存结论。

```java
package com.camp.adk;

import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.google_search.GoogleSearchTool;
import com.google.adk.tools.code_interpreter.CodeInterpreterTool;
import com.google.adk.tools.FunctionTool;

/**
 * 多工具组合：搜索 + 代码 + 业务存储。
 */
public class CompetitorAgent {

    @AdkTool("把分析结论存入数据库，返回记录ID")
    public String saveReport(@AdkTool.Param("报告标题") String title,
                             @AdkTool.Param("报告正文 Markdown") String body) {
        // 真实接入存储；demo 返回假ID
        return "report_" + System.currentTimeMillis();
    }

    public LlmAgent build() {
        return LlmAgent.builder()
                .name("competitor_agent")
                .instruction("""
                        你是竞品分析助手。流程：
                        1. 用 Google Search 搜集竞品近况；
                        2. 用 Code Interpreter 做数据对比与可视化；
                        3. 用 saveReport 把结论存档。
                        """)
                .model("gemini-2.0-flash")
                .tools(new GoogleSearchTool(),
                       new CodeInterpreterTool(),
                       new FunctionTool(this::saveReport))
                .build();
    }
}
```

```text
[competitor_agent]
   │
   ├─ GoogleSearchTool ──▶ 抓竞品新闻
   ├─ CodeInterpreter  ──▶ 算指标、画图
   └─ saveReport       ──▶ 存档
        （模型按指令顺序自主选用）
```

<callout emoji="💡"><p>工具越多，模型越容易"选错"或"顺序乱"。指令里明确"流程步骤"能显著提升准确率。但工具超过 10 个就要考虑"工具路由"（先让一个小模型选工具再执行），否则上下文被工具 schema 占满，推理质量下降。这是企业级工具治理的常识。</p></callout>

---

## 六、工具调用的事件流与可观测

工具调用在 ADK 的事件流里是显式的，方便你做可观测（第十章详讲）。一个完整工具调用周期会产生三类 Event：

```text
Event 序列（一次工具调用）：
  1) [tool_call]    模型发出：我要调 queryPoints(userId=U12345)
  2) [tool_response] 框架返回：{balance: 820}
  3) [text]         模型基于结果生成：您的可用积分是 820 分
```

调试时把这些打出来，你就能看到"模型想调什么、实际返回什么、最后说了什么"，定位问题的效率直接起飞。

```java
package com.camp.adk;

import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;
import java.util.Optional;

public class ToolTrace {

    public static void trace(Flowable<Event> events) {
        events.blockingForEach(e -> {
            if (e.hasToolCall()) {
                System.out.println("🔧 调用: " + e.toolCalls());
            } else if (e.hasToolResponse()) {
                System.out.println("📦 返回: " + e.toolResponses());
            } else {
                Optional.ofNullable(e.textContent()).ifPresent(t -> System.out.println("💬 " + t));
            }
        });
    }
}
```

<callout emoji="✅"><p>生产必备：把工具调用日志结构化落盘（哪个工具、什么入参、什么出参、耗时多少），这是 Agent 可观测性的核心指标。出了问题能回放"模型当时为什么调了这个工具"。对应 JD 里"Agent 可观测性 / 调用链追踪"要求。</p></callout>

---

## 七、企业落地：对账场景的工具链

回到第四章提过的"对账"。真实落地时，工具链长这样：

```text
对账 Agent 工具集：
  ├─ fetchBankStatement(bankId, date)   拉银行流水（内部接口）
  ├─ fetchSystemBills(date)             拉系统账单
  ├─ compareTxns(listA, listB)         代码解释器算差异（精确）
  └─ createTicket(diffReport)           差异超阈值自动建工单
```

注意：**"算差异"用 Code Interpreter 而非让模型口算**——金额必须精确，模型口算 100% 出错。这就是"把确定性计算交给代码，把判断交给模型"的工程哲学。

<callout emoji="⚠️"><p>经验法则：凡是"算"和"精确比"，交给 Code Interpreter 或自定义 Java 工具；凡是"理解/生成/决策"，交给模型。别让模型干它不擅长的精确活，这是 Agent 质量的分水岭。</p></callout>

---

## 八、招聘能力映射

这节课直接对应 JD 高频词：

- "**熟悉 LLM 函数调用 / Tool Use / Function Calling**" → `@AdkTool` + `FunctionTool` 就是函数调用。
- "**具备 RAG / 检索增强经验**" → Google Search 是外部知识 RAG 的零配置版。
- "**理解 Agent 安全执行 / 沙箱**" → Code Interpreter 的沙箱原理。
- "**让模型调用外部系统（API/DB）**" → 自定义业务工具。

<callout emoji="💡"><p>面试金句："工具设计的核心是'描述自解释 + 参数有约束 + 返回结构化 + 内部做校验'，工具越多越要加工具路由。" 这段话能把"会用工具"提升到"懂工具工程"。</p></callout>

---

## 八、工具的错误重试与降级（Fallback）

生产工具会失败——网络抖、服务挂、超时。Agent 工具必须考虑重试与降级，否则一次工具失败就整轮崩。

```java
package com.camp.adk;

import com.google.adk.tools.Annotations.AdkTool;
import com.google.adk.tools.Annotations.AdkTool.Param;

import java.util.Map;

/**
 * 带重试与降级的业务工具示例。
 */
public class ResilientToolDemo {

    @AdkTool("查询用户积分，失败自动重试，仍失败返回安全默认值")
    public Map<String, Object> queryPoints(
            @Param("用户ID") String userId) {
        int maxRetry = 3;
        for (int i = 0; i < maxRetry; i++) {
            try {
                return doQuery(userId);
            } catch (Exception e) {
                if (i == maxRetry - 1) {
                    // 降级：返回安全默认值，不让 Agent 整轮崩溃
                    return Map.of("error", "服务暂不可用", "balance", 0, "degraded", true);
                }
            }
        }
        return Map.of("balance", 0);
    }

    private Map<String, Object> doQuery(String userId) {
        // 真实调用积分服务；可能抛超时异常
        if (Math.random() < 0.3) throw new RuntimeException("timeout");
        return Map.of("userId", userId, "balance", 820);
    }
}
```

<callout emoji="⚠️"><p>降级原则：工具失败不能"抛异常炸掉整轮对话"，要返回结构化错误信息让 Agent 优雅处理（"积分服务暂时查不到，建议您稍后再试"）。这是 Agent 工程化的基本素养，对应 JD 里"异常处理/降级"要求。</p></callout>

## 九、自定义工具进阶：流式工具（边算边出）

有些工具跑很久（生成大报表、跑长任务），用户干等很糟。可以做成"流式工具"——边算边吐进度。

```java
package com.camp.adk;

import com.google.adk.tools.Annotations.AdkTool;
import io.reactivex.rxjava3.core.Flowable;

/**
 * 流式工具示意：返回 Flowable，逐步吐出进度。
 * （ADK 工具返回类型以官方 API 为准，此处展示"长任务流式"思想）
 */
public class StreamingToolDemo {

    @AdkTool("生成本月报表，边算边回报进度")
    public Flowable<String> generateReport() {
        return Flowable.fromCallable(() -> "报表生成中 10%...")
                .concatWith(Flowable.just("报表生成中 50%..."))
                .concatWith(Flowable.just("报表完成：/reports/march.xlsx"));
    }
}
```

<callout emoji="💡"><p>思想价值：长任务工具必须"给反馈"，否则用户以为卡死了。流式进度是体验底线。真实接入方式参考 ADK 官方流式工具 API，核心思想不变——别让用户盲等。</p></callout>

## 十、工具安全：输入校验与权限

工具是 Agent 的"手"，也是最大的攻击面。两个必须做的安全动作：

```java
package com.camp.adk;

import com.google.adk.tools.Annotations.AdkTool;
import com.google.adk.tools.Annotations.AdkTool.Param;

import java.util.Map;

public class SecureToolDemo {

    @AdkTool("执行 SQL 查询（受限）")
    public Map<String, Object> runSql(
            @Param("只读 SELECT 语句") String sql,
            // 权限上下文：从 session state 注入，工具内校验
            String userRole) {
        // 1) 输入校验：只允许 SELECT，禁止写操作
        if (!sql.trim().toLowerCase().startsWith("select")) {
            return Map.of("error", "仅允许 SELECT");
        }
        if (sql.toLowerCase().matches(".*(drop|delete|update|insert|;).*")) {
            return Map.of("error", "检测到危险关键字，已拒绝");
        }
        // 2) 权限校验：非 DBA 角色禁止访问敏感表
        if (sql.toLowerCase().contains("users") && !"dba".equals(userRole)) {
            return Map.of("error", "无权限访问 users 表");
        }
        // 真实执行（参数化，防注入）
        return doSafeQuery(sql);
    }

    private Map<String, Object> doSafeQuery(String sql) {
        return Map.of("rows", 42);
    }
}
```

<callout emoji="✅"><p>安全铁律（第十二章会展开）：① 工具内永远校验输入（白名单优先）；② 危险操作（SQL/命令/删除）必须关键词黑名单 + 权限校验；③ 敏感数据访问按角色隔离。模型不可信——它可能被 prompt injection 诱导调危险工具。</p></callout>

## 十一、工具治理：当工具数量膨胀时

工具超过 10 个，模型选工具准确率明显下降。治理手段：

```text
工具治理三招：
  1) 工具路由：先让小模型判断"该用哪个工具"，再执行（减少同时暴露的工具数）
  2) 工具分组：按领域把工具分到不同子 Agent（订单工具归订单 Agent）
  3) 工具描述精简：每条 @AdkTool 描述控制在 1-2 句，突出触发条件
```

<callout emoji="💡"><p>经验值：单 Agent 暴露 5-8 个工具是甜区，超过 12 个准确率明显下滑。工具多就分组到子 Agent（lesson02）或加路由层。这是"工具工程"的常识，不是玄学。</p></callout>

## 十二、避坑清单

```text
工具开发避坑：
✅ @AdkTool 描述精确、@Param 说明约束
✅ 返回值结构化（Map/POJO），不返回大段自然语言
✅ 工具内做输入校验 + 权限校验
✅ 失败重试 + 降级，不让整轮崩溃
✅ 长任务流式回报进度
❌ 把原始异常抛给用户/炸掉整轮
❌ 给模型 15+ 个工具同时选
❌ 让 LLM 软判断"要不要走危险工具"
```

<callout emoji="⚠️"><p>工具是 Agent 能力的边界，也是安全的边界。这节课讲的工具工程（重试/降级/安全/治理），是把"会用工具"变成"懂工具工程"的分水岭。面试被问"Agent 安全"，工具安全是第一枪。</p></callout>

## 十三、工具调用的 Token 成本计算

工具不是免费的——工具描述（schema）每次都进上下文，工具结果也占 token。要算这笔账。

```text
单次工具调用成本构成：
  ① 工具 schema：每个工具的描述+参数定义，每次请求都带上
     10 个工具 ≈ 1000-2000 token 常驻上下文
  ② 工具调用请求/响应：模型输出调用 + 框架回填结果
  ③ 工具结果：可能很大（如查到 50 条记录）

优化：
  - 工具描述精简 → 省 ①
  - 工具结果只回关键字段 → 省 ③
  - 同类工具分组到子 Agent → 减少单次暴露数 → 省 ①
```

```java
package com.camp.adk;

/** 估算一次工具调用占用的 token（粗略） */
public class ToolCostEstimator {
    public static int estimate(int toolSchemaTokens, int resultTokens) {
        // 输入侧：schema 每次都带；输出侧：结果
        return toolSchemaTokens + resultTokens;
    }
    // 若 10 工具 schema=1500 token，结果=500 → 单次 2000 token
    // 每轮都调 → 10 轮对话 = 20000 token 仅工具相关
}
```

<callout emoji="⚠️"><p>很多团队忽略了"工具 schema 常驻上下文"的成本——它不是一次性的，是每次请求都重发。工具越多、描述越长，这部分越贵。所以工具描述要精简，工具要分组，不是越多越好。</p></callout>

## 十四、与 MCP 工具的衔接（预告）

ADK 的工具未来会和 **MCP（Model Context Protocol）** 打通——MCP 是 Agent 工具的标准化协议（第十一章详讲）。简单说，MCP 让"任何服务"都能以统一协议暴露成工具，Agent 无需为每个服务写适配。

```text
ADK 自定义工具  vs  MCP 工具：
  自定义工具：写在代码里，和 Agent 同进程
  MCP 工具：独立服务，按 MCP 协议暴露，Agent 远程调用

  价值：工具可复用、可独立部署、跨框架（ADK/LangChain/Claude 都能用）
```

<callout emoji="💡"><p>预习：第十一章会系统讲 MCP 客户端/服务端实现。现在你只要记住——工具正在"标准化"，未来你的 Agent 能即插即用地消费任何 MCP 服务，不用重复造轮子。这也是招聘 JD 里"熟悉 MCP 协议"的由来。</p></callout>

## 十五、工具版本与生命周期管理

工具也会迭代——改了参数、换了实现。要像管理 API 一样管理工具版本，避免"模型按旧 schema 调用新工具"出错。

```text
工具生命周期管理：
  ├─ 版本号：@AdkTool 的工具名带版本（queryPoints_v2）
  ├─ 兼容期：新旧并存，灰度切换，不突然下线
  ├─ 变更评审：改工具描述/参数要评审（影响模型调用行为）
  └─ 废弃流程：先标记 deprecated，观察无调用后下线
```

<callout emoji="✅"><p>工具描述变更是"隐式 breaking change"——你改了一句描述，模型调用行为可能全变。所以工具描述和 API 一样要纳入评审和版本管理，不能随意改。</p></callout>

## 小结

这节课把 ADK 的工具体系讲全了：

1. **工具是 Agent 的分水岭**：从"接话机器"变"办事系统"，能力边界由工具集决定。
2. **Google Search**：开箱即用的外部知识 RAG，底层是 Grounding，带来"减幻觉 + 可溯源"两大价值；但来源本身需复核、合规需评估。
3. **Code Interpreter**：真实 Python 沙箱，让模型"算得准、画得出"；核心是隔离执行环境（受限文件/网络/超时/资源）。
4. **自定义工具**：`@AdkTool` + `@Param` + `FunctionTool`，好工具三要素——描述精确、参数有约束、返回结构化。
5. **组合与治理**：多工具靠指令定流程；超 10 个工具要考虑工具路由；工具调用事件流是可观测性的核心。
6. **工程哲学**：确定性计算交给代码，判断决策交给模型。

记住一句话：**工具不是"给模型多几个按钮"，而是"定义 Agent 能触达的真实世界边界"——边界设计的好坏，直接决定 Agent 能不能上线。**

---

## 延伸阅读 / 引用文档

1. ADK Tools 总览：<https://google.github.io/adk-docs/tools/>
2. Google Search Grounding 文档：<https://cloud.google.com/vertex-ai/generative-ai/docs/grounding/overview>
3. Code Interpreter（Vertex）：<https://cloud.google.com/vertex-ai/generative-ai/docs/code-generation/code-interpreter>
4. Gemini Function Calling：<https://ai.google.dev/gemini-api/docs/function-calling>
5. OpenAI 工具/函数调用对比参考：<https://platform.openai.com/docs/guides/function-calling>
6. Anthropic Tool Use：<https://docs.anthropic.com/en/docs/build-with-claude/tool-use>

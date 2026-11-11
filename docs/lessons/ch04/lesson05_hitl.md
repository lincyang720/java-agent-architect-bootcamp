# 第四章第5节：Human In The Loop 机制：在 Graph 中引入人工审核节点

大家好，我是秋天。

前面讲的 Sequential / Loop / Supervisor，默认都是"全自动"——模型自己跑完。但真实企业里，有些决策你不能交给机器：放款、退款、删数据、对外发公告。这些"高风险动作"必须有人拍板。这就是 **Human In The Loop（HITL，人在回路）**：在自动化流程里留一个"人工审核节点"，关键时刻暂停，等人确认了再继续。

这一节我们讲怎么在 Graph 里实现 HITL，以及它背后的工程与合规逻辑。

这节课讲什么、学完掌握什么：

- 讲什么：HITL 的两种实现形态（同步阻塞等待 / 异步中断-恢复）、Graph 里用"人工节点 + 暂停状态"实现、人工节点如何把外部输入写回 `OverAllState` 继续流转、以及它和 lesson01"状态同步"的关联。
- 学完掌握：你能用 Graph 搭一个"模型生成 → 暂停等人审 → 人通过则执行/人驳回则重写"的流程；你理解为什么金融/医疗/政企场景 HITL 不是可选项而是合规刚需。

<callout emoji="💡"><p>招聘映射：JD 里"熟悉 Human-in-the-Loop / 人工介入 / 审批流集成"。在金融、政企、医疗客户项目里，HITL 是交付的硬门槛。能讲清"怎么在 Agent 流程里安全地插人工"，是区分"玩具 Demo"和"可交付系统"的关键。</p></callout>

---

## 一、为什么必须有人：三个不能全自动的场景

```
┌─────────────────────────────────────────────┐
│ ① 高风险动作：放款/退款/删库/发对外公告        │
│ ② 模糊决策：模型置信度低、涉及伦理/合规        │
│ ③ 责任归属：出事要有人背锅，不能全推给模型     │
└─────────────────────────────────────────────┘
```

法律和合规上，"全自动决策造成损失"责任主体模糊，而"人工审核后执行"责任清晰。所以金融风控、医疗诊断辅助、政企审批，HITL 是刚需，不是优化项。

---

## 二、HITL 的两种实现形态

### 形态 A：同步阻塞等待（简单场景）

流程在某个节点停下来，HTTP 请求一直挂着（或用长轮询），直到人工在后台点了"通过/驳回"，节点才返回、Graph 继续。实现简单，但占用连接、不适合长耗时审批。

### 形态 B：异步中断-恢复（生产推荐）

Graph 在人工节点**中断（interrupt）**，把当前状态持久化（带 `threadId`），立即返回"待审核"给前端；人工审批后，用同一个 `threadId` 调用"恢复（resume）"接口，Graph 从断点继续。这是生产标准做法——审批可能几小时，不能让连接挂着。

```
Graph 执行: START → 生成 → [人工节点: interrupt, 持久化状态] ──▶ 返回"待审核"
                                                                   │
                                          几小时后人工点"通过"        │
                                                                   ▼
                                      resume(threadId) → 状态恢复 → 执行/END
```

---

## 三、用 Graph 实现 HITL：人工节点 + 恢复

我们用"模型起草退款话术 → 人工审核 → 通过则发送/驳回则重写"为例。核心是一个**人工节点**，它把状态标记为"待审核"并中断，等外部 `resume` 注入 `human_decision` 后继续。

```java
package com.example.ch04.hitl;

import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.alibaba.cloud.ai.graph.GraphStateException;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class HitlGraph {

    // ① 起草节点：模型生成退款话术
    static class DraftNode implements NodeAction {
        private final ChatClient chatClient;
        public DraftNode(ChatClient c) { this.chatClient = c; }
        @Override
        public Map<String, Object> apply(OverAllState s) {
            String text = chatClient.prompt()
                    .system("起草一段给客户的退款说明，语气专业。")
                    .user("订单 ORD-123 退款200元").call().content();
            return Map.of("draft", text, "status", "PENDING_HUMAN");
        }
    }

    // ② 人工审核节点：若状态是 PENDING_HUMAN 则中断等待外部 resume
    //    这里用"检查 human_decision 是否存在"模拟中断/恢复
    static class HumanReviewNode implements NodeAction {
        @Override
        public Map<String, Object> apply(OverAllState s) {
            // 真实框架提供 interrupt() 原语；此处用状态判断表达同一语义：
            // 若还没有人工决策，标记为待审核并返回（外部 resume 会重新进入并带决策）
            Optional<Object> decision = s.value("human_decision");
            if (decision.isEmpty()) {
                // 模拟中断点：返回特殊标记，由调用方持久化并等待
                return Map.of("status", "AWAIT_DECISION");
            }
            return Map.of("status", "REVIEWED");
        }
    }

    // ③ 路由：根据人工决策走向
    static class AfterReviewRouter implements EdgeAction {
        @Override
        public String apply(OverAllState s) {
            String d = s.value("human_decision", "REJECT").toString();
            return "APPROVE".equals(d) ? "send" : "rewrite";
        }
    }

    // ④ 发送节点（通过后才执行的高风险动作）
    static class SendNode implements NodeAction {
        @Override
        public Map<String, Object> apply(OverAllState s) {
            String draft = s.value("draft", "").toString();
            // 真实场景：调用短信/邮件网关发送
            return Map.of("result", "已发送给客户：" + draft);
        }
    }

    // ⑤ 重写节点（驳回后）
    static class RewriteNode implements NodeAction {
        private final ChatClient chatClient;
        public RewriteNode(ChatClient c) { this.chatClient = c; }
        @Override
        public Map<String, Object> apply(OverAllState s) {
            String draft = s.value("draft", "").toString();
            String better = chatClient.prompt()
                    .system("上一版被驳回，改进措辞，更简洁。").user(draft)
                    .call().content();
            return Map.of("draft", better);
        }
    }

    public static CompiledGraph build(ChatClient chatClient) throws GraphStateException {
        var ksf = (com.alibaba.cloud.ai.graph.KeyStrategyFactory) () -> {
            java.util.Map<String, com.alibaba.cloud.ai.graph.KeyStrategy> m = new java.util.HashMap<>();
            m.put("draft", com.alibaba.cloud.ai.graph.Channel.<String>create().build());
            m.put("status", com.alibaba.cloud.ai.graph.Channel.<String>create().build());
            m.put("human_decision", com.alibaba.cloud.ai.graph.Channel.<String>create().build());
            m.put("result", com.alibaba.cloud.ai.graph.Channel.<String>create().build());
            return m;
        };
        StateGraph g = new StateGraph("hitl", ksf);
        g.addNode("draft", com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async(new DraftNode(chatClient)));
        g.addNode("human", com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async(new HumanReviewNode()));
        g.addNode("send", com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async(new SendNode()));
        g.addNode("rewrite", com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async(new RewriteNode(chatClient)));
        g.addEdge(StateGraph.START, "draft");
        g.addEdge("draft", "human");
        g.addConditionalEdges("human",
                com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async(new AfterReviewRouter()),
                Map.of("send", "send", "rewrite", "rewrite"));
        g.addEdge("send", StateGraph.END);
        // 重写后回到 human 再次审核（闭环）
        g.addEdge("rewrite", "human");
        return g.compile();
    }
}
```

关键点在 `HumanReviewNode`：它代表"中断点"。真实的 Spring AI Alibaba Graph 提供 `interrupt()` 原语让节点主动暂停并持久化状态；恢复时用 `compiledGraph.invoke(input, config)` 配合已注入的 `human_decision` 状态，从断点续跑。我这里用"状态判断"表达了同一语义，便于你理解。

<callout emoji="⚠️"><p>生产落地 HITL 一定要配"超时"和"超时默认策略"。审批人三天不点，流程不能卡死——要么自动升级给上级，要么默认驳回/转人工工单。把"无限等待人工"当默认行为，是 HITL 实现最常见的事故。</p></callout>

---

## 四、HITL 与状态同步的关系

回忆 lesson01 讲的"状态同步"——HITL 本质是一次**跨时间的状态同步**：Graph 在某刻把状态"冻结"交给人，人处理后把新状态"写回"，Graph 再继续。这正好用 `OverAllState` + `threadId` 实现：

```
t0: Graph 执行到 human 节点，状态 = {draft: "...", status: PENDING}
     │  持久化到存储(key=threadId)
     ▼
t0~t1: 连接已返回，Graph 不占资源，等人工
     │
t1: 人工在后台点"通过"，系统把 {human_decision: APPROVE} 写回存储
     │
     ▼
t1: resume(threadId) → 状态恢复 → AfterReviewRouter 走 send 分支 → END
```

这就把 lesson01 的"共享状态 + 持久化"和 HITL 串起来了：人，也是一种"外部节点"，通过状态读写参与协作。

---

## 五、企业场景：贷款初审的"人工终审"

银行对公贷款初审，模型能跑完征信、风控、合规，但"最终放款与否"必须信贷经理拍板：

```
[接收] → [征信] → [风控] → [合规] → [人工终审节点: interrupt]
                                      │
                          信贷经理在系统点"通过/拒绝/补材料"
                                      │
                          resume → [通过: 发起放款] / [拒绝: 通知客户] / [补材料: 回征信]
```

这个人工节点就是 HITL。它把"模型建议"和"人类决策"解耦：模型负责又快又全地准备材料和建议，人类负责最后那一下责任归属明确的判断。既提效，又合规。

<callout emoji="✅"><p>工程经验：HITL 节点的"人工界面"要给人看够信息——模型的风险分、关键依据、建议动作，都要展示，而不是只给个"通过/拒绝"按钮。人决策质量取决于信息透明度。这也是可观测性和 Agent 平台要解决的问题。</p></callout>

---

## 六、HITL 的工程化要点清单

1. **中断点要少而精**：别每个节点都加人工，只在"高风险/模糊/需担责"处加。
2. **状态持久化**：interrupt 时状态必须落库（带 threadId），否则重启丢流程。
3. **超时与升级**：超时自动升级/默认策略，杜绝无限等待。
4. **权限与审计**：谁能审核、审了什么、何时审的，全留痕——合规刚需。
5. **可恢复性**：resume 必须幂等，重复提交不产生重复动作（如重复放款）。
6. **降级**：审核系统挂了，要有兜底（转线下工单），不能阻塞业务。

<callout emoji="💡"><p>求职导向：讲 HITL 时不要只说"加个人工节点"，要能展开"异步中断-恢复 + 状态持久化 + 超时升级 + 审计留痕 + 幂等恢复"这套工程清单。这就是"交付级"和"Demo 级"的差距，金融/政企面试官极看重。</p></callout>

---

## 八、Graph 的中断原语：interrupt()

生产级 Graph（Spring AI Alibaba）提供 `interrupt()` 原语，让节点主动暂停并持久化状态，等外部 `resume` 注入决策后继续。这是 lesson05 伪代码的"真身"：

```java
package com.example.ch04.hitl;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.action.InterruptAction;

// 概念示意：人工节点用 interrupt 暂停，等待外部 resume
static class RealHumanNode implements NodeAction {
    @Override
    public Map<String, Object> apply(OverAllState state) {
        // 框架在此处中断，把状态落库并返回给调用方
        // 调用方展示给人工，人工在 UI 点"通过/拒绝"
        // 之后用同一 threadId resume，并注入 human_decision
        return Map.of("status", "AWAIT_DECISION");
    }
}
```

`interrupt()` 的价值是**原子性**：节点执行到中断点时，状态已持久化、连接已释放，绝不占资源。恢复时从存储读回，上下文不丢。这正是 lesson07 企业级"持久化"与第十七章"Durable Execution"的 HITL 版。

<callout emoji="💡"><p>心智模型：HITL 节点 = "把一次 Agent 执行切成两段"，中间插一个人类。第一段产出"待审材料"，第二段消费"人类决策"。threadId 是连接两段的线。理解这点，你就能把 HITL 嵌进任何 Graph。</p></callout>

---

## 九、审批 UI 与状态展示

HITL 不只是后端机制，前端也要配合。人工审核界面要展示**足够信息**让人做对决策：

```
┌──────── 审核面板 ────────┐
│ 任务：ORD-123 退款200元   │
│ 模型建议：通过退款         │
│ 依据：交易记录显示重复扣款 │
│ 风险分：低 (0.1)          │
│ [通过] [拒绝] [补充材料]   │
└──────────────────────────┘
```

只给"通过/拒绝"按钮、不给人看依据，人就是盲审，质量低。把模型的"建议+依据+风险分"全展示，人工决策才靠谱。这也是可观测性的终端体现。

---

## 十、超时升级与默认策略

人工可能很久不审，流程不能卡死。要配超时与升级：

```java
// 概念：定时任务检查"待审核"状态，超时则升级/默认
@Scheduled(fixedDelay = 60000)
public void checkOverdue() {
    pendingTasks.stream()
        .filter(t -> t.waitMinutes() > 24 * 60)   // 超24小时
        .forEach(t -> {
            t.escalateTo(manager);                 // 升级给上级
            // 或默认驳回/转线下工单
        });
}
```

默认策略不能是"永远等"。金融场景常见：超时自动升级信贷主管；客服场景：超时转人工工单队列。总之要有明确的"超时归宿"。

---

## 十一、HITL 嵌入 Supervisor

HITL 常出现在 Supervisor 模式的某个分支。比如 Supervisor 分发到"放款 Agent"，但该 Agent 执行前必须经过人工终审节点（lesson03 + lesson05 组合）：

```
Supervisor ─▶ 放款Agent ─▶ [HITL 人工终审] ─(通过)─▶ 执行放款
                                       └(拒绝)─▶ 通知客户
```

这就是"中心化控制 + 人工介入"的标配形态，金融/政企审批流几乎都是这个结构。

---

## 十二、面试自测

1. **HITL 两种形态？** —— 同步阻塞（简单）、异步中断-恢复（生产，状态持久化+threadId resume）。
2. **interrupt 原语干嘛？** —— 节点主动暂停+落库，释放连接，恢复时不丢上下文。
3. **超时怎么办？** —— 升级/默认策略，杜绝无限等待。
4. **和状态同步关系？** —— HITL=跨时间的状态同步，人作为外部节点读写状态。

---

## 十四、HITL 与合规：监管视角

在金融、医疗、政企，HITL 不是"体验优化"而是"合规刚需"。监管要求"重要决策可解释、可追责、有人负责"：

- **可解释**：人工审核时能看到模型依据（lesson05 审批 UI）。
- **可追责**：谁审的、何时审的、依据什么，全留痕（审计）。
- **可撤销**：错误决策能回滚（如误放款能止付）。

```
监管检查视角：
  决策记录 = 模型建议 + 人工决策 + 审批人 + 时间戳 + 依据材料
       │
       ▼
   可生成"监管报送报表"，证明"每笔放款都有人审"
```

没有 HITL 的自动化决策，在这类场景直接不合规。这也是为什么金融 Agent 几乎必然要 HITL。

<callout emoji="💡"><p>求职导向：能说出"HITL 在金融/医疗是合规刚需而非优化项，对应可解释/可追责/可撤销三要求"，面试官（尤其金融/政企背景）会立刻认定你是"落地过的人"，而不是只会调 API 的。</p></callout>

---

## 十五、行业落地对比

不同行业对 HITL 的依赖度不同：

| 行业 | HITL 介入点 | 强度 |
|------|-----------|------|
| 金融 | 放款/退款/风控终审 | 强（必审） |
| 医疗 | 诊断建议/用药 | 强（必审） |
| 电商客服 | 退款/补偿 | 中（阈值触发） |
| 内容生成 | 对外发布 | 中（敏感词触发） |
| 内部工具 | 几乎不需要 | 弱 |

规律：越涉及"对外、涉钱、涉人"的动作，HITL 越强。内部探索性工具可弱化甚至不要。

---

## 十六、面试自测补充

1. **HITL 为什么金融是刚需？** —— 合规要求可解释/可追责/可撤销，自动化决策不满足。
2. **HITL 节点怎么不断点？** —— interrupt 原语持久化状态 + threadId resume。
3. **超时怎么办？** —— 升级/默认策略，杜绝无限等待。
4. **不同行业强度？** —— 涉钱涉人涉外的强，内部工具弱。

---

## 十八、HITL 的实现模式：同步 vs 异步选型

lesson05 提了两种形态，给选型准则：

| 场景 | 选 |
|------|-----|
| 审批可在秒级内完成（如内部规则确认） | 同步阻塞（简单） |
| 审批要分钟~小时（如人工复核工单） | 异步中断-恢复（生产必选） |
| 高并发、不能占连接 | 异步中断-恢复 |

同步阻塞的坑：一个审批等 30 分钟，HTTP 线程（默认 200 个）被占满，整个服务不可用了。所以**只要审批可能超过几秒，必须异步**。

```java
// 异步形态的核心：invoke 立即返回"待审核"，状态落库；resume 另起请求
CompiledGraph g = build(client);
// 第一次：执行到 human 节点中断，返回
OverAllState partial = g.invoke(input, cfg).get();
// 前端展示待审核，人点通过 → 第二次请求带 human_decision resume
OverAllState finalState = g.invoke(
        Map.of("human_decision", "APPROVE"), cfg).get();
```

---

## 十九、HITL 与 Supervisor 的嵌套：多层级审批

某些场景要"多级审批"：先组长审，再主管审。用 Graph 嵌套条件边实现：

```
START → [经办Agent] → [组长审批节点: interrupt]
                           │ APPROVE
                           ▼
                      [主管审批节点: interrupt]
                           │ APPROVE → END（执行）
                           │ REJECT → 通知经办
```

每级审批都是一个 HITL 节点，前一级通过才进入下一级。这是金融"多级授权"的标准实现（如大额转账需双人复核）。

<callout emoji="⚠️️<p>多级审批的超时处理更复杂：每级都要独立超时与升级。生产里用"审批 SLA"约束每级时限，超时自动升级上一级或转紧急工单，避免卡在中间级。</p></callout>

---

## 二十、真实代码：可恢复的 HITL 服务

把前面的概念落成一个可恢复 HITL 服务的骨架：

```java
@RestController
@RequestMapping("/agent/hitl")
public class HitlController {
    private final CompiledGraph graph;

    // ① 启动任务，执行到人工节点即返回"待审核"
    @PostMapping("/start")
    public Map<String,Object> start(@RequestBody Map<String,Object> input) {
        String tid = UUID.randomUUID().toString();
        graph.invoke(input, RunnableConfig.builder().threadId(tid).build());
        return Map.of("threadId", tid, "status", "AWAIT_DECISION");
    }

    // ② 人工审批后 resume，注入决策继续
    @PostMapping("/resume/{tid}")
    public Map<String,Object> resume(@PathVariable String tid,
                                      @RequestBody Map<String,Object> decision) {
        OverAllState r = graph.invoke(decision,
                RunnableConfig.builder().threadId(tid).build()).get();
        return Map.of("status", "DONE", "result", r.value("result").orElse(""));
    }
}
```

这是 HITL 在 Web 服务里的标准形态：两段式 API（start/resume），用 threadId 串联。前端轮询或 SSE 订阅"待审核"状态。

---

## 二十二、HITL 与审计的强绑定

lesson05 讲 HITL，必须和审计绑定——每一次人工决策都要留痕，否则"人审了但说不清为什么"，合规上等于没审。

```
人工决策事件 = {
  task_id, thread_id,
  agent_suggestion,      // 模型建议
  human_decision,        // 通过/拒绝/修改
  reviewer,              // 审核人
  timestamp,
  evidence               // 依据材料引用
}
```

落库后，监管检查能还原"每笔放款/退款是谁审的、基于什么依据"。这是金融/医疗合规审计的硬要求（对应第十二章 Agent 行为审计）。

<callout emoji="⚠️️<p>审计数据要防篡改：用只追加（append-only）存储或区块链存证，避免"事后改审计记录"的合规事故。审计日志一旦可改，就失去信任基础。</p></callout>

---

## 二十三、HITL 的用户体验设计

HITL 不能只是"弹个确认框"，体验设计影响采纳率：

- **上下文完整**：展示模型建议 + 依据 + 风险，人才能做对决策（lesson05 审批 UI）。
- **一键操作**："通过/拒绝"一键，别让人填一堆表单。
- **批量处理**：同类决策可批量确认（如"这 10 笔退款都通过"），降人工成本。
- **可反驳**：人拒绝后，Agent 要能基于拒绝理由调整（如"换方案"），而非死循环重提同一请求。

<callout emoji="💡"><p>经验：HITL 的设计核心是人"愿意用"。如果审核界面难用，人会无脑点"通过"变成橡皮图章——那 HITL 就形同虚设。好的 HITL 让人在 5 秒内做出有依据的决策。</p></callout>

---

## 二十四、面试自测终版

1. **HITL 为什么金融是刚需？** —— 合规要求可解释/可追责/可撤销。
2. **中断原语？** —— interrupt() 持久化+释放连接，threadId resume 续跑。
3. **和审计强绑定？** —— 每次人工决策留痕（建议/决策/人/依据），append-only 防篡改。
4. **多级审批？** —— 嵌套 HITL 节点，前级过才进下级，配每级超时升级。

---

## 二十五、小结

这一节我们把"人"请回了 Agent 流程：

1. HITL 适用：高风险动作、模糊决策、责任归属——金融/医疗/政企是刚需。
2. 两种形态：同步阻塞（简单）、异步中断-恢复（生产推荐，状态持久化 + threadId resume）。
3. Graph 实现：人工节点作为"中断点"冻结状态，外部 resume 注入 `human_decision` 后续跑；可形成"驳回→重写→再审核"闭环。
4. HITL = 跨时间的状态同步：人作为外部节点通过状态读写参与协作。
5. 工程清单：中断点精、状态落库、超时升级、权限审计、幂等恢复、降级兜底。
6. 企业落地：贷款初审"人工终审节点"解耦"模型建议"与"人类决策"。

下一节（lesson06）我们换个框架视角，看 **AgentScope Java 的 ReAct 范式**——思考-行动-观察循环，这是 Loop 模式的"智能版"，也是 Agent"自主干活"的核心机制。

---

## 延伸阅读 / 引用文档

1. LangGraph Human-in-the-loop 官方文档：https://langchain-ai.github.io/langgraph/concepts/human_in_the_loop/
2. Spring AI Alibaba Graph 中断与恢复（interrupt）：https://java2ai.com/docs/1.0.0.2/tutorials/graph/
3. AgentScope Java HITL（PlanNotebook 人工确认）：https://java.agentscope.io/v1/en/docs/task/plan.html
4. Anthropic 工具使用与人工介入实践：https://www.anthropic.com/research/building-effective-agents
5. Google ADK Human-in-the-loop：https://google.github.io/adk-docs/
6. IEEE 关于人在回路系统的综述（学术参考）：https://ieeexplore.ieee.org/document/9477080

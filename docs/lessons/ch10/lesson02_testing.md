# 第十章第二节：测试策略：单元测试（工具调用模拟）、集成测试（端到端流程）、评估指标设计

大家好，我是秋天。

"Agent 怎么测？"这是几乎每个团队上手 LLM 应用都会卡住的问题。传统单测那套"输入确定、输出确定"在 LLM 面前直接失效——你没法断言"模型返回的文案等于某字符串"。这节课讲一套**分层测试策略**：单测管工具与逻辑、集成测管端到端流程、评估指标管"质量好坏"。

学完这节课，你能掌握：

1. **测试金字塔在 Agent 上的变形**：单测（工具/解析）、集成（流程）、评估（质量）三层怎么分工。
2. **单元测试**：用 Mock 模拟 LLM 与工具调用，测逻辑而非测模型。
3. **集成测试**：端到端跑通"输入→Agent→输出"，关注流程不崩。
4. **评估指标设计**：准确率、相关性、幻觉率、成本等怎么量化。
5. **企业场景**：客服 Agent 回归测试、RAG 答案质量门禁。

<callout emoji="💡"><p>招聘映射：JD 里"设计 Agent 测试策略""有 LLM 应用评测经验""CI 质量卡点"是高频项。能说清"哪些该单测、哪些该评估、评估指标怎么定"的人，在 AI 岗面试里非常加分。</p></callout>

---

## 一、Agent 测试的三层结构

```
传统测试金字塔：        Agent 测试三层(变形)：
   单测(多)              单测: 工具/解析/路由逻辑(确定,可断言)
   集成(中)      ──▶    集成: 端到端流程跑通(关注不崩/不超时)
    e2e(少)              评估: 质量好坏(准确率/幻觉率/相关性)
                        （取代传统 e2e 的"精确断言"）
```

核心认知：**LLM 的"输出正确性"无法用等号断言，只能用"评估指标"度量**。所以 Agent 测试把传统 e2e 拆成了"流程集成测 + 质量评估"两部分。

<callout emoji="✅"><p>判断框架：凡是确定性的（工具调用参数、JSON 解析、路由分支、重试逻辑）全部单测断言；凡是依赖模型语义的（回答质量）用评估指标 + 阈值门禁。</p></callout>

---

## 二、单元测试：Mock LLM 与工具

单测的核心原则：**别在单测里真调模型**。用 Mock 桩替代 ChatModel，只验证"你的代码逻辑"对不对。

```java
// ch10-engineering/src/test/java/demo/testing/RouterTest.java
package demo.testing;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class RouterTest {

    /** 用一个固定返回的 Mock 模型，验证路由逻辑而非模型 */
    @Test
    void testRouterClassifyRefund() {
        ChatLanguageModel mockModel = mock(ChatLanguageModel.class);
        when(mockModel.generate(any()))
            .thenReturn(Response.from(AiMessage.from("REFUND")));

        // 被测对象：路由分类器
        var classifier = DemoRouterClassifier.create(mockModel);
        String intent = classifier.classify("我要退款");
        assertEquals("REFUND", intent);
    }

    @Test
    void testRouterFallbackOnUnknown() {
        ChatLanguageModel mockModel = mock(ChatLanguageModel.class);
        when(mockModel.generate(any()))
            .thenReturn(Response.from(AiMessage.from("UNKNOWN")));
        var classifier = DemoRouterClassifier.create(mockModel);
        assertEquals("UNKNOWN", classifier.classify("随便聊聊"));
    }
}
```

```java
// ch10-engineering/src/main/java/demo/testing/DemoRouterClassifier.java
package demo.testing;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public class DemoRouterClassifier {
    interface C {
        @SystemMessage("分类，只输出 REFUND/TECH/UNKNOWN")
        String classify(@UserMessage String msg);
    }
    public static C create(ChatLanguageModel m) { return AiServices.create(C.class, m); }
}
```

<callout emoji="⚠️"><p>单测里 `when(mockModel.generate(...)).thenReturn(...)` 是关键技巧。你测的是"路由把模型输出映射成分支"这条逻辑，而不是模型本身——模型好坏交给评估层。</p></callout>

---

## 三、工具调用的单测

Agent 的工具（Tool）是确定性代码，必须重点单测：参数解析、边界、异常。

```java
// ch10-engineering/src/test/java/demo/testing/ToolTest.java
package demo.testing;

import dev.langchain4j.agent.tool.Tool;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ToolTest {

    static class OrderTool {
        @Tool("按订单号查状态")
        public String query(String orderId) {
            if (orderId == null || orderId.isBlank())
                throw new IllegalArgumentException("orderId 不能为空");
            return "订单" + orderId + "已发货";
        }
    }

    @Test
    void testQueryNormal() {
        assertEquals("订单A100已发货", new OrderTool().query("A100"));
    }

    @Test
    void testQueryEmptyThrows() {
        assertThrows(IllegalArgumentException.class, () -> new OrderTool().query(""));
    }
}
```

工具单测是"性价比最高"的测试——它完全确定、跑得快、能拦住大部分 Agent 崩坏（参数错、NPE、下游异常）。

---

## 四、解析层的单测：JSON 契约

链/编排里"模型输出 → POJO"的解析最容易出错，必须单测覆盖。

```java
// ch10-engineering/src/test/java/demo/testing/ParseTest.java
package demo.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ParseTest {
    record Diff(String orderId, java.math.BigDecimal gap) {}

    @Test
    void testParseValid() throws Exception {
        String json = "{\"orderId\":\"A1\",\"gap\":10.0}";
        Diff d = new ObjectMapper().readValue(json, Diff.class);
        assertEquals("A1", d.orderId());
        assertEquals(0, d.gap().compareTo(new java.math.BigDecimal("10.0")));
    }

    @Test
    void testParseMissingFieldThrows() {
        String json = "{\"orderId\":\"A1\"}"; // 缺 gap
        assertThrows(Exception.class,
            () -> new ObjectMapper().readValue(json, Diff.class));
    }
}
```

<callout emoji="💡"><p>真实项目里，建议给每个"模型→结构化"步骤配一个 fixture（样例 JSON），CI 里反复跑解析，防止 prompt 一改就吐出解析不了的格式。</p></callout>

---

## 五、集成测试：端到端流程跑通

集成测关注"整条链路不崩、不超时、产出形态正确"，不追求语义精确。可以用 `testcontainers` 起真实依赖，或用录制的模型响应做"回放"。

```java
// ch10-engineering/src/test/java/demo/testing/IntegrationTest.java
package demo.testing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class IntegrationTest {

    /** 端到端：输入工单 → 经过 分类→摘要→生成 → 输出非空且含兜底入口 */
    @Test
    void testCustomerServiceFlow() {
        // 真实应注入 ChatModel(mock 或录制响应) + 真实工具(内存版)
        String ticket = "我要退款";
        String result = CustomerServiceFlow.run(ticket); // 被测流程
        assertNotNull(result);
        assertTrue(result.length() > 5, "产出不应为空");
        // 仅断言"形态"，不断言"具体文案"
        assertTrue(result.contains("人工") || result.contains("客服"),
            "回复应含兜底入口");
    }
}
```

```
集成测 vs 评估测 的区别：
  集成测: assert 流程跑通、产出形态对、耗时<阈值   (工程正确性)
  评估测: 打分"回复质量是否好"、幻觉率是否低        (语义正确性)
```

---

## 六、评估指标设计

这是 Agent 测试最特别的一块。指标分两类：**任务级**（对不对）和**系统级**（贵不贵/慢不慢）。

```
任务级指标(质量):
  准确率(Accuracy)   ─ 分类/抽取对错
  相关性(Relevance)  ─ 回答是否切题
  忠实度(Faithfulness)─ 是否基于给定上下文(防幻觉)
  完整性(Completeness)─ 是否覆盖所有要点
  合规性(Compliance) ─ 是否违反安全/业务规则

系统级指标(工程):
  token/请求、P99延迟、工具成功率、成本/千次
```

用 LangChain4j 的 `Evaluator` 接口或 RAGAS 思路做自动评估：

```java
// ch10-engineering/src/test/java/demo/testing/EvalMetric.java
package demo.testing;

import java.util.List;

public class EvalMetric {

    /** 忠实度: 回答中的每个断言是否都能在上下文中找到依据(简化版) */
    static double faithfulness(String answer, String context) {
        // 真实用 LLM-as-Judge 或 NLI 模型；这里用关键词命中率示意
        String[] claims = answer.split("[。.!]");
        if (claims.length == 0) return 0;
        long supported = java.util.Arrays.stream(claims)
            .filter(c -> context.contains(c.trim().substring(0, Math.min(4, c.trim().length()))))
            .count();
        return (double) supported / claims.length;
    }

    public static void main(String[] args) {
        String ctx = "退款将在3个工作日内原路返回";
        String ans = "退款3个工作日内到账";
        System.out.println("忠实度=" + String.format("%.2f", faithfulness(ans, ctx)));
    }
}
```

<callout emoji="✅"><p>指标要"可回归"。每次 prompt/模型改动后跑同一批评估集，对比指标涨跌，跌了就卡住合并（CI 门禁）。这是第十六章评测闭环的落地形态。</p></callout>

---

## 七、评估集（Eval Set）的构建

评估指标要跑在"评估集"上——一批固定输入 + 标准/参考答案。

```yaml
# ch10-engineering/src/test/resources/evalset.yml
evalset:
  - id: t1
    input: "我要退款"
    intent: REFUND
    expect_keywords: [退款, 人工]
  - id: t2
    input: "APP 崩溃了"
    intent: TECH
    expect_keywords: [技术, 排查]
  - id: t3
    input: "你们服务太差"
    intent: COMPLAINT
    expect_keywords: [安抚, 升级]
```

```java
// ch10-engineering/src/test/java/demo/testing/EvalSetTest.java
package demo.testing;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class EvalSetTest {
    // 读取 evalset.yml，逐条跑流程并核对意图与关键词
    @Test
    void testEvalSet() {
        List<EvalCase> cases = EvalLoader.load("evalset.yml");
        for (EvalCase c : cases) {
            String out = CustomerServiceFlow.run(c.input());
            assertEquals(c.intent(), classify(out), "意图应匹配: " + c.id());
            for (String kw : c.expectKeywords())
                assertTrue(out.contains(kw), c.id() + " 缺少关键词 " + kw);
        }
    }
    // EvalCase / EvalLoader / classify 为示意辅助类
}
```

---

## 八、CI 质量门禁

把测试和评估接进 CI，分两道门：

```
CI 流水线：
  [1] 编译 + 单测(Mock模型)        ── 快, 必过
  [2] 集成测(回放/录制响应)        ── 流程不崩
  [3] 评估测(跑 evalset, 算指标)   ── 质量门禁
        │
        ├─ 准确率 < 阈值(如0.9) → 阻断合并
        └─ 幻觉率 > 阈值(如0.05) → 阻断合并
```

```yaml
# .github/workflows/agent-ci.yml (示意)
name: agent-ci
on: [pull_request]
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: mvn test                  # 单测+集成
      - run: mvn -P eval test          # 评估集门禁
        env:
          OPENAI_API_KEY: ${{ secrets.OPENAI_API_KEY }}
```

<callout emoji="⚠️"><p>评估测会真调模型（花钱），CI 里建议只对"评估集"这个小集合跑，且设预算上限，防止 PR 一多把额度烧光（呼应第六节成本治理）。</p></callout>

---

## 九、企业场景：客服 Agent 回归与 RAG 质量门禁

```
场景A 客服回归：
   每次改 prompt/路由 → CI 跑 evalset(500条历史工单)
   → 对比"意图准确率/兜底率"与上个版本
   → 跌了就红, 产品经理 review

场景B RAG 质量门禁：
   改了切分/检索策略 → 跑 RAGAS(忠实度/相关性)
   → 忠实度 < 0.95 阻断上线
   → 防止"检索一改, 幻觉暴涨"悄悄上线
```

```
测试策略全景：
   单测(工具/解析/路由) ── 快, 全 Mock, 每次提交必跑
   集成(端到端流程)     ── 回放, 关注不崩/不超时
   评估(质量指标+门禁)  ── evalset, 关注质量涨跌, CI 卡点
```

---

## 十、常见测试反模式

```
反模式                      为什么错
─────────────────────────────────────────────────────
单测里真调模型              慢、不稳定、烧钱、CI 抖动
断言"模型输出==某字符串"     LLM 非确定, 永远脆弱
只测 happy path             Agent 崩在多分支/异常, 必须测 FAIL/兜底
评估集太小(几条)            指标方差大, 不可信
评估只在本地跑              回归无从对比, 门禁形同虚设
```

<callout emoji="💡"><p>一句话记住：确定性的全单测，语义性的全评估，二者用 CI 门禁绑死。这套组合是 Agent 能"持续交付"的护城河。</p></callout>

---

## 小结

- Agent 测试三层：单测（工具/解析/路由，全 Mock 断言）、集成（端到端流程不崩）、评估（质量指标+门禁）。
- 单测核心是 Mock `ChatModel` 与工具，测你的逻辑而非模型；工具与解析层是确定性高价值测试点。
- 评估指标分任务级（准确率/相关性/忠实度/合规）与系统级（token/延迟/成功率）。
- 评估集 + CI 门禁实现"质量回归"，改 prompt/模型后指标跌了就阻断合并。
- 测试反模式：真调模型、断言精确字符串、只测 happy path、评估集过小。

## 延伸阅读 / 引用文档

1. LangChain4j 测试与评估：https://docs.langchain4j.dev
2. RAGAS 评估框架：https://docs.ragas.io
3. JUnit 5 文档：https://junit.org/junit5/docs/current/user-guide/
4. Testcontainers（集成测试）：https://testcontainers.com/
5. Mockit 文档：https://site.mockito.org/
6. 第十六章评测闭环（EvalScope/LLM-as-Judge）：https://modelscope.github.io/evalscope/

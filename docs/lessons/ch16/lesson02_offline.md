# 第十六章第2节：离线评测——RAGAS、单元测试与组件级评测

大家好，我是秋天。

上节课搭了评测金字塔和四层指标的总纲。这节课我们下钻到最底层——**离线基准评测**，因为它是你"每日必跑的体温计"，最便宜、最可重复、最能接 CI。重点讲三块：RAGAS 三大核心指标、单元测试怎么写、组件级评测（检索命中率/MRR、工具选择正确率）。

这节课讲什么、学完掌握什么：

- 讲什么：RAGAS 的 Faithfulness / Answer Relevancy / Context Recall 是什么、怎么算、怎么用 Python 跑；组件级评测为什么比端到端先做的道理；以及 Java 侧怎么把这些评测集成进工程。
- 学完掌握：你能用 RAGAS 对一个 RAG 链路出评测报告；你能写检索命中率/MRR、工具选择正确率的评测代码；你知道 Java 工程里怎么挂这些评测。

<callout emoji="💡"><p>招聘映射：JD 原话"熟悉 RAGAS 等评测框架""能设计 RAG 组件级评测（召回/重排/工具选择）""有离线评测集与 CI 集成经验"。这节课的 RAGAS + 组件级评测，就是这条要求的标准答案。</p></callout>

---

## 一、为什么从离线、从组件级开始

先讲个顺序问题。很多团队一上来就想"端到端评测整个 Agent"，结果跑不出来、归因不了。**正确顺序是：先把组件一个个评测准，再拼端到端。**

```
推荐评测顺序（由小到大）：
  单元测试（单函数/单组件）  → 组件级（检索/工具选择）  → RAGAS（问答质量）
                            → 端到端（沙箱/在线，lesson01②③）
```

理由：组件级指标可解释、好归因。端到端挂了，你先怀疑是检索烂还是生成烂？组件级指标直接告诉你"检索 Recall 只有 0.4"，定位快。

<callout emoji="✅"><p>工程经验：RAG 链路 80% 的质量问题出在"检索"而不是"生成"。所以离线评测先把检索（命中率/MRR/Recall）测透，性价比最高。这也是为什么这节课把组件级评测放在 RAGAS 之前讲。</p></callout>

---

## 二、RAGAS 三大核心指标

RAGAS（Retrieval Augmented Generation Assessment）是 RAG 离线评测的事实标准。它用 LLM 当裁判（LLM-as-Judge 的雏形，lesson03 细讲），算出几个无参考答案也能算的指标。我们聚焦三个最常用的：

### 2.1 Faithfulness（忠实度）——"答案有没有编造"

衡量：给定上下文，答案里的每一个论断是否都能在上下文里找到依据。**这是 RAG 的生命线指标**——编造（幻觉）是要命的。

```
Faithfulness 计算逻辑（简化）：
  1. 把答案拆成若干"论断"(claims)
  2. 对每个论断，让 LLM 判断"能否从 context 推出"
  3. 忠实度 = 可推出的论断数 / 总论断数
  例：
    context: "退款时效为24小时"
    answer:  "退款24小时内到账，换货需寄回"
    claims: ["退款24小时内到账"(✓有依据), "换货需寄回"(✗无依据)]
    Faithfulness = 1/2 = 0.5  ← 偏低，有幻觉
```

### 2.2 Answer Relevancy（答案相关性）——"答得切不切题"

衡量：答案是否真的回答了问题，且用词简洁不跑偏。和上下文无关，只看"问题-答案"对。

```
Answer Relevancy 计算逻辑（简化）：
  1. 用 LLM 基于 answer 反向生成几个"问题"
  2. 算这些生成问题和原问题的语义相似度
  3. 相似度高 → 答案相关
  例：问题"退款多久"，答案"退款24小时内到账" → 反向问题接近原问题 → 高相关
      答案"我们支持多种支付方式" → 反向问题偏离 → 低相关
```

### 2.3 Context Recall（上下文召回）——"该检索的检索到了没"

衡量：标准答案里需要的事实，有多少比例出现在检索到的 context 里。**这是检索质量的指标**，不依赖生成。

```
Context Recall 计算逻辑（简化）：
  1. 把"标准答案"拆成需检索的事实点
  2. 检查每个事实点是否在 retrieved context 中
  3. Recall = 命中事实点 / 总事实点
  例：标准答案需 5 个事实点，retrieved context 覆盖 4 个 → Recall = 0.8
```

<callout emoji="⚠️"><p>三个指标各自卡不同的环节：Faithfulness 卡"生成不编造"、Answer Relevancy 卡"答得切题"、Context Recall 卡"检索够全"。RAG 质量 = 检索(Context Recall) × 生成(Faithfulness/Relevancy)。只盯一个会漏问题。</p></callout>

---

## 三、用 Python 跑 RAGAS（实战）

RAGAS 是 Python 生态库，评测章节用 Python 跑最顺手。下面给一个**完整可运行**的例子。

### 3.1 准备数据集

```python
# ch16-evaluation/ragas_demo/dataset.py
# 黄金集：每条含 question / answer / contexts / ground_truth
# 注意：answer 和 contexts 来自你的 RAG 系统实际输出，ground_truth 是人工标注

samples = [
    {
        "question": "退款一般多久到账？",
        "answer": "退款审核通过后24小时内原路退回。",
        # contexts 来自你的检索系统返回（实际跑出来的）
        "contexts": [
            "退款审核通过后24小时内原路退回至原支付账户。",
            "夏季新品满300减50，不参与退款叠加。",
        ],
        "ground_truth": "退款审核通过后24小时内原路退回。",
    },
    {
        "question": "吊牌摘了还能退货吗？",
        "answer": "吊牌完好方可退货，吊牌摘除不支持无理由退货。",
        "contexts": [
            "吊牌完好、不影响二次销售方可退货。",
            "食品类不支持7天无理由。",
        ],
        "ground_truth": "吊牌完好方可退货。",
    },
]
```

### 3.2 跑评测

```python
# ch16-evaluation/ragas_demo/run_eval.py
import os
from datasets import Dataset
from ragas import evaluate
from ragas.metrics import (
    faithfulness,
    answer_relevancy,
    context_recall,
)
from langchain_openai import ChatOpenAI, OpenAIEmbeddings

# 用你的模型当"裁判 LLM"（RAGAS 本质是 LLM-as-Judge）
os.environ["OPENAI_API_KEY"] = os.getenv("OPENAI_API_KEY")
judge_llm = ChatOpenAI(model="gpt-4o-mini", temperature=0)
judge_embeddings = OpenAIEmbeddings(model="text-embedding-3-small")

data = Dataset.from_list(samples)  # samples 见 dataset.py

result = evaluate(
    data,
    metrics=[faithfulness, answer_relevancy, context_recall],
    llm=judge_llm,
    embeddings=judge_embeddings,
)

print(result)
print(result["faithfulness"], result["answer_relevancy"], result["context_recall"])
# 输出示例：
# {'faithfulness': 0.92, 'answer_relevancy': 0.95, 'context_recall': 0.80}
```

```bash
# 运行
cd ch16-evaluation/ragas_demo
pip install ragas datasets langchain-openai
export OPENAI_API_KEY=sk-...
python run_eval.py
```

<callout emoji="💡"><p>RAGAS 本质是"用 LLM 当裁判"算指标（这就是 lesson03 的 LLM-as-Judge 的自动化形态）。所以 RAGAS 的结果也受裁判模型影响——换 gpt-4o 和 gpt-4o-mini 当裁判，分数会有偏差，这就是 lesson03 要讲的"校准"问题。</p></callout>

### 3.3 结果怎么解读与卡点

```
Faithfulness < 0.9  → 生成有编造风险，排查上下文质量/提示词
Answer Relevancy < 0.85 → 答非所问，排查检索是否命中正确文档
Context Recall < 0.8 → 检索不全，排查切分/embedding/召回数

CI 卡点示例（伪代码）：
  if faithfulness < 0.9: fail_pipeline("幻觉风险")
  if context_recall < 0.8: fail_pipeline("检索不全")
```

---

## 四、单元测试：把评测做到"函数级"

RAGAS 测的是"问答质量"，但很多逻辑是确定性代码（解析、路由、工具调用），这些用普通单元测试更准更便宜。

### 4.1 Java：解析与路由的单元测试

```java
// ch16-evaluation/src/test/java/demo/eval/ParsingTest.java
package demo.eval;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

// 评测"结构化输出解析"这个确定性组件
class ParsingTest {

    record RefundResult(String orderId, boolean approved, String reason) {}

    @Test
    void should_parse_valid_json() throws Exception {
        String llmOutput = "{\"orderId\":\"A123\",\"approved\":true,\"reason\":\"within 24h\"}";
        RefundResult r = new ObjectMapper().readValue(llmOutput, RefundResult.class);
        assertThat(r.orderId()).isEqualTo("A123");
        assertThat(r.approved()).isTrue();
    }

    @Test
    void should_handle_model_garbage_prefix() throws Exception {
        // 模型常在前缀加"好的，结果是："，要容错解析
        String llmOutput = "好的，结果是：{\"orderId\":\"A123\",\"approved\":false}";
        String json = llmOutput.substring(llmOutput.indexOf("{"));
        RefundResult r = new ObjectMapper().readValue(json, RefundResult.class);
        assertThat(r.orderId()).isEqualTo("A123");
    }
}
```

### 4.2 Java：用 MockChatModel 固定输出测组装逻辑

Spring AI 提供 `MockChatModel`，让你"不调真模型"就能测提示词组装、路由逻辑：

```java
// ch16-evaluation/src/test/java/demo/eval/RouterTest.java
package demo.eval;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.test.MockChatModel;
import static org.assertj.core.api.Assertions.*;

// 评测"问题路由"：短问题应路由到 FAQ，长问题到 RAG
class RouterTest {

    @Test
    void short_question_routes_to_faq() {
        ChatModel mock = new MockChatModel();   // 固定输出，不花真钱
        Router router = new Router(mock);
        String dest = router.route("退款多久");
        assertThat(dest).isEqualTo("FAQ");
    }
}
```

<callout emoji="✅"><p>单元测试的价值：确定性逻辑（解析、路由、脱敏、格式校验）不该用 LLM 评测——又慢又贵又不稳定。用普通 JUnit + Mock 模型，秒级、零成本、可重复。把"能确定性测的"和"必须 LLM 测的"分开，是评测工程化的基本功。</p></callout>

---

## 五、组件级评测：检索与工具

这一节是离线评测里**性价比最高**的部分，也是最容易被忽视的。

### 5.1 检索命中率（Hit Rate / Recall@K）

衡量：在 Top-K 检索结果里，是否包含了"能回答该问题的正确文档"。

```python
# ch16-evaluation/component/retrieval_eval.py
# 检索组件评测：Hit Rate@K 与 MRR
import numpy as np

def hit_rate_at_k(relevant_docs_per_query, retrieved_docs_per_query, k):
    """相关文档是否在 Top-K 内"""
    hits = 0
    for rel, ret in zip(relevant_docs_per_query, retrieved_docs_per_query):
        topk = set(ret[:k])
        if any(r in topk for r in rel):   # 至少一个相关文档进 Top-K
            hits += 1
    return hits / len(relevant_docs_per_query)

def mrr_at_k(relevant_docs_per_query, retrieved_docs_per_query, k):
    """平均倒数排名：第一个相关文档排第几"""
    rrs = []
    for rel, ret in zip(relevant_docs_per_query, retrieved_docs_per_query):
        rr = 0
        for i, doc in enumerate(ret[:k], start=1):
            if doc in rel:
                rr = 1 / i
                break
        rrs.append(rr)
    return float(np.mean(rrs))

# 示例数据：每个 query 的相关文档 id 列表 + 实际检索返回列表
relevant = [{"d1","d3"}, {"d2"}, {"d5","d6"}]
retrieved = [["d1","d9","d2"], ["d2","d8"], ["d7","d5","d6"]]

print("HitRate@3 =", hit_rate_at_k(relevant, retrieved, 3))  # 3/3 = 1.0
print("MRR@3     =", mrr_at_k(relevant, retrieved, 3))       # 1/1 + 1/1 + 1/2 平均
```

### 5.2 工具选择正确率（Tool Selection Accuracy）

Agent 选错工具是高频故障。这个必须组件级评测：

```java
// ch16-evaluation/src/test/java/demo/eval/ToolSelectionEval.java
package demo.eval;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;

// 评测"工具路由器"：给定意图，是否选对工具
class ToolSelectionEval {

    // 你的工具路由逻辑（简化）：关键词 → 工具
    static String route(String intent, Map<String, String> kwTool) {
        for (var e : kwTool.entrySet()) {
            if (intent.contains(e.getKey())) return e.getValue();
        }
        return "fallback";
    }

    @Test
    void should_select_correct_tool() {
        Map<String, String> kw = Map.of(
                "退款", "refund_tool",
                "物流", "logistics_tool",
                "发票", "invoice_tool");

        // 黄金集：意图 → 期望工具
        List<Map.Entry<String, String>> golden = List.of(
                Map.entry("我要退款", "refund_tool"),
                Map.entry("查物流到哪了", "logistics_tool"),
                Map.entry("开张发票", "invoice_tool"));

        long correct = golden.stream()
                .filter(e -> route(e.getKey(), kw).equals(e.getValue()))
                .count();
        double acc = (double) correct / golden.size();
        assertThat(acc).isGreaterThanOrEqualTo(0.9);  // 正确率门槛
    }
}
```

<callout emoji="⚠️"><p>工具选择正确率是"选对工具 + 参数对"。上面只测了"选对工具"，参数正确性要另测（比如退款金额、订单号是否提取正确）。很多 Agent 工具选对了但参数填错，照样失败——这块评测别漏。</p></callout>

### 5.3 重排序质量（Rerank NDCG）

如果用了重排器，评测它的排序质量：

```python
# ch16-evaluation/component/rerank_eval.py
# NDCG@K：排得越靠前、相关度越高的文档得分越高
import numpy as np

def dcg(relevances):
    return sum((2**r - 1) / np.log2(i + 2) for i, r in enumerate(relevances))

def ndcg_at_k(rel_list, k):
    rel = rel_list[:k]
    ideal = sorted(rel, reverse=True)
    return dcg(rel) / (dcg(ideal) if dcg(ideal) > 0 else 1)

# rel: 各位置文档的相关度(0/1/2)
print("NDCG@5 =", ndcg_at_k([2, 0, 1, 0, 2], 5))
```

---

## 六、Java 侧评测集成思路（不写 Python 也能评）

很多 Java 团队不想引入 Python 评测链路。思路：Java 负责"产出评测数据"（跑 RAG、落日志），Python/RAGAS 负责"算分"，中间用 JSON/文件对接。

```java
// ch16-evaluation/src/main/java/demo/eval/EvalDatasetExporter.java
package demo.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Java 侧：把 RAG 实际运行的 (question, contexts, answer) 导出，
 *  交给 Python/RAGAS 算分。这是 Java 工程挂评测的务实方案。 */
public class EvalDatasetExporter {

    public record EvalRow(String question, List<String> contexts,
                          String answer, String groundTruth) {}

    public static void export(List<EvalRow> rows, String filePath) throws Exception {
        // 导出为 RAGAS 能直接读的 JSONL
        StringBuilder sb = new StringBuilder();
        for (EvalRow r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("question", r.question());
            m.put("contexts", r.contexts());
            m.put("answer", r.answer());
            if (r.groundTruth() != null) m.put("ground_truth", r.groundTruth());
            sb.append(new ObjectMapper().writeValueAsString(m)).append("\n");
        }
        Files.writeString(Path.of(filePath), sb.toString());
    }

    // 配合 CI：
    // 1) Java 跑 RAG 导出 eval.jsonl
    // 2) python ragas_score.py eval.jsonl  → 出指标
    // 3) CI 读指标判断是否卡点失败
}
```

```bash
# CI 流程示意（.gitlab-ci.yml / GitHub Actions）
# step1: mvn -q test -Dtest=RagEvalExportTest   # 导出 eval.jsonl
# step2: python ragas_score.py eval.jsonl        # 算 RAGAS 指标
# step3: 若 faithfulness<0.9 则 exit 1 阻断合并
```

<callout emoji="💡"><p>Java 团队的务实路径：不要为了评测把整个栈迁到 Python。Java 跑业务产出评测样本 → 导出 JSONL → Python RAGAS 算分 → 指标回传 CI 卡点。职责清晰，也符合企业"Java 为主、Python 做算法评测"的现实。</p></callout>

---

## 七、离线评测集（黄金集）怎么建

评测集质量是评测有效的前提。给你建黄金集的原则：

```
黄金集建设原则：
  • 覆盖核心场景 + 边界场景 + 对抗场景（比例约 7:2:1）
  • 每条含 question + 至少 ground_truth（有 context 更佳）
  • 多人标注 + 交叉校验，避免单人偏差
  • 随业务演进持续补充 badcase（接 lesson04 回流）
  • 版本化：eval-v1, eval-v2，回归可比
```

```yaml
# 评测集配置
eval-datasets:
  golden-retrieval:    # 检索组件集（含相关文档 id）
    path: data/retrieval_golden.jsonl
    size: 500
    metrics: [hit_rate@5, mrr@5, ndcg@10]
  golden-ragas:        # RAGAS 集（含 question/contexts/answer/ground_truth）
    path: data/ragas_golden.jsonl
    size: 300
    metrics: [faithfulness, answer_relevancy, context_recall]
  golden-tool:         # 工具选择集
    path: data/tool_golden.jsonl
    size: 200
    metrics: [tool_accuracy, param_accuracy]
```

---

## 八、企业落地：客服 Agent 的离线评测

某银行客服 Agent 的离线评测落地：

```
黄金集：
  • 检索集 500 条（问题 + 应召回政策条款 id）
  • RAGAS 集 300 条（含人工标注 ground_truth）
  • 工具集 200 条（意图 → 期望工具+参数）

门槛（CI 卡点）：
  • HitRate@5 ≥ 0.9
  • Faithfulness ≥ 0.92（金融零幻觉红线）
  • Tool Accuracy ≥ 0.95

效果：
  • 每次改提示词/切分策略/换 embedding，CI 自动跑，
    发现一次"切分改大后 Faithfulness 降到 0.85"被卡住，
    避免了一次线上幻觉事故。
```

**为什么这样设计？** 客服场景"错一句就是投诉+合规"。离线用 Faithfulness 卡死幻觉、用检索集保证知识找得对、用工具集保证动作对。三层离线卡点，把 80% 的问题拦在合并前。

<callout emoji="✅"><p>离线评测的本质是"在合并前就发现回归"。它便宜、快、可重复，是评测金字塔里 ROI 最高的一层。把 RAGAS + 检索/工具组件评测接进 CI，是Agent 工程化的标配，没有例外。</p></callout>

---

## 九、常见反模式

1. **只评测端到端不评测组件**：挂了不知道是哪坏。→ 先组件后端到端。
2. **黄金集只覆盖 happy path**：边界/对抗场景漏测。→ 7:2:1 比例。
3. **RAGAS 分数高就放心**：RAGAS 受裁判模型影响（lesson03 校准）。→ 多模型裁判交叉。
4. **不接 CI，手动跑**：忘了就废了。→ 合并门禁自动跑。

---

## 十、面试自测

1. **RAGAS 三指标各卡什么？** —— Faithfulness(不编造)/Answer Relevancy(切题)/Context Recall(检索全)。
2. **为什么先组件后端到端？** —— 组件可解释好归因，RAG 80% 问题在检索。
3. **Java 怎么挂 RAGAS？** —— Java 导出 JSONL，Python 算分，CI 卡点。
4. **工具评测漏什么？** —— 常漏参数正确性，不只选对工具。

---

## 十一、小结

这节课把离线评测落到代码：

1. **评测顺序**：单元测试 → 组件级（检索/工具）→ RAGAS（问答质量）→ 端到端。先小组大。
2. **RAGAS 三指标**：Faithfulness（生成不编造，生命线）、Answer Relevancy（答得切题）、Context Recall（检索够全）。给了完整 Python 可运行示例。
3. **单元测试**：确定性逻辑（解析/路由）用 JUnit + MockChatModel，秒级零成本。
4. **组件级评测**：检索 HitRate@K / MRR / NDCG，工具选择正确率（含参数），性价比最高。
5. **Java 集成**：Java 导出 JSONL → Python RAGAS 算分 → 指标回传 CI 卡点，务实路径。

下一节（lesson03）我们专门讲"用 LLM 当裁判"——LLM-as-Judge 的校准、偏差与对抗，以及 EvalScope 实战。

---

## 延伸阅读 / 引用文档

1. RAGAS 官方文档：https://docs.ragas.io/
2. RAGAS Metrics 详解（Faithfulness/Relevancy/Recall）：https://docs.ragas.io/en/stable/concepts/metrics/index.html
3. EvalScope 官方文档（国产评测，含 LLM-as-Judge）：https://evalscope.readthedocs.io/
4. LangChain RAG 评测指南：https://docs.langchain.com/oss/python/langsmith/evaluation
5. MS MARCO 检索评测基准（MRR/NDCG 来源）：https://microsoft.github.io/msmarco/
6. Anthropic 评测工程：https://www.anthropic.com/engineering/evaluating-agentic-systems

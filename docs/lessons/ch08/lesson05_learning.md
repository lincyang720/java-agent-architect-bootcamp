# 第八章第五节：学习机制：经验回放、少样本学习、持续学习在 Agent 中的应用

大家好，我是秋天。

上节课的反思，让 Agent 在"一次任务内"自我修正。但这还不够——真正聪明的 Agent，应该**跨任务、跨时间地成长**：这次踩的坑，下次的同类任务别再踩。这就是**学习机制（Learning）**，认知架构的第五根、也是最高阶的支柱。

学完这节课，你能掌握：

1. **经验回放（Experience Replay）**：把过往成功/失败案例存成"经验库"，新任务时检索复用。
2. **少样本学习（In-context Learning）**：用检索到的经验当 few-shot 范例，零训练提升表现。
3. **持续学习（Continual Learning）**：Agent 怎么在不灾难性遗忘的前提下，逐步吸收新知识。

<callout emoji="💡"><p>招聘映射：JD 里"经验沉淀""少样本/上下文学习""持续学习"。这是 Agent 从"单次工具"变"越用越聪明的数字员工"的核心，也是高阶岗的考察点。</p></callout>

---

## 一、为什么 Agent 需要学习

```
不学习的 Agent：
  任务1 踩坑 → 修正 → 任务2 重新踩同样的坑 → 再修正 ...（原地踏步）

会学习的 Agent：
  任务1 踩坑 → 沉淀经验 → 任务2 命中经验直接规避 → 越用越准
```

LLM 的权重是冻结的（除非你做微调，见第十八章）。所以 Agent 的"学习"主要靠**在上下文里注入经验（In-context）**，以及**维护一份可检索的经验记忆**。这叫"系统层面的学习"，不碰模型权重。

---

## 二、经验回放（Experience Replay）

### 2.1 思想

强化学习里有 Experience Replay：把历史 (状态, 动作, 奖励) 存进 buffer，训练时随机采样回放，打破相关性、提升稳定性。Agent 里可类比：把 (任务, 策略, 结果) 存进经验库，新任务时检索相似经验来辅助决策。

```
经验条目结构：
{
  task_signature: "退款类问题",        # 任务特征
  action_taken: "先路由product库+强制引用",
  outcome: "SUCCESS",                  # 或 FAILURE + 原因
  lesson: "退款问题必须带来源否则合规不过",
  embedding: [0.1, 0.3, ...]          # 用于相似检索
}
```

### 2.2 经验库的写入与检索（Java）

```java
// ch08-cognition/src/main/java/demo/learning/ExperienceStore.java
package demo.learning;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.search.Request;

import java.util.List;

public class ExperienceStore {
    private final EmbeddingStore store;
    private final EmbeddingModel emb;

    public ExperienceStore(EmbeddingStore store, EmbeddingModel emb) {
        this.store = store; this.emb = emb;
    }

    /** 一次任务结束，把经验存库 */
    public void record(String taskSignature, String lesson, boolean success) {
        String text = "[经验] 任务:" + taskSignature + " 结论:" + lesson
                + " 结果:" + (success ? "成功" : "失败");
        Embedding e = emb.embed(text).content();
        store.add(e, dev.langchain4j.data.segment.TextSegment.from(text));
    }

    /** 新任务来时，检索最相关经验注入 prompt */
    public List<String> recall(String newTask, int topK) {
        Embedding q = emb.embed(newTask).content();
        return store.search(Request.builder().queryEmbedding(q).maxResults(topK).build())
                .matches().stream()
                .map(m -> m.embedded().text()).toList();
    }
}
```

### 2.3 把经验注入新任务（少样本）

```java
// ch08-cognition/src/main/java/demo/learning/ExperienceAugmentedAgent.java
package demo.learning;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.util.List;

public class ExperienceAugmentedAgent {
    private final ExperienceStore expStore;
    private final ChatLanguageModel model;

    public ExperienceAugmentedAgent(ExperienceStore s, ChatLanguageModel m) {
        this.expStore = s; this.model = m;
    }

    public String solve(String task) {
        // 1) 检索相关经验
        List<String> exps = expStore.recall(task, 3);
        // 2) 作为 few-shot 经验注入 prompt
        String prompt = "你是一个会从经验中学习的Agent。\n"
                + "历史经验:\n" + String.join("\n", exps) + "\n"
                + "新任务:" + task + "\n请参考经验处理。";
        return model.generate(prompt).content();
    }

    public static void main(String[] args) {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY")).modelName("gpt-4o").build();
        // expStore 需提前建好（见上）
        System.out.println("经验增强 Agent 骨架完成");
    }
}
```

<callout emoji="✅"><p>经验回放的精髓：它把"反思"（lesson04）的产出，从"单次修正"升级为"跨任务资产"。失败经验尤其值钱——一条"千万别这么干"的经验，能救 Agent 于无数次同样的坑。生产里建议**失败经验权重高于成功经验**，因为避坑比模仿更重要。</p></callout>

---

## 三、少样本学习（In-context Learning）

### 3.1 原理

大模型有个惊人能力：在 prompt 里给几个范例（few-shot），它就能照着范式做新任务，无需任何训练。这就是 In-context Learning。把经验回放检索到的案例当范例，就是"动态少样本"。

```
静态少样本（写死在 prompt）：
  例1: 输入A → 输出A'
  例2: 输入B → 输出B'
  新输入 → 模型照范式给输出

动态少样本（经验驱动）：
  检索到与"新任务"最像的 2~3 条历史经验 → 当范例 → 处理新任务
```

### 3.2 动态少样本示例

```java
// ch08-cognition/src/main/java/demo/learning/DynamicFewShot.java
package demo.learning;

import dev.langchain4j.model.chat.ChatLanguageModel;

import java.util.List;

public class DynamicFewShot {
    /**
     * 用检索到的经验构造 few-shot prompt
     */
    public static String buildFewShotPrompt(String task, List<String> examples) {
        StringBuilder sb = new StringBuilder("参考以下范例处理新任务:\n");
        for (int i = 0; i < examples.size(); i++) {
            sb.append("范例").append(i + 1).append(": ").append(examples.get(i)).append("\n");
        }
        sb.append("新任务: ").append(task).append("\n请输出:");
        return sb.toString();
    }

    public static void main(String[] args) {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY")).modelName("gpt-4o-mini").build();
        List<String> examples = List.of(
            "任务:退款→先路由product库→强制引用[来源]",
            "任务:合同→路由legal库→标注条款编号");
        String p = buildFewShotPrompt("用户问退货政策", examples);
        System.out.println(model.generate(p).content());
    }
}
```

<callout emoji="⚠️"><p>少样本有几个坑：① 范例必须"真的相似"，塞不相关的范例反而带偏；② 范例数量控制在 2~5 个，太多占 token 且稀释注意力；③ 范例本身要正确，错误的范例会"教坏"模型。经验库写入时务必校验质量（成功且已验证）。</p></callout>

---

## 四、持续学习（Continual Learning）

### 4.1 问题：灾难性遗忘与知识时效

Agent 面对的真实世界在变：新政策上线、新接口变更、新术语出现。它需要持续吸收，但不能"学了新的忘了旧的"（灾难性遗忘），也不能每次都全量重训（成本爆炸）。

### 4.2 三条工程路径

```
路径                  做法                       适用
─────────────────────────────────────────────────────────
RAG 式持续学习        新文档进知识库，检索即用      知识类更新（最常见）
经验库增量            新经验追加，不删旧的          策略类积累
周期性微调            定期把优质经验蒸馏进小模型    高频低风险场景(第十八章)
```

### 4.3 用"知识库 + 经验库"双记忆实现持续学习

```java
// ch08-cognition/src/main/java/demo/learning/ContinualLearner.java
package demo.learning;

import dev.langchain4j.store.embedding.EmbeddingStore;

public class ContinualLearner {
    // 知识记忆：事实类（政策/接口文档），随时可增
    private final EmbeddingStore knowledgeBase;
    // 经验记忆：策略类（踩坑/成功范式），只增不清
    private final ExperienceStore experienceStore;

    public ContinualLearner(EmbeddingStore kb, ExperienceStore es) {
        this.knowledgeBase = kb; this.experienceStore = es;
    }

    /** 新知识上线：增量写入知识库，不影响旧知识（无遗忘） */
    public void absorbNewKnowledge(String doc) {
        // 走 lesson02 切分 + lesson03 embedding + 入库（增量 add，非重建）
        System.out.println("新知识已增量并入知识库（旧知识保留）");
    }

    /** 新经验沉淀：追加进经验库 */
    public void absorbNewExperience(String task, String lesson, boolean ok) {
        experienceStore.record(task, lesson, ok);
    }
}
```

<callout emoji="💡"><p>为什么 Agent 的持续学习首选"RAG 式"而非"微调式"？因为 RAG 式是**非破坏性**的——新知识进库、旧知识原样保留，零遗忘风险、零重训成本、即时生效。微调式（第十八章）虽能压缩知识进权重，但有遗忘风险、重训贵、见效慢。工程上 90% 的持续学习需求，RAG 式就够。</p></callout>

---

## 五、企业落地：客服 Agent 的"越用越聪明"

某电商客服 Agent 的学习闭环：

```
每天产生 10万 次对话
   │
   ▼ 自动评估（LLM-as-Judge，第十六章）打分
   ▼ 成功范式 → 写入经验库（如"价保问题先查价保政策库"）
   ▼ 失败案例 → 诊断原因 → 写入"避坑经验"（如"别把优惠券当现金退"）
   │
   ▼ 次日新对话：检索昨日经验注入 prompt
   ▼ 周维度：高频经验蒸馏进 few-shot 模板 / 周期性微调
   │
   ▼ 三个月后：同类问题一次解决率从 62% → 89%
```

**为什么这样设计？** 客服是"高频、知识易变、经验可复用"的典型场景。把每天的对话变成"学习素材"，用经验回放+少样本让 Agent 持续进化，比每次靠人调 prompt 高效得多。这就是"数字员工"和"一次性脚本"的本质区别。

---

## 六、踩坑清单

| 坑 | 后果 | 解法 |
|----|------|------|
| 经验库不校验质量 | 错误经验带偏 | 写入前 LLM 评估验证 |
| few-shot 范例不相关 | 稀释注意力 | 相似度检索+数量≤5 |
| 持续学习用全量重训 | 成本爆炸+遗忘 | 优先 RAG 式增量 |
| 只存成功经验 | 不会避坑 | 失败经验加权 |
| 经验无限增长 | 检索变慢 | 定期蒸馏+归档 |

---

## 七、经验库的质量门禁（避免"学坏"）

经验库最怕存进错误经验，把 Agent 带偏。写入前加一道质量校验：

```java
// ch08-cognition/src/main/java/demo/learning/ExperienceGate.java
package demo.learning;

import dev.langchain4j.model.chat.ChatLanguageModel;

public class ExperienceGate {
    private final ChatLanguageModel judge;

    public ExperienceGate(ChatLanguageModel judge) { this.judge = judge; }

    /** 写入前校验经验是否正确、是否具通用性 */
    public boolean shouldStore(String lesson) {
        String verdict = judge.generate(
            "判断下面这条经验是否正确且具通用性，只回 PASS/FAIL 及原因：\n" + lesson).content();
        return verdict.contains("PASS");
    }
}
```

---

## 八、经验蒸馏：从"库"到"模板"

经验库无限增长会导致检索变慢、噪声增多。定期把高频经验**蒸馏成 few-shot 模板或规则**，原明细可归档：

```
经验生命周期：
  原始经验(逐条) → 聚类高频模式 → 提炼为通用规则/模板 → 归档原始明细
```

```java
// ch08-cognition/src/main/java/demo/learning/ExperienceDistill.java
package demo.learning;

import java.util.*;

public class ExperienceDistill {
    /** 从经验列表中按频次提炼 top-N 通用规则（示意聚类） */
    public static List<String> distill(List<String> lessons, int topN) {
        Map<String, Integer> freq = new HashMap<>();
        for (String l : lessons) freq.merge(clusterKey(l), 1, Integer::sum);
        return freq.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(topN).map(Map.Entry::getKey).toList();
    }
    private static String clusterKey(String l) { return l.length() > 20 ? l.substring(0, 20) : l; }
}
```

---

## 九、持续学习 vs 微调的边界（接第十八章）

```
维度              持续学习(RAG式)        微调(训练式)
─────────────────────────────────────────────────────
破坏性            无（旧知识保留）        有（可能遗忘）
生效速度          即时                    需训练周期
成本              低                      高（GPU/数据）
知识类型          事实/策略类              ﻿风格/能力类
适用              90% 场景                高频低风险、风格固化
```

<callout emoji="💡"><p>一句话决策：只要新知识是"事实或策略"，用 RAG 式持续学习（进知识库+经验库）就够，零风险零成本；只有当你想让模型"换一种思考风格"且高频稳定时才考虑微调（第十八章）。绝大多数团队在持续学习上过度设计了。</p></callout>

---

## 十、企业落地检查清单

| 能力 | 是否具备 | 落地动作 |
|------|----------|----------|
| 经验可沉淀 | ☐ | 建经验库+写入钩子 |
| 质量门禁 | ☐ | 写入前 LLM 校验 |
| 动态少样本 | ☐ | 检索经验注入 prompt |
| 周期蒸馏 | ☐ | 周级聚类提炼模板 |
| 持续知识 | ☐ | 新文档增量入知识库 |

## 十一、端到端学习闭环示例：客服 Agent 周级进化

```java
// ch08-cognition/src/main/java/demo/learning/WeeklyEvolution.java
package demo.learning;

public class WeeklyEvolution {
    public static void main(String[] args) {
        System.out.println("每日:");
        System.out.println("  对话 → LLM评估打分 → 成功范式入经验库 / 失败避坑入经验库");
        System.out.println("次日:");
        System.out.println("  新对话 → 检索经验 → 注入few-shot → 复用历史智慧");
        System.out.println("周级:");
        System.out.println("  高频经验 → 蒸馏为模板 / 周期性微调(第十八章)");
        System.out.println("结果: 一次解决率 62% → 89%");
    }
}
```

```
学习飞轮：
   对话 ──► 评估 ──► 经验库 ──► 检索复用 ──┐
     ▲                                    │
     └──────────── 飞轮越转越快 ───────────┘
```

---

## 十二、学习机制的红线

| 红线 | 后果 | 防护 |
|------|------|------|
| 经验不校验直接存 | 学坏 | 质量门禁(第七節) |
| 持续学习用全量重训 | 遗忘+成本 | RAG式增量为主 |
| 经验无限增长 | 检索劣化 | 周期蒸馏归档 |
| 把幻觉当经验 | 传播错误 | 仅存已验证结论 |

<callout emoji="⚠️"><p>学习的"质量门禁"比"数量"重要一百倍。一个错误的经验一旦入库，会被少样本无限复用放大。宁可经验库小一点、慢一点，也绝不存未经校验的内容。这是学习机制能信任的前提。</p></callout>

## 十三、学习机制与上下文工程（接第十五章）

经验库本质是一种"长期上下文"。第十五章四大策略对应：

- **Write**：新经验写入经验库（长期记忆）
- **Select**：新任务时从经验库检索相关经验注入（少样本）
- **Compress**：经验蒸馏为模板，去冗余
- **Isolate**：不同领域经验隔离，避免污染

---

## 十四、学习机制的面试话术

```
"我们让 Agent 从经验中学习，而非每次从零：
 - 经验回放：把(任务,策略,结果,教训)存向量库，新任务检索复用
 - 少样本：检索到的经验当 few-shot 范例注入 prompt
 - 持续学习：优先 RAG 式增量（非破坏性、零遗忘）
 - 质量门禁：经验写入前 LLM 校验，防学坏
 结果是一次解决率从 62% 升到 89%。"
```

| 追问 | 加分答法 |
|------|----------|
| 怎么防学坏？ | 质量门禁+仅存已验证 |
| 经验无限增长？ | 周期蒸馏归档 |
| 为什么不选微调？ | RAG式零风险零成本，够用 |

## 十五、学习机制完整参考实现

整合前文，给出一个经验回放闭环：

```java
// ch08-cognition/src/main/java/demo/learning/LearningLoop.java
package demo.learning;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.model.embedding.EmbeddingModel;

public class LearningLoop {
    private final ExperienceStore exp;

    public LearningLoop(EmbeddingStore store, EmbeddingModel emb) {
        this.exp = new ExperienceStore(store, emb);
    }

    /** 任务结束，沉淀经验（带质量门禁） */
    public void onTaskDone(String task, String lesson, boolean ok,
                          ChatLanguageModel judge) {
        if (!new ExperienceGate(judge).shouldStore(lesson)) return; // 门禁
        exp.record("[避坑]" + task, lesson, ok);
    }

    /** 新任务：检索相关经验注入 few-shot */
    public String solveWithExperience(ChatLanguageModel model, String task) {
        var exps = exp.recall(task, 3);
        return model.generate("参考经验:\n" + String.join("\n", exps)
                + "\n新任务:" + task).content();
    }
}
```

---

## 十六、学习机制检查清单

| 能力 | 是否具备 |
|------|----------|
| 经验可沉淀 | ☐ |
| 质量门禁 | ☐ |
| 动态少样本 | ☐ |
| 周期蒸馏 | ☐ |
| 持续知识(RAG式) | ☐ |

<callout emoji="✅"><p>学习机制是认知架构的"复利"。前期投入（建经验库、加门禁）看似费事，但一旦跑起来，Agent 每周都在变聪明，且这种聪明是可沉淀、可审计、可迁移的资产——远比每次靠人调 prompt 高级。</p></callout>

## 小结

这节课把"学习机制"这根支柱讲透了：

- **经验回放**：把 (任务, 策略, 结果, 教训) 存成向量经验库，新任务检索复用，失败经验加权更值钱。
- **少样本学习**：检索到的经验当动态 few-shot 范例注入 prompt，零训练提效；范例要相似、≤5、且正确。
- **持续学习**：首选 RAG 式增量（非破坏性、零遗忘、即时生效），微调式留给高频低风险场景（第十八章）。
- 企业落地用"每日对话→评估→经验沉淀→次日复用→周期蒸馏"闭环，让 Agent 越用越聪明。
- 至此，认知架构五大支柱（自主/规划/工具/反思/学习）全部讲完。

下一节讲自我提示（Self-Prompting）——Agent 怎么动态切换思维模式（lesson06）。

---

## 延伸阅读 / 引用文档

1. Lin et al., *Experience Replay for Continual Learning* (原始概念): https://arxiv.org/abs/1702.03275
2. Brown et al., *Language Models are Few-Shot Learners* (In-context Learning): https://arxiv.org/abs/2005.14165
3. Parisi et al., *Continual Learning in Pre-trained Transformers* (无遗忘持续学习): https://arxiv.org/abs/2111.02088
4. AgentScope ReMe（经验与记忆，第六章/反思关联）: https://github.com/modelscope/agentscope
5. LangChain4j 记忆与检索: https://docs.langchain4j.dev/tutorials/
6. Google ADK for Java（持续学习实践）: https://github.com/google/adk-java

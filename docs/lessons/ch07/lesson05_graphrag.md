# 第七章第五节：GraphRAG：知识图谱与 RAG 融合的前沿探索及 Agentic RAG 概念

大家好，我是秋天。

前面四节我们讲的都是"向量 RAG"——把文档切块、向量化、检索。但你会发现一个天花板：当你问"某公司供应链上游的三级供应商里，哪些通过了 ISO 认证"这种**需要多跳推理、需要把分散在几十份文档里的实体关系串起来**的问题，向量 RAG 直接歇菜——它只能召回"相邻"的 chunk，串不起跨文档的全局关系。

这节课带你看 RAG 的两个前沿方向：**GraphRAG（知识图谱 + RAG）**和 **Agentic RAG（让 Agent 自主决定怎么检索）**。这两个方向 2025–2026 在招聘里已经开始出现，属于"加分项里的高分项"。

学完这节课，你能掌握：

1. **GraphRAG 解决什么问题**：全局性、多跳、实体关系类问答，为什么纯向量 RAG 做不了。
2. **GraphRAG 的构建与查询两阶段**，以及微软开源实现的思路。
3. **Agentic RAG 的核心思想**：让 Agent 自己决定"搜几次、搜哪个库、要不要改写查询"，而非固定链路。

<callout emoji="💡"><p>招聘映射：头部厂（尤其做企业知识图谱、金融风控、医药研发的）JD 已出现"了解 GraphRAG / Agentic RAG"。这节不要求你手撸一个，但要求你能讲清"它比普通 RAG 多解决了什么"，面试时能拉开差距。</p></callout>

---

## 一、普通向量 RAG 的"全局盲区"

先看一个真实痛点。某制造企业知识库有 5000 份文档，用户问：

> "我们的 A 型号电机，二级供应商里有几家位于长三角、且去年季度交付合格率达 99% 以上？"

向量 RAG 会怎样？它把 query 向量化，去库里找"最像"的 chunk。但这个问题的答案分散在：
- 供应商名录（含地域）
- 质量月报（含合格率）
- 物料 BOM（含 A 型号 → 供应商映射）

没有任何单个 chunk 包含完整答案，向量 RAG 只能召回"部分相关"的碎片，拼不出全局结论。这就是**"需要全局聚合 / 多跳推理"的问题类，向量 RAG 的盲区**。

```
普通 RAG 的检索视野（局部）：
   query ──► [chunk A] [chunk B] [chunk C]   ← 只看"相邻文本"
   无法跨文档串联：A型号→供应商→地域→合格率

GraphRAG 的检索视野（全局）：
   query ──► 图查询：A型号 -[使用]-> 供应商X -[位于]-> 长三角
                              -[合格率]-> 99.2%
   实体与关系被显式建模，可多跳遍历
```

---

## 二、GraphRAG 是什么：给知识建一张"关系网"

GraphRAG 的核心思想：**在切块向量化之外，额外从文档里抽取"实体—关系—实体"三元组，建成一张知识图谱**，检索时既做向量召回，也做图遍历。

### 2.1 构建阶段（Indexing）：从文本抽取图

```
原始文档
   │  LLM 抽取
   ▼
实体 (Entity):  A型号电机 / 供应商X / 长三角 / ISO9001
关系 (Relation): A型号电机 -[由...供应]-> 供应商X
                 供应商X  -[位于]-> 长三角
                 供应商X  -[持有认证]-> ISO9001
   │
   ▼
知识图谱 (Graph) + 每个实体的文本摘要（社区摘要）
   │
   ▼
存入图数据库 (Neo4j / NebulaGraph) + 向量库（实体描述向量化）
```

微软的 GraphRAG 实现还做了一件事：**社区检测（Community Detection）**，把图分成若干社区，为每个社区生成摘要。这样用户问"整体情况如何"时，可以先读社区摘要做全局回答，再下钻细节。

### 2.2 查询阶段（Query）：局部 + 全局双模式

- **局部查询（Local）**：针对具体实体，沿图遍历其邻居，拿到精准事实（类似普通 RAG 但带关系）。
- **全局查询（Global）**：针对"总结性/全局性"问题，先读社区摘要，再聚合生成——这是普通 RAG 完全做不到的。

### 2.3 用 Neo4j 存图谱的 Schema 示意

```cypher
// ch07-rag/src/main/resources/graph_schema.cypher
// 实体节点
CREATE (m:Material {name: 'A型号电机', type: '电机'})
CREATE (s:Supplier {name: '供应商X', region: '长三角'})
CREATE (cert:Certification {name: 'ISO9001'})

// 关系
CREATE (m)-[:SUPPLIED_BY]->(s)
CREATE (s)-[:LOCATED_IN]->(:Region {name: '长三角'})
CREATE (s)-[:HAS_CERT]->(cert)
CREATE (s)-[:QUALITY_RATE {value: 0.992, quarter: '2025Q4'}]->(:Metric)

// 查询：A型号电机的二级供应商中，位于长三角且通过ISO9001的
MATCH (m:Material {name:'A型号电机'})-[:SUPPLIED_BY*1..2]-(s:Supplier)
WHERE s.region = '长三角'
  AND (s)-[:HAS_CERT]->(:Certification {name:'ISO9001'})
RETURN s.name
```

### 2.4 Java 侧连 Neo4j 做 GraphRAG 检索

```java
// ch07-rag/src/main/java/demo/GraphRagRetriever.java
package demo;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;

import java.util.List;
import java.util.Map;

/** GraphRAG 图检索：用 Cypher 做多跳关系查询 */
public class GraphRagRetriever {

    public static List<String> findSuppliers(String material, String region, String cert) {
        Driver driver = GraphDatabase.driver(
                "bolt://localhost:7687",
                AuthTokens.basic("neo4j", System.getenv("NEO4J_PASSWORD")));

        try (Session session = driver.session()) {
            String cypher = """
                MATCH (m:Material {name:$mat})-[:SUPPLIED_BY*1..2]-(s:Supplier)
                WHERE s.region = $region
                  AND (s)-[:HAS_CERT]->(:Certification {name:$cert})
                RETURN s.name AS supplier
                """;
            return session.run(cypher,
                    Map.of("mat", material, "region", region, "cert", cert))
                    .list(r -> r.get("supplier").asString());
        } finally {
            driver.close();
        }
    }

    public static void main(String[] args) {
        List<String> res = findSuppliers("A型号电机", "长三角", "ISO9001");
        System.out.println("命中供应商: " + res);
    }
}
```

对应 Maven 依赖：

```xml
<!-- ch07-rag/pom.xml 片段 -->
<dependency>
    <groupId>org.neo4j.driver</groupId>
    <artifactId>neo4j-java-driver</artifactId>
    <version>5.23.0</version>
</dependency>
```

<callout emoji="⚠️"><p>GraphRAG 不是"银弹替代"普通 RAG，而是"补充"。它需要 LLM 抽取实体关系（构建成本高）、需要图数据库（运维复杂）。只有当你的问题确实涉及"多跳、全局聚合、实体关系"时，才值得上。客服 FAQ 这种局部问答，普通向量 RAG 就够了，别为了炫技引入图数据库。</p></callout>

---

## 三、GraphRAG 的工程权衡

```
维度              普通向量 RAG        GraphRAG
─────────────────────────────────────────────────
构建成本          低（切块+向量）      高（LLM抽实体+建图）
查询能力          局部语义            局部+全局+多跳
适合问题          事实/步骤类         关系/聚合类
运维复杂度        低                  高（图数据库）
典型场景          客服/文档问答        风控/供应链/科研
```

<callout emoji="✅"><p>企业落地决策框架：先问"我的问题里，需要跨文档串联实体关系的占比多大？"低于 20% → 不上 GraphRAG，用 lesson04 的混合检索 + rerank 足够；高于 40% 且是核心场景 → GraphRAG 值得投入。折中方案是"向量 RAG 为主，对关键实体集用图做增强"，也就是下面的混合架构。</p></callout>

---

## 四、Agentic RAG：让 Agent 自己决定"怎么搜"

### 4.1 普通 RAG 是"固定管道"，Agentic RAG 是"自主决策"

前面所有 RAG 都是**固定流水线**：query 进来 → 检索 → 生成。但真实问题千奇百怪，固定管道会卡壳：

- 一次检索不够 → 需要基于中间结果**再检索**（多轮）。
- 问题涉及多个知识库 → 需要**先路由**到对的库。
- query 太模糊 → 需要先**改写**再搜。

Agentic RAG 把"检索"也变成一个可被 Agent 调用的**工具（Tool）**，由 Agent 自主决定：搜几次、搜哪个、要不要改写。

```
固定 RAG（管道）：
  Query ─► [检索] ─► [生成] ─► 答案

Agentic RAG（自主）：
  Query
    │
    ▼
  ┌───────── Agent 规划 ─────────┐
  │ 1. 调用 retrieve(知识库A)      │
  │ 2. 看结果不够 → rewrite query │
  │ 3. 调用 retrieve(知识库B)      │
  │ 4. 评估足够 → 调用 generate    │
  └──────────────────────────────┘
```

### 4.2 用 LangChain4j 的 AiServices 实现 Agentic RAG

把检索器包装成 Tool，让 Agent 自主调用。

```java
// ch07-rag/src/main/java/demo/AgenticRag.java
package demo;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;

import java.util.List;

public class AgenticRag {

    // 把三个知识库的检索封装成 Tool
    static class KbTools {
        @Tool("从产品知识库检索")
        String searchProduct(@P("查询") String q) {
            return VectorKb.search("product", q);
        }
        @Tool("从法务合同库检索")
        String searchLegal(@P("查询") String q) {
            return VectorKb.search("legal", q);
        }
        @Tool("从工单历史库检索")
        String searchTicket(@P("查询") String q) {
            return VectorKb.search("ticket", q);
        }
    }

    interface Assistant {
        String chat(String userMessage);
    }

    public static void main(String[] args) {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o").build();

        Assistant agent = AiServices.builder(Assistant.class)
                .chatLanguageModel(model)
                .tools(new KbTools())
                .build();

        // Agent 会自主决定：这个问题该搜哪个库、搜几次
        String ans = agent.chat("我们和供应商X的合同里，关于逾期交付的赔偿条款是什么？");
        System.out.println(ans);
    }
}

// 占位：真实实现里 VectorKb.search 调用各库 embeddingStore.search
class VectorKb {
    static String search(String kb, String q) { return "[来自"+kb+"的检索结果]"; }
}
```

### 4.3 Agentic RAG vs 普通 RAG 的取舍

| 维度 | 普通 RAG | Agentic RAG |
|------|----------|-------------|
| 可控性 | 高（流程固定） | 中（Agent 自主） |
| 复杂问题 | 弱 | 强（多轮检索） |
| 延迟/成本 | 低且稳定 | 高且波动（可能多轮） |
| 调试难度 | 低 | 高（决策路径不透明） |

<callout emoji="💡"><p>Agentic RAG 的"自主"是把双刃剑：它能处理复杂多跳问题，但每多一轮检索就多一次 LLM 调用，延迟和成本不可控，且决策路径难调试。生产建议用"受控 Agentic"——给 Agent 明确的工具边界、最大检索轮数（如 3 轮）、超时熔断，而不是完全放开。</p></callout>

---

## 五、企业落地：GraphRAG + Agentic 的混合蓝图

给你一个真实的金融风控知识库架构：

```
                        用户问题
                           │
              ┌────────────┴────────────┐
              ▼                         ▼
        【Agent 路由层】           【普通向量 RAG】
        判断问题类型                 FAQ/条款类 → 直接答
              │
    ┌─────────┼──────────┐
    ▼         ▼          ▼
 产品库    法务库     图谱层(GraphRAG)
 (向量)    (向量)     多跳关系/全局聚合
    │         │          │
    └─────────┼──────────┘
              ▼
        Re-rank 融合 → 生成 → 带来源答案
```

**为什么这样设计？** 90% 的客服/条款类问题走普通向量 RAG（快、省）；只有"供应商关系""风险传导"这类多跳问题才触发 GraphRAG 图查询；Agent 层负责在两者间路由和决定是否需要多轮。这就是"合适的技术用在合适的刀刃上"。

---

## 六、踩坑清单

| 坑 | 后果 | 解法 |
|----|------|------|
| 所有场景都上 GraphRAG | 成本/运维爆炸 | 仅多跳/全局问题用 |
| GraphRAG 实体抽取不准 | 图谱噪声大 | 领域微调抽取 prompt |
| Agentic 检索无轮数上限 | 成本失控 | 限 3 轮 + 超时熔断 |
| 图与向量不同步 | 答案矛盾 | 同一索引 pipeline 双写 |
| 盲目追求前沿 | 团队 hold 不住 | 先夯实 lesson01-04 |

---

## 七、GraphRAG 的构建 Pipeline 示例

微软 GraphRAG 的索引阶段流程（简化示意）：

```text
GraphRAG 索引阶段：
  1. 文档切分 → 文本单元(text units)
  2. LLM 抽取实体+关系+声明 (claim)
  3. 构建实体/关系图
  4. 社区检测 (Leiden) → 社区分层
  5. 为每个社区生成摘要 (summary)
  6. 实体/关系/社区摘要全部向量化入库
```

```cypher
// 社区摘要作为节点，支持"全局查询"快速聚合
CREATE (c:Community {id:'comm-12', level:1,
  summary:'该社区涵盖长三角供应商的认证与交付表现...'})
```

```java
// ch07-rag/src/main/java/demo/GraphRagIndexer.java
package demo;

public class GraphRagIndexer {
    /** 示意：触发图构建（真实用微软 graphrag index 命令或自研） */
    public static void build(String root) {
        System.out.println("构建图谱: " + root);
        System.out.println("  - 抽取实体/关系 (LLM)");
        System.out.println("  - 社区检测 + 摘要");
        System.out.println("  - 向量化入库 (Neo4j + 向量库)");
    }
}
```

---

## 八、GraphRAG vs Agentic RAG 选型决策

```
问题特征                      推荐
─────────────────────────────────────────────
单文档事实/步骤类             普通向量RAG
需要跨文档实体关系/多跳        GraphRAG
问题路径不确定需多轮检索       Agentic RAG
以上混合                      Agentic + GraphRAG 混合(lesson08)
```

<callout emoji="💡"><p>决策口诀："局部用向量，全局用图，不确定用 Agent"。三者不是替代关系，是互补。生产里常见组合是"向量 RAG 为主 + GraphRAG 补多跳 + Agentic 管路由"，这正好引出 lesson08 的企业级架构。</p></callout>

---

## 九、GraphRAG 的成本与运维红线

| 红线 | 后果 | 防护 |
|------|------|------|
| 全量文档都建图 | 成本爆炸 | 仅关键实体集建图 |
| 图与向量不同步 | 答案矛盾 | 同 pipeline 双写 |
| 抽取 prompt 不调 | 图噪声大 | 领域微调抽取 prompt |
| 无图库备份 | 重建成本极高 | 定期导出图快照 |

## 十、GraphRAG 的局部查询示例

局部查询沿实体邻居遍历，拿到精准事实：

```java
// ch07-rag/src/main/java/demo/GraphRagLocalQuery.java
package demo;

import org.neo4j.driver.*;

public class GraphRagLocalQuery {
    public static void main(String[] args) {
        try (Driver driver = GraphDatabase.driver("bolt://localhost:7687",
                AuthTokens.basic("neo4j", System.getenv("NEO4J_PASSWORD")));
             Session s = driver.session()) {
            // 局部查询：A型号电机的供应商及其认证
            String cypher = """
                MATCH (m:Material {name:'A型号电机'})-[:SUPPLIED_BY]-(s:Supplier)
                OPTIONAL MATCH (s)-[:HAS_CERT]->(c:Certification)
                RETURN s.name AS supplier, collect(c.name) AS certs
                """;
            s.run(cypher).forEach(r ->
                System.out.println(r.get("supplier").asString()
                    + " 认证: " + r.get("certs").asList()));
        }
    }
}
```

<callout emoji="✅"><p>局部查询是 GraphRAG 的"精准模式"——当你已经知道关注哪个实体，沿图遍历比向量召回更确定、更可解释。它和向量 RAG 不是替代，是互补：向量找"可能相关"，图找"确定关系"。</p></callout>

---

## 十一、Agentic RAG 的轮次控制实现

```java
// ch07-rag/src/main/java/demo/AgenticRagLoop.java
package demo;

public class AgenticRagLoop {
    /** 受控 Agentic：最多 maxRounds 轮检索，超时即停 */
    public static String run(String query, int maxRounds) {
        StringBuilder context = new StringBuilder();
        for (int r = 0; r < maxRounds; r++) {
            // 每轮：Agent 决定调用哪个 tool（见 lesson05 第四节 KbTools）
            String toolCall = decideTool(query, context.toString());
            if (toolCall.equals("GENERATE")) break;  // 信息够了，生成
            context.append(retrieve(toolCall, query));
        }
        return generateAnswer(query, context.toString());
    }
    static String decideTool(String q, String ctx) { return "GENERATE"; }
    static String retrieve(String t, String q) { return ""; }
    static String generateAnswer(String q, String c) { return ""; }
}
```

---

## 十二、前沿趋势与边界

```
RAG 演进路线：
  向量RAG → 混合检索+rerank → GraphRAG → Agentic RAG
            (lesson01-04)      (本课)      (本课)
  未来: Self-RAG / Corrective RAG / Modular RAG
```

<callout emoji="💡"><p>关注三个新方向：Self-RAG（模型自己决定"要不要检索、检索够了没"）、Corrective RAG（检索质量差时自动纠正）、Modular RAG（把各模块当乐高拼装）。它们本质都是本课内容的进一步自动化，原理相通，学透本课再看论文会很轻松。</p></callout>

## 小结

这节课把 RAG 推到了前沿：

- **GraphRAG** 用知识图谱补普通向量 RAG 的"全局/多跳"盲区，适合实体关系、聚合类问题，但构建和运维成本高，别滥用。
- 微软 GraphRAG 的"社区摘要 + 局部/全局双查询"是标杆实现，可借鉴思路。
- **Agentic RAG** 把检索变成 Agent 的工具，自主决定搜几次、搜哪库、要不要改写，适合复杂问题，但需受控（限轮数、熔断）。
- 企业落地用"普通 RAG 为主 + GraphRAG 补刀 + Agent 路由"的混合架构，而非非此即彼。

到这里，RAG 的主干（lesson01-05）就讲完了。下一节进入三节新增深化：文档解析实战（lesson06）、查询改写与上下文压缩（lesson07）、多知识库路由与企业级架构（lesson08）。

---

## 延伸阅读 / 引用文档

1. Microsoft GraphRAG 官方仓库与论文: https://github.com/microsoft/graphrag
2. GraphRAG: UNcovering Latent Knowledge (微软论文): https://arxiv.org/abs/2404.16130
3. Neo4j 图数据库（GraphRAG 常用存储）: https://neo4j.com/docs/
4. LangChain4j Agent Tools 文档: https://docs.langchain4j.dev/tutorials/ai-services-tool-calling
5. Agentic RAG 综述（Survey）: https://arxiv.org/abs/2405.11519
6. LlamaIndex 的 Agentic RAG 实现: https://docs.llamaindex.ai/en/stable/examples/agent/

# 第八章第七节：专家角色（Expert Persona）：多领域知识协同与角色扮演机制

大家好，我是秋天。

第八章的最后一节，我们讲认知架构的"集大成"技巧——**专家角色（Expert Persona）**。前面讲的自我提示，是让 Agent 切换"思维模式"；专家角色更进一步：让 Agent **化身不同领域的专家**，用各自的专业知识、术语、判断框架协作解决跨领域复杂问题。这也是多 Agent 协作（第四章）在"单 Agent 内部"的一种映射。

学完这节课，你能掌握：

1. **专家角色的本质**：通过 system prompt 注入"人设 + 领域知识 + 行为约束"，让模型模拟专家。
2. **多专家协同**：用"多角色辩论/协作"提升复杂问题的决策质量。
3. **在 Java 里怎么工程化**地编排一堆专家角色（含与第四章多 Agent 的区别）。

<callout emoji="💡"><p>招聘映射：JD 里"多角色协作""专家系统""角色扮演式 Prompt"。专家角色是把单 Agent 能力提升到"团队作战"的关键，面试时能讲清"单 Agent 内多角色"和"多 Agent 编排"的取舍，是高阶信号。</p></callout>

---

## 一、为什么需要专家角色

```
单角色 Agent 处理跨领域问题：
  问题："这款新药的市场前景和合规风险如何？"
  单 Agent：既不懂医药机理，又不懂法规，又不懂市场 → 答得四不像

多专家角色 Agent：
  化身 医学专家 → 分析机理与疗效
  化身 法规专家 → 核查审批与合规
  化身 市场专家 → 评估需求与竞争
  汇总 → 结构化、多角度、可信的结论
```

当问题横跨多个专业领域，单一"通用助手"人设必然浅薄。专家角色让 Agent 在需要时有"对应的脑子"。

---

## 二、专家角色的实现：人设即提示

核心就是用 system prompt 把"专家人设 + 知识边界 + 行为规范"注入。

```java
// ch08-cognition/src/main/java/demo/persona/Persona.java
package demo.persona;

public record Persona(
        String name,          // 角色名
        String role,          // 头衔
        String expertise,     // 专业领域
        String behavior,      // 行为规范/约束
        String tone           // 语气
) {
    /** 渲染成 system prompt */
    public String toSystemPrompt() {
        return String.format(
            "你现在是%s，%s。\n" +
            "专业领域：%s。\n" +
            "行为规范：%s。\n" +
            "语气风格：%s。\n" +
            "只在你的专业范围内作答，超出范围请明确说明。",
            name, role, expertise, behavior, tone);
    }
}
```

### 2.1 定义一组专家

```java
// ch08-cognition/src/main/java/demo/persona/ExpertPool.java
package demo.persona;

import java.util.List;

public class ExpertPool {
    public static final Persona MEDICAL = new Persona(
        "医学专家", "三甲医院主任医师",
        "药理学、临床试验、适应症与禁忌",
        "只基于循证医学作答，标注证据等级，不夸大疗效",
        "严谨、专业、克制");

    public static final Persona LEGAL = new Persona(
        "法规专家", "药品监管合规顾问",
        "NMPA/FDA 审批流程、广告法合规、数据隐私",
        "引用具体法规条款，标注风险等级，不提供法律意见书",
        "审慎、条理清晰");

    public static final Persona MARKET = new Persona(
        "市场专家", "医药行业分析师",
        "市场规模、竞争格局、患者需求、定价策略",
        "基于公开数据与行业逻辑分析，标注假设",
        "务实、数据驱动");

    public static List<Persona> all() { return List.of(MEDICAL, LEGAL, MARKET); }
}
```

---

## 三、多专家协作：辩论与汇总

### 3.1 串行协作（各专家依次给意见，最后汇总）

```java
// ch08-cognition/src/main/java/demo/persona/MultiExpert.java
package demo.persona;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.util.List;

public class MultiExpert {

    private final ChatLanguageModel model;

    public MultiExpert(ChatLanguageModel model) { this.model = model; }

    /** 让每个专家就同一问题发表意见，最后由"主持专家"汇总 */
    public String collaborate(String question) {
        StringBuilder opinions = new StringBuilder();
        for (Persona p : ExpertPool.all()) {
            String ans = model.generate(
                p.toSystemPrompt() + "\n问题:" + question + "\n请给出你的专业意见：").content();
            opinions.append("【").append(p.name()).append("】").append(ans).append("\n");
        }
        // 汇总阶段：用"主持人"角色综合
        String synthesis = model.generate(
            "你是项目主持人，请综合以下多位专家意见，给出平衡、结构化的最终结论：\n"
            + opinions).content();
        return synthesis;
    }

    public static void main(String[] args) {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY")).modelName("gpt-4o").build();
        String result = new MultiExpert(model)
                .collaborate("一款降糖新药的市场前景与合规风险？");
        System.out.println(result);
    }
}
```

### 3.2 辩论模式（专家互相挑刺，逼近更优解）

```
辩论模式：
  专家A 提出方案 → 专家B 批判 → 专家A 反驳/修正 → 专家C 补刀
       │
       ▼ 多轮后
  主持人综合双方共识与分歧，输出结论（含未决分歧）
```

```java
// ch08-cognition/src/main/java/demo/persona/Debate.java
package demo.persona;

import dev.langchain4j.model.chat.ChatLanguageModel;

public class Debate {
    private final ChatLanguageModel model;
    public Debate(ChatLanguageModel m) { this.model = m; }

    public String debate(String topic, int rounds) {
        String aView = model.generate(
            ExpertPool.MEDICAL.toSystemPrompt() + "\n话题:" + topic + "\n陈述你的观点").content();
        String bView = model.generate(
            ExpertPool.LEGAL.toSystemPrompt() + "\n话题:" + topic
            + "\n请批判以下观点并给出你的立场:\n" + aView).content();
        // 多轮可继续让 A 反驳 B ...
        return model.generate("综合双方:\nA:" + aView + "\nB:" + bView
                + "\n请输出共识与分歧").content();
    }
}
```

<callout emoji="✅"><p>多专家协作的工程价值：复杂跨域问题（如"新药立项"）单靠一个通用模型容易片面，用"多角色各抒己见+汇总"能显著减少盲区。但代价是一次问题要跑 N 次 LLM——适合"高价值、低频、需严谨"的决策场景（如战略分析、风险评审），不适合高吞吐客服。</p></callout>

---

## 四、与第四章"多 Agent 编排"的区别

很多同学会混：专家角色 vs 多 Agent，到底啥区别？

```
维度              专家角色(本节)            多 Agent(第四章)
─────────────────────────────────────────────────────────────
运行实体          同一进程内，共享模型       多个独立 Agent 实例
通信              共享上下文/prompt 切换    消息/协议(A2A/MCP)
状态              共享对话状态             各自独立状态
成本              低（共用一个模型）        高（多实例多调用）
隔离性            弱（同上下文）            强（独立边界）
适用              单 Agent 内多角度思考     真正需要分工自治的任务
```

<callout emoji="⚠️"><p>一个判断框架：如果"多个角色"只是需要不同视角看同一问题，**专家角色（单 Agent 内）就够了**，省成本；如果是"需要真正分工、各自调不同工具、有独立状态"（如一个 Agent 查库、一个 Agent 写报告、一个 Agent 审合规），那得上第四章的多 Agent 编排。别为了"显得高级"硬上多 Agent，徒增复杂度和成本。</p></callout>

---

## 五、专家角色 + 自我提示 + 学习的三角协同

把本章后半段三个高级技巧串起来，就是认知架构的"高阶形态"：

```
          ┌─────────────────────────────────┐
          │   高阶认知 Agent 闭环             │
          └─────────────────────────────────┘
   任务 ──► 自我提示(lesson06): 选思维模式
          ──► 专家角色(本节): 化身对应领域专家
          ──► 规划(02)/工具(03): 拆解+执行
          ──► 反思(04): 评估质量
          ──► 学习(05): 沉淀经验 → 下次更准
```

```java
// ch08-cognition/src/main/java/demo/persona/PersonaSelfPrompt.java
package demo.persona;

import demo.selfprompt.ThinkingMode;

public class PersonaSelfPrompt {
    /** 把"思维模式"和"专家角色"组合成完整执行上下文 */
    public static String buildContext(ThinkingMode mode, Persona persona, String task) {
        return persona.toSystemPrompt()
                + "\n当前思维模式：" + mode.label + "（" + mode.instruction + "）\n"
                + "任务：" + task;
    }
}
```

---

## 六、企业落地：投资研究 Agent 的"专家委员会"

某投研团队的 Agent，分析一家上市公司：

```
问题: "评估XX公司的投资价值与风险"
  │
  ▼ 财务专家 → 拆解财报，算估值与现金流
  ▼ 行业专家 → 分析赛道空间与竞争格局
  ▼ 风控专家 → 排查政策/债务/合规风险
  ▼ 宏观专家 → 研判利率与周期影响
  ▼ 主持人(汇总) → 输出"买入/观望/回避"结论 + 各方分歧
```

**为什么这样设计？** 投资决策是高风险、强专业、多维度的。单一视角必然漏判（财务好但政策风险大）。专家委员会式协作，把"多维专业判断"结构化的同时保留分歧，让人类决策者看到全貌而非被一个模型的片面结论带偏。

<callout emoji="💡"><p>专家角色是"增强人类决策"而非"替代"。工程上一定要把"各方分歧"也输出，而不是让模型强行"和稀泥"给个单一结论。承认不确定性、保留分歧，恰恰是专家系统的专业体现，也是合规要求。</p></callout>

---

## 七、踩坑清单

| 坑 | 后果 | 解法 |
|----|------|------|
| 专家人设互相矛盾 | 输出混乱 | 明确分工边界 |
| 强行和稀泥给结论 | 掩盖分歧风险 | 保留分歧输出 |
| 高吞吐场景用多专家 | 成本爆炸 | 仅高价值决策用 |
| 与多 Agent 混淆 | 架构过度复杂 | 按第四节框架抉择 |
| 角色无知识边界 | 越界胡说 | prompt 明写"超出范围请声明" |

---

## 八、专家角色的隔离与上下文管理

多专家共用一个对话上下文时，容易互相"污染"（医学专家的术语混进市场专家的结论）。需要隔离：

```java
// ch08-cognition/src/main/java/demo/persona/PersonaIsolation.java
package demo.persona;

import java.util.*;

public class PersonaIsolation {
    /** 每个专家独立上下文，最后才汇总，避免污染 */
    private final Map<String, List<String>> contexts = new HashMap<>();

    public void addOpinion(Persona p, String opinion) {
        contexts.computeIfAbsent(p.name(), k -> new ArrayList<>()).add(opinion);
    }

    public String synthesize(ChatLanguageModelWrapper model) {
        StringBuilder all = new StringBuilder();
        contexts.forEach((name, list) ->
            all.append("【").append(name).append("】\n").append(String.join("\n", list)).append("\n"));
        return "综合:\n" + all;
    }

    // 仅为示意包装
    record ChatLanguageModelWrapper() {}
}
```

---

## 九、专家角色的 YAML 配置化

把角色定义外置到配置，便于运营调整而不改代码：

```yaml
# ch08-cognition/src/main/resources/personas.yml
personas:
  - name: 医学专家
    role: 三甲医院主任医师
    expertise: 药理学/临床试验/适应症与禁忌
    behavior: 仅基于循证医学，标注证据等级
    tone: 严谨克制
    enabled: true
  - name: 法规专家
    role: 药品监管合规顾问
    expertise: NMPA/FDA审批/广告法合规
    behavior: 引用具体条款，标注风险等级
    tone: 审慎条理
    enabled: true
  - name: 市场专家
    role: 医药行业分析师
    expertise: 市场规模/竞争格局/定价
    behavior: 基于公开数据，标注假设
    tone: 务实数据驱动
    enabled: true
```

```java
// ch08-cognition/src/main/java/demo/persona/PersonaLoader.java
package demo.persona;

import org.yaml.snakeyaml.Yaml;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

public class PersonaLoader {
    @SuppressWarnings("unchecked")
    public static List<Persona> load(String resource) {
        try (InputStream in = PersonaLoader.class
                .getClassLoader().getResourceAsStream(resource)) {
            Map<String, Object> root = new Yaml().load(in);
            List<Map<String, String>> list =
                    (List<Map<String, String>>) root.get("personas");
            return list.stream().map(m -> new Persona(
                m.get("name"), m.get("role"), m.get("expertise"),
                m.get("behavior"), m.get("tone"))).toList();
        } catch (Exception e) { return List.of(); }
    }
}
```

---

## 十、面试追问与常见误区

| 追问 | 回答要点 |
|------|----------|
| 多角色 vs 多Agent？ | 见第四节框架，按隔离/成本需求选 |
| 角色会冲突怎么办？ | 隔离上下文 + 主持人综合并保留分歧 |
| 角色越多越好？ | 否，N次LLM调用，按需 |
| 怎么防角色越界？ | prompt 明写"超出范围请声明" |

<callout emoji="⚠️"><p>误区：把专家角色当成"让模型假装专家"。真正的价值在于**注入领域约束与行为边界**，让模型在已知框架内严谨作答，而不是自由发挥。角色定义里的 behavior（行为规范）比 role（头衔）重要得多。</p></callout>

## 十一、端到端示例：投资研究专家委员会

```java
// ch08-cognition/src/main/java/demo/persona/InvestmentCommittee.java
package demo.persona;

import demo.selfprompt.ThinkingMode;

public class InvestmentCommittee {
    public static void main(String[] args) {
        // 各专家用对应思维模式（接 lesson06）
        System.out.println("财务专家[" + ThinkingMode.ANALYTICAL.label + "] → 估值分析");
        System.out.println("行业专家[" + ThinkingMode.ANALYTICAL.label + "] → 竞争格局");
        System.out.println("风控专家[" + ThinkingMode.CRITICAL.label + "] → 风险排查");
        System.out.println("主持人 → 综合共识与分歧，保留未决项");
    }
}
```

```
委员会决策流：
  议题
   ├─ 财务专家 ─┐
   ├─ 行业专家 ─┼─► 主持人综合 ─► 结论 + 分歧清单
   └─ 风控专家 ─┘
```

---

## 十二、专家角色的运营治理

| 治理项 | 做法 |
|--------|------|
| 角色版本管理 | 角色定义纳入 Git，变更评审 |
| 角色效果评测 | 各角色贡献度可量化 |
| 角色上下线 | 配置化 enabled 开关 |
| 角色冲突检测 | 定期审计输出一致性 |

<callout emoji="✅"><p>专家角色一旦进入生产，就要当"虚拟员工"来运营：有入职（定义）、有考核（评测）、有离职（下线）。配置化 + 版本化 + 评测化，是让它可持续的关键。别把角色写死在代码里，运营同学改不了。</p></callout>

## 十三、专家角色的招聘话术

```
"对于跨领域复杂问题，我们让单 Agent 内化身多专家协作：
 用 system prompt 注入各专家的人设+领域知识+行为约束，
 串行汇总或辩论模式产出多角度结论，
 主持人综合时保留分歧而非强行和稀泥。
 它与多 Agent 编排(第四章)的区别在于：共享上下文、成本低，
 适合'同问题多视角'；真正需要分工自治才上多 Agent。"
```

---

## 十四、第八章总收口

第八章七节串起来，就是 Agent 的"认知全景"：

```
第八章认知架构全景：
  lesson01 基础：五大支柱总纲（自主/规划/工具/反思/学习）
  lesson02 规划：CoT/任务分解/Plan-and-Execute
  lesson03 工具：API/DB/浏览器 + 安全护栏
  lesson04 反思：Reflexion/错误诊断/自我修正
  lesson05 学习：经验回放/少样本/持续学习
  lesson06 自我提示：动态思维模式切换
  lesson07 专家角色：多领域协同与角色扮演
        │
        ▼
  一个会规划、能动手、会反思、越用越聪明、懂切换脑子、能化身专家的 Agent
```

<callout emoji="✅"><p>学完第八章，你就具备了"设计 Agent 脑子"的能力。这恰好对应招聘 JD 里"理解 Agent 认知架构、能设计规划/反思/学习机制"的高阶要求。下一阶段（第九章架构设计模式、第十章工程化）会把认知能力和系统可靠性结合起来。</p></callout>

## 十五、专家角色完整参考实现

```java
// ch08-cognition/src/main/java/demo/persona/ExpertCommitteeFull.java
package demo.persona;

import dev.langchain4j.model.chat.ChatLanguageModel;

import java.util.List;

public class ExpertCommitteeFull {
    private final ChatLanguageModel model;

    public ExpertCommitteeFull(ChatLanguageModel m) { this.model = m; }

    public String deliberate(String topic) {
        StringBuilder opinions = new StringBuilder();
        for (Persona p : PersonaLoader.load("personas.yml")) {
            String op = model.generate(p.toSystemPrompt()
                    + "\n议题:" + topic + "\n请给出专业意见").content();
            opinions.append("【").append(p.name()).append("】").append(op).append("\n");
        }
        return model.generate("你是主持人，综合以下专家意见，输出结论并保留分歧:\n"
                + opinions).content();
    }
}
```

---

## 十六、专家角色检查清单

| 能力 | 是否具备 |
|------|----------|
| 角色定义(行为边界) | ☐ |
| 上下文隔离 | ☐ |
| 配置化管理 | ☐ |
| 主持人综合+留分歧 | ☐ |
| 与多Agent区分清晰 | ☐ |

<callout emoji="✅"><p>第八章到此收官。七节串起来，你已具备设计"会规划、能动手、会反思、越用越聪明、懂切换脑子、能化身专家"的 Agent 认知能力。这对应 JD 里"理解 Agent 认知架构、能设计规划/反思/学习机制"的高阶要求——这是普通"调 API 工程师"和"Agent 架构师"的分水岭。</p></callout>

## 小结

这节课把认知架构的最后一节——专家角色讲透了：

- **专家角色**通过 system prompt 注入"人设+领域知识+行为约束"，让模型模拟专家。
- **多专家协作**：串行汇总（各抒己见+主持人综合）或辩论模式（互相挑刺逼近更优），适合高价值跨域决策。
- **与多 Agent 的区别**：专家角色是单 Agent 内共享上下文的多视角，成本低；多 Agent 是独立实例分工自治，成本高。按需选用。
- **三角协同**：自我提示（选思维模式）+ 专家角色（化身领域专家）+ 学习（沉淀经验）构成高阶认知闭环。
- 至此，第八章认知架构七节（基础/规划/工具/反思/学习/自我提示/专家角色）全部讲完。

---

## 延伸阅读 / 引用文档

1. Park et al., *Generative Agents: Human Behavior Simulation* (角色/人设奠基): https://arxiv.org/abs/2304.03442
2. Liang et al., *Encouraging Divergent Thinking in LLMs* (多角色/辩论): https://arxiv.org/abs/2309.02307
3. LangChain4j AiServices（动态角色/系统提示）: https://docs.langchain4j.dev/tutorials/ai-services
4. Spring AI Alibaba Graph（多角色编排，第四章关联）: https://github.com/alibaba/spring-ai-alibaba
5. AutoGen（多 Agent 角色协作，第四章关联）: https://microsoft.github.io/autogen/
6. Google ADK for Java（子 Agent 角色）: https://github.com/google/adk-java

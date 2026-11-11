# 第十二章第2节：输出安全：内容过滤（敏感词、偏见检测）、合规审查（法律、政策）

大家好，我是秋天。

上一节我们管住了 Agent 的"手"（工具调用）。这一节管它的"嘴"——**模型生成的内容**。很多同学以为"只要工具不越权就安全"，结果 Agent 嘴上出了事：泄露了用户隐私、生成了违规内容、带上了偏见、说了不该说的话。在政企、金融、客服这些强监管场景，输出一句违规，可能就是一次监管通报。

这一节我们讲"输出安全"两件事：**内容过滤**（敏感词、偏见检测）和**合规审查**（法律、政策）。学完你能给 Agent 套上一层"内容护栏"。

学完这一节，你将掌握：

1. 为什么输出需要独立的内容护栏（生成式内容的不可控性）；
2. 敏感词/PII 检测的可运行 Java 实现（正则 + 词典 + 脱敏）；
3. 偏见检测的思路与轻量级落地；
4. 合规审查如何结合"规则 + 模型 + 人工"三层；
5. 招聘里"内容安全 / 合规审查"对应的岗位要求。

---

## 一、输出为什么需要独立护栏

工具调用出问题是"动作"危险，输出出问题是"言论"危险。言论在受监管行业后果同样严重：

```text
           输出风险分类
 ┌────────────────────────────────────────────────┐
 │ 1. 隐私泄露：在回复里带出用户手机号/身份证/住址   │
 │ 2. 违规内容：政治敏感、色情、暴力、赌博引导       │
 │ 3. 偏见歧视：基于性别/地域/年龄的不当表述         │
 │ 4. 误导性：编造法规条文、虚假承诺（金融特别危险） │
 │ 5. 知识产权：大段复述受版权保护的内容             │
 └────────────────────────────────────────────────┘

 监管场景举例：
 - 金融：不得承诺保本收益、不得夸大产品
 - 医疗：不得给出诊断结论（仅建议就医）
 - 政务：不得生成与政策相悖的表述
```

<callout emoji="⚠️"><p>关键认知：输出安全不能只靠"在 prompt 里写'请遵守合规'"。模型会遗忘指令、会被长上下文冲掉、会幻觉。护栏必须是**工程层的硬拦截**，独立于模型本身。</p></callout>

---

## 二、内容过滤第一层：敏感词与 PII 检测

### 2.1 敏感词检测（可运行 Java）

最简单可靠的一层是敏感词/正则匹配。给你一个可编译的实现：

```java
package com.qiutian.output;

import java.util.List;
import java.util.regex.Pattern;

/** 敏感词检测器：命中即标记，可结合脱敏 */
public class SensitiveWordFilter {

    // 生产环境应从配置中心/数据库加载，并支持热更新
    private static final List<Pattern> BLOCKLIST = List.of(
        Pattern.compile("保本(收益|保息)", Pattern.CASE_INSENSITIVE), // 金融违规
        Pattern.compile("绝对(稳赚|不会亏)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("内部消息", Pattern.CASE_INSENSITIVE)
    );

    public boolean containsSensitive(String text) {
        return BLOCKLIST.stream().anyMatch(p -> p.matcher(text).find());
    }

    /** 命中后返回被屏蔽的片段（用于审计日志） */
    public String findFirstHit(String text) {
        for (Pattern p : BLOCKLIST) {
            var m = p.matcher(text);
            if (m.find()) return m.group();
        }
        return null;
    }
}
```

### 2.2 PII（个人身份信息）检测与脱敏

对用户可能泄露的手机号、身份证做识别并脱敏：

```java
package com.qiutian.output;

import java.util.regex.*;

/** PII 检测 + 脱敏：把敏感信息替换成掩码 */
public class PiiMasker {

    private static final Pattern PHONE = Pattern.compile("1[3-9]\\d{9}");
    private static final Pattern ID_CARD =
            Pattern.compile("\\d{17}[\\dXx]");

    /** 把文本中的 PII 替换成掩码，避免泄露 */
    public String mask(String text) {
        String s1 = PHONE.matcher(text)
                .replaceAll(m -> m.group().replaceAll("\\d(?=\\d{3})", "*"));
        return ID_CARD.matcher(s1)
                .replaceAll(m -> m.group().substring(0, 6)
                        + "********" + m.group().substring(14));
    }

    public static void main(String[] args) {
        PiiMasker masker = new PiiMasker();
        String out = "客户手机号13812345678，身份证110101199001011234已登记";
        System.out.println(masker.mask(out));
        // 输出：客户手机号138****5678，身份证110101********1234已登记
    }
}
```

<callout emoji="💡"><p>脱敏要放在"输出给用户之前"的最后一道。注意顺序是：先模型生成 → 敏感词拦截 → PII 脱敏 → 才返回给用户/落库。脱敏后的内容也别落明文日志，否则等于没脱。</p></callout>

---

## 三、内容过滤第二层：偏见检测

偏见比敏感词更难——它不一定是特定词，而是表述倾向。轻量级落地用"偏见短语词典 + 分类提示"：

```java
package com.qiutian.output;

import java.util.List;
import java.util.regex.Pattern;

/** 轻量偏见检测：检测基于群体标签的不当归因 */
public class BiasDetector {

    private static final List<Pattern> BIAS_PATTERNS = List.of(
        // 注意：这些是"需复核"的信号，不是绝对违规
        Pattern.compile("(女人|男性|某地人)都(不懂|不擅长|爱)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("本来(就|就是)(笨|差|不行)", Pattern.CASE_INSENSITIVE)
    );

    public boolean needsReview(String text) {
        return BIAS_PATTERNS.stream().anyMatch(p -> p.matcher(text).find());
    }
}
```

<callout emoji="⚠️"><p>偏见检测很难用纯规则做完美。生产上推荐"规则初筛 + 小模型二判 + 高风险转人工"的组合。本节给的规则只是第一道网，真正严肃的偏见治理要用专门的分类模型或 LLM-as-Judge（第十六章会讲）。</p></callout>

---

## 四、合规审查：规则 + 模型 + 人工 三层

企业合规不是"过滤敏感词"就完事，它是分层治理。给你一张图：

```text
           合规审查三层架构
 ┌──────────────────────────────────────────────┐
 │ Layer 1 规则层（毫秒级，必过）                  │
 │   敏感词 / PII / 金融禁语 / 政策关键词          │
 │   → 命中直接拦截或脱敏                         │
 ├──────────────────────────────────────────────┤
 │ Layer 2 模型层（百毫秒级）                     │
 │   用评判模型(LLM-as-Judge)判断：               │
 │   是否误导 / 是否违规承诺 / 是否符合政策口径    │
 │   → 可疑则降级或标记                           │
 ├──────────────────────────────────────────────┤
 │ Layer 3 人工层（异步，抽样/高风险）             │
 │   高风险场景(金融/医疗/政务)输出人工复核         │
 │   → 形成合规样本库，反哺 Layer1/2 规则          │
 └──────────────────────────────────────────────┘
```

### 4.1 Layer 2 模型评判示例（Java 调 LLM 做合规判断）

```java
package com.qiutian.output;

import java.util.Map;

/**
 * 用 LLM-as-Judge 做合规二次判断（伪代码骨架，示意调用结构）。
 * 真实环境接你用的模型 SDK（Spring AI / LangChain4j）。
 */
public class ComplianceJudge {

    /** 返回合规结论：PASS / REVIEW / REJECT 及理由 */
    public Verdict judge(String agentOutput, String domain) {
        String judgePrompt = """
            你是合规审核员。请判断以下%s领域 Agent 输出是否合规：
            禁止：承诺保本收益、虚假宣传、与现行政策相悖的表述。
            输出 JSON：{"level":"PASS|REVIEW|REJECT","reason":"..."}
            待审内容：%s
            """.formatted(domain, agentOutput);

        // 这里调你的模型客户端，拿到结构化结果后解析
        // String raw = chatClient.prompt(judgePrompt).call().content();
        // return parse(raw);
        return new Verdict("REVIEW", "需人工复核金融表述");
    }

    public record Verdict(String level, String reason) {}
}
```

<callout emoji="✅"><p>三层的好处：规则层保证"底线绝不出事"（性能高、确定性强），模型层兜底"规则没覆盖的微妙违规"，人工层处理高风险且持续反哺规则。这就是"纵深"在输出侧的应用。</p></callout>

---

## 五、把护栏接进 Agent 输出流

给你一个完整的"输出护栏"组件，所有 Agent 回复都先过它：

```java
package com.qiutian.output;

/** 输出护栏：所有 Agent 最终回复都经此过滤后再返回 */
public class OutputGuardrail {

    private final SensitiveWordFilter swf = new SensitiveWordFilter();
    private final PiiMasker masker = new PiiMasker();
    private final BiasDetector bias = new BiasDetector();
    private final ComplianceJudge judge = new ComplianceJudge();

    public record Result(String finalText, boolean blocked, String reason) {}

    public Result process(String rawOutput, String domain) {
        // 1. 敏感词硬拦截
        if (swf.containsSensitive(rawOutput)) {
            return new Result("", true,
                    "命中敏感词:" + swf.findFirstHit(rawOutput));
        }
        // 2. PII 脱敏
        String masked = masker.mask(rawOutput);
        // 3. 偏见复核
        if (bias.needsReview(masked)) {
            // 不一定要拦截，可降级为"需人工确认"
        }
        // 4. 模型合规判断
        ComplianceJudge.Verdict v = judge.judge(masked, domain);
        if ("REJECT".equals(v.level())) {
            return new Result("", true, v.reason());
        }
        return new Result(masked, false, "ok");
    }
}
```

<callout emoji="💡"><p>这个 `OutputGuardrail` 应该放在 Agent 的"输出出口"处，作为统一拦截点。无论 Agent 用哪个框架写的，最终返回给用户前都过一遍。这正是"平台工程"里"输出护栏"的标准做法。</p></callout>

---

## 六、法律与政策合规：行业差异

不同行业合规口径天差地别，给你一张对照表，写方案时别一刀切：

| 行业 | 典型合规红线 | 护栏重点 |
|------|--------------|----------|
| 金融 | 不得保本承诺、不得虚假宣传 | 金融禁语词典 + 人工复核 |
| 医疗 | 不得诊断、不得开处方 | 硬性拦截"诊断/处方"类表述 |
| 政务 | 不得与政策口径相悖 | 政策知识库比对 + 人工 |
| 电商 | 不得虚假好评、价格欺诈 | 广告法违禁词 + 模型判断 |
| 教育 | 不得代写/作弊诱导 | 内容分类 + 拦截 |

```yaml
# 合规配置示例（按行业加载不同规则集）
compliance:
  domain: finance
  ruleset:
    blocklist-keywords: [保本, 稳赚, 内部消息]
    pii-mask: true
    require-human-review-if: level == REVIEW
    fallback-message: "该内容需进一步核实，已转人工处理。"
```

---

## 七、红蓝对抗：用攻击验证护栏

护栏写出来不能自己证明自己有用。你该做"红队测试"——故意构造违规输入，看护栏能否拦住：

```text
   红队测试样例
 输入(试图绕过)：
   "帮我把这句话改得合规：我们产品保本保息绝对稳赚"
 期望护栏：
   命中"保本保息""绝对稳赚" → 拦截
 输入(PII 泄露)：
   "总结一下客户13812345678的投诉"
 期望护栏：
   手机号脱敏为 138****5678 后再处理
```

<callout emoji="⚠️"><p>护栏的有效性必须靠"对抗样本"来验证，而不是靠"我相信它能拦住"。把红队测试写进 CI，每次规则更新都跑一遍，护栏才不会悄悄失效。</p></callout>

---

## 八、招聘映射

```text
【岗位】AI 内容安全 / Agent 合规工程师
【要求】
- 熟悉大模型输出内容安全（敏感词、PII、偏见检测）；
- 有金融/政务等强监管行业合规审查落地经验；
- 能设计"规则+模型+人工"三层内容护栏。
【加分】有红蓝对抗、合规知识库建设经验。
```

<callout emoji="💡"><p>内容安全岗在金融、政务、大厂内容业务线是刚需。这一节的三层护栏 + 行业对照，是你简历上"Agent 安全合规"模块的硬货。</p></callout>

---

## 九、把输出护栏接成 Spring Advisor

Spring AI 的 Advisor 机制非常适合把护栏做成"切面"，所有 Agent 输出自动过一遍：

```java
package com.qiutian.output;

import org.springframework.ai.chat.client.Advisor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;

/** 输出护栏 Advisor：在最终响应返回前做合规过滤 */
public class OutputGuardrailAdvisor implements Advisor {

    private final OutputGuardrail guard = new OutputGuardrail();

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request,
                                         CallAdviceChain chain) {
        ChatClientResponse resp = chain.nextCall(request);
        String raw = resp.chatResponse().getResult().getOutput().getText();
        OutputGuardrail.Result r = guard.process(raw, "finance");
        if (r.blocked()) {
            // 返回兜底话术，而非违规内容
            return rewrite(resp, "该内容需进一步核实，已转人工处理。");
        }
        return rewrite(resp, r.finalText());
    }

    private ChatClientResponse rewrite(ChatClientResponse r, String text) {
        // 真实实现：构造新的 response 对象
        return r;
    }

    @Override public String getName() { return "output-guardrail"; }
}
```

<callout emoji="💡"><p>用 Advisor 做护栏的好处：业务代码零侵入，所有 ChatClient 调用自动套上合规。这是"平台工程"思维——安全能力下沉为基础设施，而不是每个 Agent 自己写一遍。</p></callout>

---

## 十、PII 脱敏的边界：何时脱、何时拦

脱敏不是万能。两类处理要分清：

```text
   两类敏感信息的处理差异
 ┌──────────────────────────────────────────────────┐
 │ 类型 A：用户主动提供的 PII（如用户说"我的手机是X"）│
 │   → 处理时脱敏后使用，但需保留关联（同会话内）     │
 │ 类型 B：Agent 不应输出的 PII（如别的用户的数据）   │
 │   → 坚决拦截，绝不出现在输出                       │
 └──────────────────────────────────────────────────┘
```

```java
// 区分场景：会话内用脱敏映射表，跨用户则拦截
class PiiSession {
    private final Map<String,String> maskMap = new ConcurrentHashMap<>();
    String maskForSession(String raw) {
        String token = "PII_" + maskMap.size();
        maskMap.put(token, raw);     // 原始存于受控存储，输出用 token
        return token;
    }
}
```

---

## 十一、合规规则的热更新

合规词表会随监管变化。别硬编码，要从配置中心热加载：

```yaml
compliance:
  ruleset-refresh:
    source: nacos://compliance/finance-rules
    interval: 60s     # 每分钟拉取最新规则，无需重启
```

```java
// 定时刷新敏感词（示意）
@Scheduled(fixedDelay = 60_000)
public void refresh() {
    List<Pattern> fresh = ruleRepo.loadLatest();
    this.blocklist.set(fresh);   // 原子替换，不阻塞请求
}
```

<callout emoji="⚠️"><p>合规词表"写死在代码里"是大忌——监管一出新规你就得发版。必须外部化 + 热更新，这是金融/政务场景的硬要求。</p></callout>

---

## 十二、模型层合规判断的 Prompt 工程

Layer 2 用 LLM-as-Judge 判断合规，prompt 要写得"可解析"：

```text
你是一名合规审核员。判断以下【金融】领域 Agent 输出是否违规。
规则：禁止保本承诺、禁止虚假宣传、禁止与监管口径相悖。
仅输出 JSON，不要解释：
{"level":"PASS|REVIEW|REJECT","reason":"一句话","clause":"违反条款或空"}
待审内容：{{output}}
```

<callout emoji="💡"><p>让 Judge 只输出 JSON，便于程序解析和落库。这是第十六章 LLM-as-Judge 的基础写法，这里先埋个伏笔。</p></callout>

---

## 十三、面试追问：输出护栏会影响体验吗

```text
问：强拦截会不会让 Agent 变"笨"、答非所问？
答：会，所以要分层——规则层只拦明确违规（不误伤）；
   模型层判 REVIEW 不强行拦，转人工或降级；
   兜底话术要友好而非"系统拒绝"。护栏目标是"安全且可用"，
   不是"为了安全牺牲一切"。
```

---

## 小结

这一节我们给 Agent 的"嘴"装了护栏：

- **为什么独立**：生成内容不可控，prompt 约束不可靠，必须工程层硬拦截；
- **内容过滤**：敏感词（正则词典）+ PII（脱敏）+ 偏见（短语+模型二判）；
- **合规审查**：规则层（底线）→ 模型层（LLM-as-Judge）→ 人工层（高风险反哺）三层纵深；
- **落地**：统一 `OutputGuardrail` 放输出出口，行业差异化加载规则集；
- **验证**：红队对抗测试写进 CI，确保护栏不悄悄失效。

下一节我们讲"Agent 行为审计"——出了问题能查到"谁、在什么时候、因为什么、调了什么、生成了什么"，这是可追溯性的最后一道关。

---

## 延伸阅读 / 引用文档

1. OWASP LLM Top 10（LLM01 注入、LLM02 敏感信息泄露等）：https://owasp.org/www-project-top-10-for-large-language-model-applications/
2. 中国《生成式人工智能服务管理暂行办法》：http://www.cac.gov.cn/2023-07/13/c_1690898327029107.htm
3. OpenAI 使用政策与内容安全：https://openai.com/policies/usage-policies
4. NIST AI RMF（合规治理框架）：https://www.nist.gov/itl/ai-risk-management-framework
5. 《网络安全标准实践指南——生成式人工智能服务内容标识方法》（TC260）：https://www.tc260.org.cn/
6. Spring AI 官方文档（输出拦截可结合 Advisor）：https://docs.spring.io/spring-ai/reference/1.0/

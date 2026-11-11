# 第十章第三节：提示词版本管理（Prompt as Code）：Git 管理、提示词生命周期

大家好，我是秋天。

很多团队把 prompt 写死在代码里、甚至写在飞书文档里口口相传。结果某天"谁改了句提示词，线上质量崩了"，你翻遍代码找不到、问了一圈人没人认领。这节课讲**提示词版本管理（Prompt as Code）**：把提示词当成代码一样进 Git、走评审、打版本、可回滚。

学完这节课，你能掌握：

1. **为什么要 Prompt as Code**：prompt 是"隐形代码"，必须可版本化、可评审、可回滚。
2. **工程落地**：提示词外置成文件/配置，Git 管理，CI 校验。
3. **提示词生命周期**：草稿 → 评审 → 灰度 → 全量 → 监控 → 回滚。
4. **与框架结合**：LangChain4j 模板、Spring AI `PromptTemplate`、Spring AI Alibaba Prompt 管理的用法。
5. **企业场景**：多版本 A/B、故障快速回滚、合规审计。

<callout emoji="💡"><p>招聘映射：JD 里"Prompt 管理/版本控制""Agent 平台工程经验"是 2025–26 的新晋高频项。能把 prompt 工程化、平台化的候选人，直接对标"Agent 平台工程师"岗位。</p></callout>

---

## 一、prompt 不是文本，是代码

```
把 prompt 写死在代码里：
   String prompt = "你是客服，...";  // 散落各处，改一处动全身，无法审计

把 prompt 写在文档里：
   飞书文档 v3 最终版(真的最终版).docx  ← 谁改的？为什么改？回不去

正确做法 Prompt as Code：
   prompts/
     classify_v2.txt   ← Git 版本化、PR 评审、打 tag
     summarize_v1.txt
   代码里只引用 "classify" + 版本，运行时加载
```

提示词具备代码的所有特征：**会被改、会引入 bug、需要评审、需要回滚、需要多人协作**。所以它必须享受代码的待遇。

<callout emoji="✅"><p>判断框架：任何"会被反复修改、影响线上质量、多人参与"的提示词，都必须 Prompt as Code。一次性写死且永不改的例外——但现实中几乎没有。</p></callout>

---

## 二、提示词外置成文件 + Git 管理

最简单有效的起步：把每个提示词存成独立文件，进 Git。

```
ch10-engineering/
  prompts/
    classifier/
      refund.txt         # 退款分类提示词 v2
      tech.txt
    chain/
      summarize.txt
      generate.txt
  src/main/resources/
    prompt-config.yml    # 声明当前启用哪个版本
```

```text
# prompts/classifier/refund.txt
你是工单分类器。把用户消息分类为以下之一，只输出英文标签：
REFUND / LOGISTICS / TECH / COMPLAINT / UNKNOWN
不要解释。注意：含"钱/退款/退钱"优先判 REFUND。
```

```java
// ch10-engineering/src/main/java/demo/prompt/PromptLoader.java
package demo.prompt;

import java.nio.file.*;

public class PromptLoader {
    /** 从文件加载提示词（生产可改为从 Nacos/配置中心拉取） */
    public static String load(String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (Exception e) {
            throw new RuntimeException("提示词加载失败: " + path, e);
        }
    }

    public static void main(String[] args) {
        String p = load("prompts/classifier/refund.txt");
        System.out.println("加载到提示词:\n" + p);
    }
}
```

Git 带来三个直接好处：**历史可追溯**（谁、何时、改了啥）、**可评审**（PR diff 看 prompt 改动）、**可回滚**（`git revert` 回到上一个版本）。

---

## 三、在框架里用外部提示词

LangChain4j 的 `PromptTemplate` 和 Spring AI 的 `PromptTemplate` 都支持从外部资源加载。

```java
// ch10-engineering/src/main/java/demo/prompt/LangChain4jPrompt.java
package demo.prompt;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.nio.file.Files;
import java.nio.file.Path;

public class LangChain4jPrompt {

    interface Assistant {
        @SystemMessage("{{it.systemPrompt}}")
        String chat(@UserMessage String user, @V("systemPrompt") String systemPrompt);
    }

    public static void main(String[] args) throws Exception {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY")).modelName("gpt-4o-mini").build();
        Assistant a = AiServices.create(Assistant.class, model);

        String system = Files.readString(Path.of("prompts/classifier/refund.txt"));
        String ans = a.chat("我要退款", system);
        System.out.println(ans);
    }
}
```

Spring AI 的 `PromptTemplate` 用 `Resource` 加载更顺：

```java
// ch10-engineering/src/main/java/demo/prompt/SpringAiPrompt.java
package demo.prompt;

import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.ClassPathResource;
import java.util.Map;

public class SpringAiPrompt {
    public static String render() {
        // 从 classpath 加载模板文件
        PromptTemplate tpl = new PromptTemplate(new ClassPathResource("prompts/summarize.st"));
        return tpl.render(Map.of("text", "用户投诉订单未退款"));
    }
}
```

```text
# src/main/resources/prompts/summarize.st
用一句话摘要下面的工单，不超过30字，保留订单号：
{text}
```

<callout emoji="⚠️"><p>模板变量用 `{text}`（Spring AI）或 `{{text}}`（LangChain4j），千万别在代码里用字符串拼接拼提示词——那是注入漏洞和格式错误的温床。</p></callout>

---

## 四、提示词生命周期：从草稿到回滚

把提示词当软件制品管，走完整生命周期：

```
草稿(Draft) ─▶ 评审(Review, PR) ─▶ 灰度(Canary, 5%流量)
                                          │
                                   指标无劣化?
                                     ├─ 是 ─▶ 全量(Production)
                                     └─ 否 ─▶ 回滚(Rollback) 到上一版本
                                                  │
                                            监控(盯质量曲线)
```

```yaml
# prompt-config.yml 声明式管理版本与灰度
prompts:
  classifier:
    active-version: v2
    canary:
      version: v3
      traffic: 0.05     # v3 灰度 5% 流量
    strategy: ab        # 与 v2 做 A/B 对比
  summarize:
    active-version: v1
```

```java
// ch10-engineering/src/main/java/demo/prompt/VersionedPrompt.java
package demo.prompt;

import java.util.Map;
import java.util.Random;

public class VersionedPrompt {

    // 极简灰度路由：按流量比例选版本
    static String pickVersion(Map<String, Double> versions, double canaryTraffic) {
        double r = new Random().nextDouble();
        return r < canaryTraffic ? "v3" : "v2"; // 5% 走 v3
    }

    public static void main(String[] args) {
        System.out.println("本次请求命中版本: " +
            pickVersion(Map.of("v2", 0.95, "v3", 0.05), 0.05));
    }
}
```

<callout emoji="💡"><p>灰度 + 可观测性（第一节）是黄金搭档：新 prompt 灰度时，Grafana 上盯"质检 FAIL 率 / 兜底率"曲线，一抬头立刻回滚。没有监控的灰度等于盲飞。</p></callout>

---

## 五、Prompt 版本化的 Git 工作流

```
# 新增/修改一个提示词的标准动作
git checkout -b prompt/classifier-v3
# 编辑 prompts/classifier/refund.txt (加新规则)
git add prompts/classifier/refund.txt
git commit -m "classifier: v3 增加'退钱'优先判 REFUND 规则"
git push && 开 PR
# 评审人看 diff，关注: 是否影响其他分支? 是否有评估集覆盖?
# 合并后 CI 自动跑 evalset(第二节)，指标通过则灰度
```

在 PR 描述里强制要求附上**评估集结果对比**：

```markdown
## 提示词变更: classifier v2 → v3
变更点: 新增"退钱/钱"优先 REFUND 规则
评估集结果:
  意图准确率 v2=0.91 → v3=0.94  ✅
  兜底率     v2=0.05 → v3=0.04  ✅
风险评估: 低(仅增强退款识别)
```

---

## 六、Spring AI Alibaba Prompt 管理（平台级）

当提示词多了（几十上百个），文件 + Git 不够，需要平台。Spring AI Alibaba 集成了 **Prompt 管理**能力，可对接 Nacos 做动态下发。

```yaml
# application.yml 使用配置中心的提示词
spring:
  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_ADDR}
  ai:
    alibaba:
      prompt:
        # 提示词存 Nacos 配置，运行时动态拉取，改了不用重启
        source: nacos
        group: AGENT_PROMPTS
        data-id: customer-service-prompts
```

```
Git(版本真相) ──CI 发布──▶ Nacos(运行时配置) ──▶ Agent 热加载
        ▲                                              │
        └──────── 回滚: Git revert + 重新发布 ◀────────┘
```

这种"Git 管版本、Nacos 管运行时"的双层结构，兼顾了**审计可追溯**与**热更新不重启**。

---

## 七、提示词与代码的解耦边界

什么时候该把提示词拆出来、什么时候留在代码里？

```
留在代码里(硬编码可接受):
   - 永远不变的系统身份设定("你是有帮助的助手")
   - 一次性的临时实验提示词

必须外置(Prompt as Code):
   - 影响质量且会被业务反复调的(分类规则、话术模板)
   - 需要 A/B、灰度、回滚的
   - 多人协作、有合规审计要求的(金融/政企)
```

<callout emoji="⚠️"><p>外置不是目的，可控才是。如果外置后没人评审、改了不跑评估集，那和写文档没区别。Prompt as Code 的精髓是"版本 + 评审 + 评估 + 回滚"四件套齐活。</p></callout>

---

## 八、企业场景：故障回滚与合规审计

```
场景A 故障快速回滚：
   新 prompt v3 灰度后质检率跌 → 监控告警
   → git revert + 重新发布 → 5 分钟恢复 v2
   （若 prompt 写死代码里，要改代码重新部署，至少半小时）

场景B 合规审计(金融):
   监管要查"三个月前某笔贷款话术用的什么提示词"
   → Git 历史 + tag 直接翻出当时的 prompt 全文 + 评审记录
   → 满足审计留痕要求
```

```bash
# 查某个提示词在指定日期的版本(审计用)
git log -- prompts/classifier/refund.txt
git show v2.3.1:prompts/classifier/refund.txt
```

---

## 九、与全课程的连接

```
提示词版本管理 衔接：
   ── 第九章链路/编排: 链结构也能外置成 YAML 版本化(见 ch09)
   ── 本节评估集: 每次 prompt 变更必须跑 evalset 对比(第二节)
   ── 成本治理: 不同 prompt 版本的 token 消耗差异要监控(第六节)
   ── 安全护栏: 提示词变更也要过安全评审(第五节/第十二章)
```

---

## 十、提示词 A/B 实验与多版本共存

Prompt as Code 不只是"存档"，更是"做实验"。同一意图同时跑多个 prompt 版本，用数据选赢家。

```
A/B 实验结构：
   流量 ─▶ 分流器(按 hash/随机)
            ├─ 50% → prompt v2 (对照组)
            └─ 50% → prompt v3 (实验组)
             │
             ▼
         各自跑评估集 + 线上指标(质检率/满意度)
             │
             ▼
         统计显著性检验 → v3 胜出则全量, 否则回滚
```

```java
// ch10-engineering/src/main/java/demo/prompt/AbExperiment.java
package demo.prompt;

import java.util.*;

public class AbExperiment {

    static String pickBucket(String userId, String vA, String vB) {
        // 按 userId hash 稳定分流, 同一用户始终命中同一版本(避免体验抖动)
        int h = userId.hashCode() & Integer.MAX_VALUE;
        return (h % 100) < 50 ? vA : vB;
    }

    public static void main(String[] args) {
        String v = pickBucket("user-123", "v2", "v3");
        System.out.println("user-123 命中版本=" + v);
        // 收集两组的线上指标后对比, 决定全量版本
    }
}
```

<callout emoji="💡"><p>A/B 实验的要点是"分流稳定"——同一用户每次必须命中同一版本，否则他对话前后体验不一致会很困惑。用 userId 做一致性 hash 是最稳的做法。</p></callout>

---

## 十一、多租户/多语言提示词管理

SaaS 产品常需"同一逻辑、不同租户不同话术/不同语言"。提示词要支持维度化。

```yaml
# 多维度提示词寻址: 租户 + 语言 + 版本
prompts:
  classifier:
    tenant-a:
      zh-CN:
        active: v2
        variants: { v2: "你是A公司的客服分类器...", v3: "..." }
      en-US:
        active: v1
    tenant-b:
      zh-CN:
        active: v1
```

```java
// ch10-engineering/src/main/java/demo/prompt/MultiTenantPrompt.java
package demo.prompt;

import java.util.Map;

public class MultiTenantPrompt {

    static String resolve(Map<String, Object> cfg, String tenant, String lang) {
        @SuppressWarnings("unchecked")
        Map<String, Object> tenantCfg = (Map<String, Object>) cfg.get(tenant);
        Map<String, Object> langCfg = (Map<String, Object>) tenantCfg.get(lang);
        return (String) langCfg.get("active");
    }

    public static void main(String[] args) {
        // 真实从 Nacos 拉取; 这里示意维度解析
        System.out.println("tenant-a/zh-CN 启用版本应解析为 v2");
    }
}
```

```
维度化提示词管理:
   维度: 租户 × 语言 × 场景 × 版本
   存储: Git(版本真相) + Nacos(运行时按维度下发)
   好处: 不同客户定制话术不互相干扰, 且每版可独立评审回滚
```

---

## 小结

- Prompt as Code：把提示词当代码——Git 版本化、PR 评审、可回滚、可审计。
- 落地：提示词外置成文件/配置，框架用 `PromptTemplate` 加载，严禁字符串拼接。
- 生命周期：草稿→评审→灰度→全量→监控→回滚，灰度必须配质量监控。
- Git（版本真相）+ Nacos（运行时热更新）双层结构兼顾审计与敏捷。
- 价值：故障分钟级回滚、合规审计留痕、A/B 对比有据。评估集是每次变更的强制门禁。

## 延伸阅读 / 引用文档

1. Spring AI PromptTemplate 文档：https://docs.spring.io/spring-ai/reference/api/prompt-template.html
2. LangChain4j 提示词模板：https://docs.langchain4j.dev
3. Spring AI Alibaba Prompt 管理：https://java2ai.com/docs/overview
4. Nacos 配置管理：https://nacos.io/zh-cn/docs/config-concept.html
5. Git 官方文档（版本管理）：https://git-scm.com/doc
6. Anthropic 提示词工程指南：https://docs.anthropic.com/en/docs/build-with-claude/prompt-engineering/overview

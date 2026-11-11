# 第2章第7节：输出解析器（OutputParser）：将LLM输出转换为Java对象

大家好，我是秋天。

上一节课我们把提示词模板讲透了，知道了如何优雅地管理和注入提示词变量。

这节课，我们来讲 LangChain4j 里一个非常实用的能力：**OutputParser（输出解析器）——如何把大模型返回的文本，自动解析成 Java 对象。**

这个能力在生产环境里极其重要。模型输出的是文本，但业务系统需要的是结构化数据。如果没有好的解析机制，你要么手动写 JSON 解析代码，要么在 Agent 里做各种字符串处理，非常容易出错。

学完这节课，你会掌握：

1. OutputParser 的工作原理
2. AiServices 结构化输出的使用方式
3. 支持的 Java 类型：基本类型、枚举、Record、POJO、List、Map
4. 自定义 OutputParser 的实现
5. 结构化输出与 JSON Schema 的关系
6. 常见坑和最佳实践

---

## 一、为什么需要 OutputParser

先看一个没有 OutputParser 的痛点：

````Java
// 没有 OutputParser：手动解析，脆弱且繁琐
String response = model.generate("分析这段代码的问题，返回 JSON 格式");
// response 可能是：
// {"issues": [...]}  ← 标准 JSON
// ```json\n{"issues": [...]}```  ← 带代码块
// 以下是分析结果：\n{"issues": [...]}  ← 带前缀文字
// 模型还可能偶尔返回格式错误的 JSON

// 手动处理各种情况，非常脆弱
String json = response
        .replaceAll("```json\\n?", "")
        .replaceAll("```\\n?", "")
        .trim();
if (json.startsWith("以下")) {
    json = json.substring(json.indexOf("{"));
}
CodeAnalysis result = objectMapper.readValue(json, CodeAnalysis.class);

````

**有了 OutputParser，你只需要定义返回类型，框架自动处理一切：**

```Java
// 有 OutputParser：声明返回类型，框架自动解析
interface CodeAnalysisAgent {
    CodeAnalysis analyze(String code);  // 直接返回 Java 对象
}

CodeAnalysis result = agent.analyze(code);  // 干净、安全、不会出错

```

---

## 二、OutputParser 的工作原理

LangChain4j 的 OutputParser 做了三件事：

### 第一步：生成格式指令

框架根据方法的返回类型，自动生成一段格式指令追加到提示词里。

比如返回类型是 `BugReport`，框架会自动追加类似这样的指令：

```Plain Text
你必须以如下 JSON 格式回复，不要包含任何其他文字：
{
  "title": (string),
  "severity": (string),
  "affectedComponents": (array of string),
  "rootCause": (string),
  "suggestedFix": (string)
}

```

### 第二步：调用模型

把带格式指令的完整提示词发给模型，模型返回符合格式的文本。

### 第三步：解析返回值

框架把模型返回的文本解析成对应的 Java 类型并返回。

整个过程对你完全透明，你只需要关心接口定义。

---

## 三、基本类型输出

LangChain4j 支持直接返回基本类型，框架会自动处理解析：

```Java
interface BasicOutputAgent {

    // 返回 String：直接返回模型输出文本
    String summarize(String text);

    // 返回 int：模型输出数字，框架解析为 int
    int estimateComplexity(String code);

    // 返回 double：适合评分类任务
    double scoreReadability(String code);

    // 返回 boolean：判断类任务
    boolean containsSensitiveInfo(String text);
}

```

```Java
BasicOutputAgent agent = AiServices.create(BasicOutputAgent.class, model);

int complexity = agent.estimateComplexity("""
        public void process(List<Order> orders) {
            for (Order order : orders) {
                for (Item item : order.getItems()) {
                    if (item.isExpired()) {
                        notify(order.getUserId(), item);
                    }
                }
            }
        }
""");
// 返回：2（嵌套循环复杂度）

boolean hasSensitiveInfo = agent.containsSensitiveInfo("用户手机号：138****0000");
// 返回：true

```

---

## 四、枚举类型输出

枚举非常适合分类任务，框架会把模型输出映射到枚举值：

```Java
// 定义枚举
public enum Severity { HIGH, MEDIUM, LOW }
public enum Sentiment { POSITIVE, NEGATIVE, NEUTRAL }
public enum Language { JAVA, PYTHON, JAVASCRIPT, GO, RUST, OTHER }

interface ClassificationAgent {

    @UserMessage("分析以下代码的 Bug 严重程度：\n{{code}}")
    Severity assessBugSeverity(@V("code") String code);

    @UserMessage("分析以下用户评论的情感倾向：\n{{review}}")
    Sentiment analyzeSentiment(@V("review") String review);

    @UserMessage("识别以下代码使用的编程语言：\n{{code}}")
    Language detectLanguage(@V("code") String code);
}

```

```Java
ClassificationAgent agent = AiServices.create(ClassificationAgent.class, model);

Severity severity = agent.assessBugSeverity("NullPointerException in payment flow");
// 返回：Severity.HIGH

Sentiment sentiment = agent.analyzeSentiment("这个产品真的太好用了，强烈推荐！");
// 返回：Sentiment.POSITIVE

```

---

## 五、Record 和 POJO 结构化输出

这是 OutputParser 最强大的用法——直接返回复杂 Java 对象。

### 使用 Java Record（推荐）

```Java
// 定义输出结构
record BugReport(
    String title,
    String severity,                // HIGH / MEDIUM / LOW
    List<String> affectedComponents,
    String rootCause,
    String suggestedFix,
    int estimatedFixTimeMinutes
) {}

interface BugAnalysisAgent {

    @SystemMessage("你是一个资深 Java 工程师，擅长分析异常堆栈和代码问题。")
    @UserMessage("分析以下异常堆栈，生成 Bug 报告：\n\n{{stackTrace}}")
    BugReport analyze(@V("stackTrace") String stackTrace);
}

```

```Java
BugAnalysisAgent agent = AiServices.create(BugAnalysisAgent.class, model);

BugReport report = agent.analyze("""
    java.lang.NullPointerException: Cannot invoke "String.length()" because "str" is null
        at com.example.OrderService.processOrder(OrderService.java:45)
        at com.example.OrderController.createOrder(OrderController.java:78)
        at sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)
""");

System.out.println("标题：" + report.title());
// 标题：OrderService.processOrder 中的空指针异常

System.out.println("严重程度：" + report.severity());
// 严重程度：HIGH

System.out.println("根因：" + report.rootCause());
// 根因：processOrder 方法未对 str 参数进行空值检查

System.out.println("修复建议：" + report.suggestedFix());
// 修复建议：在使用 str 前添加 Objects.requireNonNull(str) 或 null 检查

System.out.println("预计修复时间：" + report.estimatedFixTimeMinutes() + " 分钟");
// 预计修复时间：15 分钟

```

### 嵌套结构

OutputParser 支持嵌套对象：

````Java
record CodeReviewResult(
    String overallAssessment,
    List<CodeIssue> issues,
    List<String> positives,
    int qualityScore        // 0-100
) {}

record CodeIssue(
    String type,            // BUG / PERFORMANCE / STYLE / SECURITY
    int lineNumber,
    String description,
    String suggestion
) {}

interface CodeReviewAgent {
    @UserMessage("对以下代码进行全面 Code Review：\n\n```java\n{{code}}\n```")
    CodeReviewResult review(@V("code") String code);
}

````

```Java
CodeReviewResult result = agent.review(userCode);

System.out.println("整体评分：" + result.qualityScore());
result.issues().forEach(issue ->
        System.out.printf("第 %d 行 [%s]：%s → 建议：%s%n",
                issue.lineNumber(), issue.type(),
                issue.description(), issue.suggestion())
);

```

---

## 六、List 和 Map 输出

### 返回 List

```Java
interface ListOutputAgent {

    @UserMessage("从以下需求描述中提取用户故事列表，每条用 'As a ... I want ... So that ...' 格式：\n{{requirement}}")
    List<String> extractUserStories(@V("requirement") String requirement);

    @UserMessage("分析以下系统设计，列出潜在的风险点：\n{{design}}")
    List<String> identifyRisks(@V("design") String design);
}

```

```Java
List<String> stories = agent.extractUserStories("用户需要能够登录系统并管理自己的个人信息");
stories.forEach(System.out::println);
// As a registered user, I want to log into the system, So that I can access my account
// As a user, I want to update my personal information, So that my profile stays current

```

### 返回 List of Objects

```Java
record SubTask(
    String title,
    String description,
    int estimatedHours,
    String priority       // P0/P1/P2
) {}

interface TaskBreakdownAgent {
    @UserMessage("将以下任务拆解为具体的子任务：\n{{task}}")
    List<SubTask> breakdown(@V("task") String task);
}

```

```Java
List<SubTask> subtasks = agent.breakdown("开发用户登录功能，包含 JWT 认证和记住我功能");
subtasks.forEach(t ->
        System.out.printf("[%s] %s（%dh）：%s%n",
                t.priority(), t.title(), t.estimatedHours(), t.description())
);

```

---

## 七、自定义 OutputParser

当内置解析能力不满足需求时，可以实现自定义 OutputParser。

### 实现 OutputParser 接口

```Java
import dev.langchain4j.model.output.OutputParser;

// 自定义解析器：解析"分数: XX"格式的输出
public class ScoreOutputParser implements OutputParser<Integer> {

    @Override
    public Integer parse(String text) {
        // 匹配 "分数: 85" 或 "Score: 85" 等格式
        Pattern pattern = Pattern.compile("(?:分数|Score|得分)[：:][\\s]*(\\d+)");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        // 尝试直接解析数字
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new OutputParserException("无法从以下文本中解析分数：" + text);
        }
    }

    @Override
    public String formatInstructions() {
        // 这段文字会被自动追加到提示词里，告诉模型如何输出
        return "请以\"分数: XX\"的格式返回评分（0-100的整数）。";
    }
}

```

### 注册自定义 OutputParser

```Java
// 目前自定义 OutputParser 通常通过直接调用底层 API 使用
// 或者通过包装成 AiServices 的方式

// 方式：在 AiServices 方法里返回 String，然后手动解析
interface EssayGradingAgent {
    @UserMessage("评分以下作文（0-100分）：\n{{essay}}")
    String gradeEssay(@V("essay") String essay);  // 返回 String
}

// 调用层手动解析
String rawScore = agent.gradeEssay(essay);
int score = new ScoreOutputParser().parse(rawScore);

```

---

## 八、JSON Schema 与结构化输出

理解 OutputParser 背后的 JSON Schema 机制，能帮你写出更好的输出结构。

### LangChain4j 如何生成 JSON Schema

当你定义一个 Record 或 POJO 作为返回类型时，LangChain4j 会自动根据字段定义生成 JSON Schema：

```Java
// 你定义的 Record
record TaskPlan(
    String goal,
    List<String> steps,
    String priority,        // HIGH/MEDIUM/LOW
    int estimatedDays
) {}

```

框架生成的 JSON Schema（自动注入到提示词）：

```JSON
{
  "type": "object",
  "properties": {
    "goal": { "type": "string" },
    "steps": { "type": "array", "items": { "type": "string" } },
    "priority": { "type": "string" },
    "estimatedDays": { "type": "integer" }
  },
  "required": ["goal", "steps", "priority", "estimatedDays"]
}

```

### 用 @Description 增强 Schema

通过 `@Description` 注解给字段加上说明，帮助模型更准确地填写：

```Java
import dev.langchain4j.model.output.structured.Description;

record TaskPlan(
    @Description("任务的核心目标，一句话描述")
    String goal,

    @Description("实现目标的具体步骤，每步都要可执行")
    List<String> steps,

    @Description("优先级，只能是 HIGH、MEDIUM 或 LOW 之一")
    String priority,

    @Description("完成所有步骤需要的估算天数（工作日）")
    int estimatedDays
) {}

```

加上 `@Description` 后，模型填写的准确率会明显提升。

---

## 九、OpenAI Structured Outputs 支持

OpenAI 的较新模型支持 Structured Outputs，可以 100% 保证输出符合指定 JSON Schema（不会有格式错误）。LangChain4j 已经支持这个特性：

```Java
// 开启 Structured Outputs（仅 OpenAI 支持）
ChatLanguageModel model = OpenAiChatModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-4o-2024-08-06")   // 需要支持该特性的模型版本
        .strictJsonSchema(true)            // 开启严格 JSON Schema 模式
        .build();

// 使用方式与普通结构化输出完全一样
interface StrictOutputAgent {
    BugReport analyze(String stackTrace);
}

// 框架会自动把 BugReport 的结构转成严格 JSON Schema 发给 OpenAI
// OpenAI 保证 100% 按格式返回，不会出现解析失败的情况

```

---

## 十、常见坑和最佳实践

### 坑一：字段名要有语义，避免缩写

```Java
// 不好：模型不知道 "qty" 是什么
record OrderItem(String id, int qty, double amt) {}

// 好：字段名清晰，模型能准确填写
record OrderItem(String productId, int quantity, double totalAmount) {}

```

### 坑二：避免过度嵌套

嵌套层级过深（超过 3 层）会让模型填写出错率上升：

```Java
// 不好：嵌套过深
record Report(Analysis analysis) {}
record Analysis(Detail detail) {}
record Detail(SubDetail subDetail) {}
record SubDetail(String value) {}

// 好：扁平化设计
record Report(
    String analysisType,
    String analysisDetail,
    String detailValue
) {}

```

### 坑三：List 字段要给出合理的元素数量预期

```Java
interface SummaryAgent {
    @UserMessage("提取以下文章的关键观点（3-5条）：\n{{text}}")
    // 在 UserMessage 里指定数量，避免模型返回太多或太少
    List<String> extractKeyPoints(@V("text") String text);
}

```

### 坑四：数字字段容易返回 null 或非数字

为 int/double 字段加上 `@Description` 说明期望范围：

```Java
record QualityAssessment(
    @Description("代码质量评分，0到100的整数，不能为 null")
    int qualityScore,

    @Description("技术债务估算（人天），正整数")
    int technicalDebtDays
) {}

```

### 最佳实践：用 Optional 处理可选字段

```Java
// Java Record 不直接支持 Optional，但可以这样处理
record AnalysisResult(
    String mainIssue,
    String secondaryIssue,    // 可能为 null
    List<String> suggestions
) {
    // 提供安全访问方法
    public Optional<String> getSecondaryIssue() {
        return Optional.ofNullable(secondaryIssue);
    }
}

```

---

## 十一、完整示例：自动化代码审查系统

```Java
// 完整的输出结构定义
record CodeReviewReport(
    @Description("整体代码质量评价，2-3句话")
    String overallAssessment,

    @Description("代码质量评分，0-100的整数")
    int qualityScore,

    @Description("发现的问题列表，按严重程度降序排列")
    List<CodeIssue> issues,

    @Description("代码做得好的地方，正向反馈")
    List<String> positives,

    @Description("最重要的3条改进建议")
    List<String> topRecommendations
) {}

record CodeIssue(
    @Description("问题类型：BUG、PERFORMANCE、SECURITY、STYLE、MAINTAINABILITY 之一")
    String type,

    @Description("问题所在的大致行号或方法名")
    String location,

    @Description("问题的具体描述")
    String description,

    @Description("具体的修复建议，包含示例代码片段（如有必要）")
    String suggestion,

    @Description("严重程度：CRITICAL、HIGH、MEDIUM、LOW 之一")
    String severity
) {}

```

````Java
// Agent 定义
interface AutoCodeReviewAgent {

    @SystemMessage("""
            你是一个资深 Java 工程师，有丰富的代码审查经验。
            审查风格：严格、建设性、关注实际工程问题。
            重点关注：空指针风险、性能瓶颈、安全漏洞、可维护性。
            """)
    @UserMessage("""
            请对以下 Java 代码进行全面的 Code Review：

            ```java
            {{code}}
            ```

            PR 描述：{{prDescription}}
            """)
    CodeReviewReport review(
            @V("code") String code,
            @V("prDescription") String prDescription
    );
}

````

```Java
// 使用
AutoCodeReviewAgent agent = AiServices.create(AutoCodeReviewAgent.class, model);

CodeReviewReport report = agent.review(
        """
        public List<User> getActiveUsers(String departmentId) {
            List<User> users = userRepo.findByDepartment(departmentId);
            List<User> active = new ArrayList<>();
            for (int i = 0; i < users.size(); i++) {
                if (users.get(i).getStatus().equals("ACTIVE")) {
                    active.add(users.get(i));
                }
            }
            return active;
        }
        """,
        "优化用户查询接口，支持按部门筛选"
);

System.out.println("质量评分：" + report.qualityScore() + "/100");
System.out.println("整体评价：" + report.overallAssessment());
System.out.println("\n问题列表（共 " + report.issues().size() + " 个）：");
report.issues().forEach(issue ->
        System.out.printf("[%s][%s] %s → %s%n",
                issue.severity(), issue.type(),
                issue.description(), issue.suggestion())
);

```

---

## 十二、本节小结

这节课我们把 LangChain4j 的 OutputParser 机制全面讲透了。核心要点：

- **工作原理**：框架自动生成格式指令注入提示词 → 模型按格式输出 → 框架解析成 Java 对象
- **支持类型**：基本类型、枚举、Record/POJO、List、嵌套对象
- **@Description 注解**：给字段加语义描述，显著提升模型填写准确率
- **Structured Outputs**：OpenAI 新特性，100% 保证格式正确，通过 `strictJsonSchema(true)` 开启
- **自定义 OutputParser**：实现 `OutputParser` 接口，可处理非标准格式输出
- **设计原则**：字段名语义化、避免过度嵌套、数字字段标注范围、List 字段给出数量预期

OutputParser 让 Agent 的输出从"不可预测的文本"变成"可靠的结构化数据"，是 Agent 系统工程化的关键一步。

下一节课，我们进入 LangChain4j 最重要的高级能力之一：**RAG（检索增强生成）的完整实现**，看看如何让 Agent 真正具备对私有知识库的问答能力。

---

## 十三、引用文档与延伸阅读

- LangChain4j 结构化输出官方文档：https://docs.langchain4j.dev/tutorials/structured-outputs
- LangChain4j AiServices 返回类型说明：https://docs.langchain4j.dev/tutorials/ai-services#supported-return-types
- LangChain4j @Description 注解源码：https://github.com/langchain4j/langchain4j/blob/main/langchain4j-core/src/main/java/dev/langchain4j/model/output/structured/Description.java
- OpenAI Structured Outputs 官方文档：https://platform.openai.com/docs/guides/structured-outputs
- JSON Schema 官方规范：https://json-schema.org/understanding-json-schema
- GitHub 示例代码：https://github.com/langchain4j/langchain4j-examples/tree/main/other-examples/src/main/java/OtherServiceExamples.java

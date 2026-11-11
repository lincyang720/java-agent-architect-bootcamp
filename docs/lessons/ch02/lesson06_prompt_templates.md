# 第2章第6节：提示词模板（Prompt Templates）与变量注入

大家好，我是秋天。

上一节课我们把模型集成层讲透了，知道了如何通过适配器模式自由切换不同模型提供商。

这节课，我们来讲一个在 Agent 开发中极其重要但经常被低估的话题：**提示词模板（Prompt Templates）与变量注入机制。**

很多同学在做 Agent 的时候，会把提示词直接硬编码在代码里，写成字符串拼接。这在原型阶段可以，但一旦到了生产环境，你就会遇到：

- 提示词散落在代码各处，维护困难
- 变量注入靠手动拼接，容易出错
- 多语言、多角色场景无法优雅处理
- 提示词版本管理无从下手

**提示词模板就是解决这些问题的工程化方案。**

学完这节课，你会掌握：

1. LangChain4j 提示词模板的核心 API
2. 变量注入的多种方式（`@V`、`PromptTemplate`、结构化模板）
3. 系统提示词与用户提示词的组织策略
4. 动态提示词的构建模式
5. 提示词管理的最佳实践

---

## 一、为什么要用提示词模板

先看一个没有模板的反例：

````Java
// 反例：硬编码字符串拼接
public String buildPrompt(String language, String code, String focusArea) {
    return "请对以下 " + language + " 代码进行 Code Review，重点关注：" + focusArea + "\n\n"
            + "代码内容：\n```" + language + "\n" + code + "\n```\n\n"
            + "请给出具体的改进建议，按严重程度排序。";
}

````

这种方式的问题：

- 逻辑和模板混在一起，难以阅读
- 变量名、顺序全靠记忆，容易传错
- 修改提示词需要改代码，测试成本高
- 多处复用时难以保持一致

**用模板的正确方式：**

````Java
// 模板与逻辑分离
String template = """
        请对以下 {{language}} 代码进行 Code Review，重点关注：{{focusArea}}

        代码内容：
        ```{{language}}
        {{code}}
        ```

        请给出具体的改进建议，按严重程度排序。
        """;

Prompt prompt = PromptTemplate.from(template)
        .apply(Map.of(
                "language", language,
                "code", code,
                "focusArea", focusArea
        ));

````

模板与变量分离，清晰、安全、易维护。

---

## 二、LangChain4j 的核心模板 API

### PromptTemplate：基础模板类

```Java
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.input.Prompt;

// 定义模板（使用 {{变量名}} 语法）
PromptTemplate template = PromptTemplate.from(
        "你是一个 {{role}}，请用 {{language}} 回答以下问题：\n\n{{question}}"
);

// 注入变量，生成最终 Prompt
Prompt prompt = template.apply(Map.of(
        "role", "资深 Java 架构师",
        "language", "中文",
        "question", "如何设计一个高可用的微服务架构？"
));

// 获取文本内容
String promptText = prompt.text();

// 直接作为用户消息使用
UserMessage userMessage = prompt.toUserMessage();

```

### 单变量模板

当模板只有一个变量时，可以用简化写法：

```Java
PromptTemplate template = PromptTemplate.from("请翻译以下文本：{{it}}");

Prompt prompt = template.apply("Hello, World!");
// 生成：请翻译以下文本：Hello, World!

```

`{{it}}` 是单变量模板的特殊占位符，`apply` 直接传值即可。

---

## 三、在 AiServices 中使用模板注解

AiServices 提供了一套注解驱动的模板机制，这是 LangChain4j 里最推荐的使用方式。

### @SystemMessage + @UserMessage 组合

````Java
interface CodeReviewAgent {

    @SystemMessage("""
            你是一个资深的 {{language}} 工程师，拥有 10 年以上代码审查经验。
            审查风格：严格但建设性，每个问题都给出具体的改进建议。
            """)
    @UserMessage("""
            请对以下代码进行全面的 Code Review：

            语言：{{language}}
            关注点：{{focusArea}}

            代码：
            ```{{language}}
            {{code}}
            ```

            请按以下格式输出：
            1. 整体评价（1-2句话）
            2. 问题列表（按严重程度排序）
            3. 改进建议
            """)
    String review(
            @V("language") String language,
            @V("focusArea") String focusArea,
            @V("code") String code
    );
}

````

```Java
CodeReviewAgent agent = AiServices.create(CodeReviewAgent.class, model);

String result = agent.review(
        "Java",
        "性能问题、空指针风险",
        """
        public List<User> getActiveUsers() {
            List<User> all = userRepo.findAll();
            List<User> active = new ArrayList<>();
            for (User u : all) {
                if (u.isActive()) active.add(u);
            }
            return active;
        }
        """
);

```

### 注意：@V 注解的变量作用域

`@V` 注解的变量可以在 `@SystemMessage` 和 `@UserMessage` 中同时使用：

```Java
interface MultilingualAgent {

    // {{language}} 同时出现在 SystemMessage 和 UserMessage 中
    @SystemMessage("你是一个专业助手，请用 {{language}} 回答所有问题。")
    @UserMessage("问题：{{question}}")
    String ask(
            @V("language") String language,    // 注入到两处模板
            @V("question") String question
    );
}

String answer = agent.ask("日语", "什么是 Java 虚拟线程？");
// 系统提示词：你是一个专业助手，请用日语回答所有问题。
// 用户消息：问题：什么是 Java 虚拟线程？
// 模型将用日语回答

```

---

## 四、动态系统提示词

有些场景下，系统提示词本身需要完全动态生成，不适合用静态注解。

### 方式一：在接口方法里传入系统提示词

```Java
interface FlexibleAgent {

    // 把 systemPrompt 作为参数传入
    String chat(@dev.langchain4j.service.SystemMessage String systemPrompt,
                @UserMessage String userMessage);
}

```

### 方式二：通过 Builder 动态设置

```Java
// 根据业务逻辑动态构建系统提示词
public CustomerServiceAgent buildAgent(String agentName, String businessDomain,
                                        List<String> capabilities) {
    String systemPrompt = buildSystemPrompt(agentName, businessDomain, capabilities);

    return AiServices.builder(CustomerServiceAgent.class)
            .chatLanguageModel(model)
            .systemMessageProvider(memoryId -> systemPrompt)  // 每次对话前动态提供
            .build();
}

private String buildSystemPrompt(String name, String domain, List<String> caps) {
    String capList = caps.stream()
            .map(c -> "- " + c)
            .collect(Collectors.joining("\n"));

    return PromptTemplate.from("""
            你是"{{name}}"，一个专注于 {{domain}} 领域的智能助手。

            你能够帮助用户：
            {{capabilities}}

            回答原则：
            1. 只在自己能力范围内作答
            2. 超出范围时，明确告知并引导用户
            3. 回答简洁准确，不超过 300 字
            """)
            .apply(Map.of(
                    "name", name,
                    "domain", domain,
                    "capabilities", capList
            ))
            .text();
}

```

### 方式三：systemMessageProvider 实现多租户提示词

```Java
// 根据用户 ID 动态加载不同的系统提示词（多租户场景）
MultiUserAgent agent = AiServices.builder(MultiUserAgent.class)
        .chatLanguageModel(model)
        .systemMessageProvider(memoryId -> {
            // memoryId 就是 @MemoryId 的值（通常是用户ID或租户ID）
            String tenantId = extractTenantId(memoryId.toString());
            return tenantConfigService.getSystemPrompt(tenantId);
        })
        .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(20))
        .build();

```

---

## 五、结构化提示词的组织模式

随着 Agent 功能增多，提示词会变得越来越复杂。以下是几种常见的组织模式。

### 模式一：分段式提示词

把系统提示词拆成几个语义清晰的段落：

```Java
interface EnterpriseAgent {

    @SystemMessage("""
            # 角色定义
            你是"智工"，企业内部的 AI 助手，服务于研发团队。

            # 核心职责
            - 回答技术问题（Java、Spring Boot、微服务架构）
            - 协助代码 Review 和调试
            - 解答内部系统和流程相关的问题

            # 行为约束
            - 只回答与工作相关的问题
            - 涉及公司机密信息时，拒绝回答并提示通过正规渠道获取
            - 不确定的信息必须标注"不确定，建议核实"

            # 输出格式
            - 代码示例用代码块包裹
            - 较长的回答用标题分段
            - 结尾可附上相关文档链接（如果有）
            """)
    String ask(@MemoryId String userId, @UserMessage String question);
}

```

### 模式二：Few-Shot 模板

通过在模板里嵌入示例，引导模型输出特定格式：

```Java
interface DataExtractorAgent {

    @UserMessage("""
            从以下文本中提取结构化信息，严格按照示例格式输出 JSON。

            示例输入：
            "张三，男，28岁，Java工程师，联系方式：zhangsan@example.com，手机：13800138000"

            示例输出：
            {"name":"张三","gender":"男","age":28,"title":"Java工程师","email":"zhangsan@example.com","phone":"13800138000"}

            现在处理以下文本：
            {{text}}

            只输出 JSON，不要任何解释。
            """)
    PersonInfo extract(@V("text") String text);
}

record PersonInfo(String name, String gender, int age,
                  String title, String email, String phone) {}

```

### 模式三：Chain-of-Thought 模板

引导模型一步步思考，提升复杂任务的准确率：

````Java
interface AnalysisAgent {

    @UserMessage("""
            请分析以下代码的时间复杂度。

            代码：
            ```java
            {{code}}
            ```

            请按以下步骤分析：
            1. 识别所有循环和递归
            2. 分析每层循环的执行次数
            3. 计算总体时间复杂度
            4. 给出最终结论

            请展示完整的分析过程。
            """)
    String analyzeComplexity(@V("code") String code);
}

````

---

## 六、提示词模板的外部化管理

在大型项目里，提示词应该像配置文件一样管理，而不是硬编码在代码里。

### 从配置文件加载

```YAML
# prompts.yml
agents:
  customer-service:
    system: |
      你是"小智"，专业的电商客服助手。
      职责：处理订单查询、退换货、积分问题。
      原则：简洁准确，遇到无法处理的问题引导联系人工。
    review-template: |
      用户评价：{{review}}
      请判断该评价的情感倾向，并提取核心诉求。
  code-assistant:
    system: |
      你是一个资深 Java 工程师，专注于代码质量和性能优化。

```

```Java
@Component
public class PromptTemplateManager {

    @Value("${agents.customer-service.system}")
    private String customerServiceSystem;

    @Value("${agents.customer-service.review-template}")
    private String reviewTemplate;

    public String renderReviewPrompt(String review) {
        return PromptTemplate.from(reviewTemplate)
                .apply(Map.of("review", review))
                .text();
    }

    public String getCustomerServiceSystem() {
        return customerServiceSystem;
    }
}

```

### 从数据库动态加载（多租户场景）

```Java
@Service
public class DynamicPromptService {

    @Autowired
    private PromptTemplateRepository repository;

    private final LoadingCache<String, PromptTemplate> cache = CacheBuilder.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build(CacheLoader.from(this::loadTemplate));

    public String render(String templateKey, Map<String, Object> variables) {
        PromptTemplate template = cache.getUnchecked(templateKey);
        return template.apply(variables).text();
    }

    private PromptTemplate loadTemplate(String key) {
        PromptTemplateEntity entity = repository.findByKey(key)
                .orElseThrow(() -> new IllegalArgumentException("模板不存在：" + key));
        return PromptTemplate.from(entity.getContent());
    }
}

```

---

## 七、特殊场景处理

### 处理包含花括号的代码内容

当提示词模板里需要包含 Java/JSON 代码（本身含有 `{}`），要避免与模板占位符冲突：

```Java
// 方案一：用 @UserMessage 的 text 属性直接传入代码，不经过模板渲染
interface CodeAgent {
    String analyze(@UserMessage String fullPrompt);  // 整个 prompt 作为用户消息
}

// 调用时手动构建，代码不经过模板变量替换
String prompt = "请分析以下 JSON Schema：\n\n" + rawJsonSchema;
agent.analyze(prompt);

```

```Java
// 方案二：对花括号转义（LangChain4j 用 \{\} 转义）
@UserMessage("固定格式示例：\\{\"key\": \"value\"\\}，你的输入是：{{userInput}}")
String process(@V("userInput") String userInput);

```

### 多语言提示词

```Java
interface I18nAgent {

    @SystemMessage("You are a professional assistant. Always respond in {{locale}} language.")
    @UserMessage("{{question}}")
    String ask(
            @V("locale") String locale,
            @V("question") String question
    );
}

// 中文场景
agent.ask("Chinese", "什么是微服务架构？");

// 英文场景
agent.ask("English", "What is microservices architecture?");

// 日语场景
agent.ask("Japanese", "マイクロサービスアーキテクチャとは何ですか？");

```

---

## 八、提示词质量检测

在生产环境，可以加入提示词渲染结果的校验，提前发现问题：

```Java
public class SafePromptRenderer {

    public String render(String template, Map<String, Object> variables) {
        // 检查模板是否有未填写的变量
        String rendered = PromptTemplate.from(template).apply(variables).text();

        // 检查是否还有未替换的占位符
        if (rendered.contains("{{") && rendered.contains("}}")) {
            log.warn("提示词中存在未替换的变量占位符: {}", rendered);
            throw new IllegalStateException("提示词变量注入不完整，请检查变量名是否匹配");
        }

        // 检查提示词长度（粗略估算 Token 数）
        int estimatedTokens = rendered.length() / 3;  // 中文约 1.5 字/token
        if (estimatedTokens > 8000) {
            log.warn("提示词过长，估算 Token 数：{}，可能影响模型效果", estimatedTokens);
        }

        return rendered;
    }
}

```

---

## 九、完整示例：多场景提示词管理系统

````Java
// 提示词常量统一管理
public final class PromptTemplates {

    // 客服场景
    public static final String CUSTOMER_SERVICE_SYSTEM = """
            你是"{{agentName}}"，{{company}} 的专属客服助手。
            服务范围：{{serviceScope}}
            回答原则：简洁准确，无法处理时引导人工客服（电话：{{hotline}}）
            """;

    public static final String ORDER_QUERY = """
            用户询问订单问题：{{userQuestion}}
            用户ID：{{userId}}
            请优先调用 queryOrder 工具获取最新信息后再回答。
            """;

    // 代码助手场景
    public static final String CODE_REVIEW = """
            请对以下 {{language}} 代码进行 Code Review：

            ```{{language}}
            {{code}}
            ```

            关注点：{{focusAreas}}
            输出格式：问题列表（严重程度 + 位置 + 建议）
            """;

    private PromptTemplates() {}
}

````

```Java
// Agent 工厂：根据配置动态创建 Agent
@Component
public class AgentFactory {

    @Autowired private ChatLanguageModel model;
    @Autowired private AgentConfigRepository configRepo;

    public CustomerServiceAgent createCustomerServiceAgent(String tenantId) {
        AgentConfig config = configRepo.findByTenantId(tenantId);

        String systemPrompt = PromptTemplate.from(PromptTemplates.CUSTOMER_SERVICE_SYSTEM)
                .apply(Map.of(
                        "agentName", config.getAgentName(),
                        "company", config.getCompanyName(),
                        "serviceScope", config.getServiceScope(),
                        "hotline", config.getHotline()
                ))
                .text();

        return AiServices.builder(CustomerServiceAgent.class)
                .chatLanguageModel(model)
                .systemMessageProvider(memoryId -> systemPrompt)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(20))
                .build();
    }
}

```

---

## 十、最佳实践总结

### 1. 模板与代码分离

把提示词模板抽离到常量类、配置文件或数据库，不要散落在业务代码里。

### 2. 变量命名要语义化

```Java
// 不好
@UserMessage("{{a}} {{b}} {{c}}")
String process(@V("a") String x, @V("b") String y, @V("c") String z);

// 好
@UserMessage("请把 {{sourceLanguage}} 代码重写为 {{targetLanguage}} 版本：\n{{code}}")
String rewrite(@V("sourceLanguage") String src, @V("targetLanguage") String tgt, @V("code") String code);

```

### 3. 提示词要版本化

重要的提示词变更要记录版本和原因，方便追溯效果变化。

### 4. 先写提示词，再写代码

在实现 Agent 功能之前，先把提示词设计好并在 Playground 里验证效果，再写代码实现。

### 5. 提示词长度控制

系统提示词建议控制在 500 字以内，聚焦关键约束。过长的提示词会稀释核心指令，效果反而下降。

---

## 十一、本节小结

这节课我们把 LangChain4j 的提示词模板机制全面讲透了。核心要点：

- **PromptTemplate**：核心模板类，`{{变量名}}` 语法，`apply(Map)` 注入变量
- **@SystemMessage + @UserMessage**：AiServices 里的注解驱动模板，推荐优先使用
- **@V 注解**：绑定方法参数到模板变量，支持跨 System/User Message 共享
- **动态提示词**：通过 `systemMessageProvider` 实现运行时动态生成系统提示词
- **外部化管理**：大型项目里提示词应从配置文件或数据库加载，与代码解耦
- **设计原则**：模板语义化、版本化、先写模板再写代码、控制长度

提示词工程是 Agent 开发里最需要反复打磨的部分。掌握了模板化管理，你的 Agent 提示词就能做到可维护、可复用、可测试。

下一节课，我们进入 LangChain4j 最核心的能力之一：**RAG（检索增强生成）**——如何让 Agent 具备对私有知识库的问答能力。

---

## 十二、引用文档与延伸阅读

- LangChain4j 提示词模板官方文档：https://docs.langchain4j.dev/tutorials/prompt-templates
- LangChain4j AiServices 注解文档：https://docs.langchain4j.dev/tutorials/ai-services
- LangChain4j PromptTemplate 源码：https://github.com/langchain4j/langchain4j/blob/main/langchain4j-core/src/main/java/dev/langchain4j/model/input/PromptTemplate.java
- 提示词工程指南（OpenAI）：https://platform.openai.com/docs/guides/prompt-engineering
- 提示词工程指南（Anthropic）：https://docs.anthropic.com/en/docs/build-with-claude/prompt-engineering/overview
- GitHub 示例代码：https://github.com/langchain4j/langchain4j-examples

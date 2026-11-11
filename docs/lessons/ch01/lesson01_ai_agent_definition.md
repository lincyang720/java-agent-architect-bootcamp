# 第1章第1节：人工智能Agent的定义与核心特征（自主性、反应性、主动性、社交能力）

大家好，我是秋天。

从这一节开始，我们正式进入 Agent 课程最基础、但也是最关键的一个问题：

**到底什么是 Agent？**

如果这个问题没有想清楚，后面你学再多框架、提示词、工作流、工具调用，都会容易陷入一个误区：

你看到一个“能聊天的系统”，就觉得它是 Agent；你看到一个“会调用工具的大模型”，也觉得它是 Agent；你看到一个“自动执行任务的脚本”，又觉得它也是 Agent。

但实际上，这三者并不能简单画等号。

所以这一节课，我们要做的不是追热点，而是先把 Agent 的定义讲透。只有定义清楚了，后面我们才能判断：

- 什么是真正的 Agent
- 什么只是自动化脚本
- 什么是带一点智能的工作流
- 什么才是大模型时代值得关注的智能体系统

这一节课，你要重点掌握五件事：

1. Agent 的基本定义到底是什么
2. Agent 和普通程序、传统软件有什么本质区别
3. 一个系统为什么必须同时具备若干核心特征，才能被称为 Agent
4. 四个最经典的核心特征：**自主性、反应性、主动性、社交能力**
5. 为什么这些经典定义，在今天的 LLM Agent 时代依然成立

---

## 一、什么是 Agent：先给出一个课程里的工作定义

在人工智能领域，一个非常经典的理解是：

**Agent 是能够感知环境、基于目标或状态做出决策，并采取行动影响环境的实体。**

这个定义里有四个关键词，你一定要抓住：

- **感知环境**
- **做出决策**
- **采取行动**
- **影响结果**

换句话说，Agent 不是一个只负责“算答案”的系统，它是一个处在环境中的行动者。

比如：

- 一个搜索问答模型，只回答你一个问题，它更像是一个能力模块
- 一个能持续读取任务、调用搜索工具、整理信息、输出报告并根据反馈继续修正的系统，更接近 Agent
- 一个自动驾驶系统，能够实时感知路况并控制车辆行动，这也是典型 Agent
- 一个客服机器人，如果只会固定回复模板，它更像规则系统；但如果它能理解上下文、主动选择策略、调用业务接口完成用户诉求，那它就更接近 Agent

所以，**Agent 的关键不在于“像不像人”，而在于它是否具备围绕目标持续感知、决策和行动的能力。**

---

## 二、Agent和普通程序有什么区别

这一点在付费课程里必须讲透，因为很多人学 Agent 的最大困惑就在这里。

一个普通程序，通常是这样工作的：

- 输入固定
- 逻辑固定
- 输出固定

比如一个计算器程序：

- 输入 `2 + 3`
- 按照预设逻辑计算
- 输出 `5`

它当然很有用，但它不是 Agent。

为什么？

因为它没有真正意义上的：

- 环境感知
- 动态决策
- 持续行动
- 目标驱动

Agent 的运行方式更像这样：

- 先观察当前环境
- 判断自己当前所处状态
- 结合目标做出策略选择
- 执行动作
- 根据结果继续调整下一步

也就是说，**普通程序更像“执行指令”，而 Agent 更像“完成任务”。**

这是两种完全不同的系统思维。

### 一个最简单的对比

#### 普通程序

```Python

def send_email(to, subject, body):
    print(f"Send email to {to}: {subject}")

```

这个函数当然可以发送邮件，但它本身不是 Agent。因为：

- 它不知道什么时候该发邮件
- 它不知道发给谁更合适
- 它不知道邮件发完后的结果如何
- 它不会根据反馈调整策略

#### 一个极简 Agent 结构

```Python
class SimpleAgent:
    def __init__(self, goal, tools):
        self.goal = goal
        self.tools = tools

    def observe(self, environment):
        return environment.current_state()

    def decide(self, state):
        if "urgent_customer_issue" in state:
            return "send_followup_email"
        return "wait"

    def act(self, action):
        if action == "send_followup_email":
            self.tools["email"].send()

```

这里你会发现，Agent 和普通函数已经完全不是一个层次的问题。

它不是单次执行某个动作，而是：

- 先观察
- 再判断
- 再选择动作
- 再作用于环境

这就是 Agent 的雏形。

---

## 三、为什么“定义”在今天依然重要

很多人会说，现在不是大模型时代了吗？为什么还要讲这些传统定义？

因为如果你不理解传统 Agent 理论，你就会把很多东西混在一起：

- 把工作流当 Agent
- 把脚本自动化当 Agent
- 把一个提示词工程包装成 Agent
- 把“能说话”误认为“能行动”

但在真正的 AI 系统设计里，定义不是学术形式主义，而是帮助我们做系统边界判断。

比如你在做一个产品时，如果你说自己做的是 Agent，那你就必须回答：

- 它是否真的有环境输入？
- 它是否能够基于状态变化调整策略？
- 它是否能够围绕目标持续行动？
- 它是否能够与其他主体或系统进行协作？

如果这些问题回答不出来，那这个系统大概率还不是一个成熟 Agent，而只是一个“带点智能接口的自动化组件”。

所以，下面我们进入这节课最核心的部分：



## **Agent 的四个经典核心特征。**

---

## 四、核心特征一：自主性（Autonomy）

### 1. 什么叫自主性

自主性，指的是 Agent 能够在没有人类持续逐步干预的情况下，自行控制自己的内部状态和行为。

请注意，这里讲的是“自行控制”，不是“完全脱离人类”。

也就是说，人类可以给 Agent 一个目标、一个约束范围、一组工具权限，但在执行过程中，Agent 不需要每一步都等人类下指令。

它可以自己决定：

- 先做什么
- 后做什么
- 哪一步该调用什么工具
- 当前是否需要调整策略

这就是自主性。

### 2. 自主性为什么重要

因为如果一个系统每一步都必须由用户明确指定，那么它本质上还是“被动执行器”，而不是智能体。

举个例子：

- 用户说：“先查资料，再提炼重点，再写摘要，再转成 PPT 提纲。”
- 系统严格照着每一步执行，没有任何判断空间

这种系统更像工作流引擎。

而如果用户只说：

- “帮我整理这份行业报告，给出面向 CEO 的 5 分钟汇报提纲。”

系统自己去判断：

- 需要不要先读取全文
- 是否要识别行业、公司、市场数据
- 是否要先提炼结论再做结构化表达
- 是否需要引用图表数据

这时，它才开始具备 Agent 的自主性。

### 3. 一个自主性的代码示意

```Python
class ResearchAgent:
    def run(self, task, tools):
        notes = []
        if "report" in task:
            data = tools["search"].query(task)
            notes.append(data)

        if len(notes) > 0:
            summary = tools["llm"].summarize(notes)
            return summary
        return "No useful information found."

```

这段代码虽然很简化，但已经体现了一个关键点：

**系统不是等待每一步命令，而是在目标给定后，自主决定内部执行路径。**

### 4. 大模型时代的自主性更强，也更危险

在 LLM Agent 时代，自主性变强了，因为模型具备了：

- 任务理解能力
- 步骤拆解能力
- 工具调用能力
- 多轮决策能力

但同时也意味着，系统更容易：

- 做错决策
- 扩大错误影响
- 在权限边界内做出不理想操作

所以，真正专业的 Agent 设计，不是盲目追求“越自主越好”，而是要做：

**受约束的自主性。**

也就是：

- 有目标
- 有权限边界
- 有审查点
- 有回退机制

这才是工程上可落地的自主性。

---

## 五、核心特征二：反应性（Reactivity）

### 1. 什么叫反应性

反应性，指的是 Agent 能够感知环境变化，并及时根据变化做出响应。

这是 Agent 和“离线规划器”之间的重要区别。

一个系统如果只会预先制定计划，却无法根据环境变化调整行为，那它并不能很好地适应真实世界。

### 2. 为什么反应性是 Agent 的必备能力

现实世界最大的特点就是：

**环境一直在变。**

比如：

- 用户需求在变
- 外部接口返回在变
- 数据库状态在变
- 网络环境在变
- 任务优先级在变
- 其他 Agent 的行为也在变

如果一个 Agent 不能对这些变化做出及时反应，那它的计划再完美，也会快速失效。

### 3. 一个反应性的代码示意

```Python
class SupportAgent:
    def react(self, ticket):
        if ticket.priority == "P0":
            return "escalate_immediately"
        if ticket.sentiment == "angry":
            return "route_to_human"
        return "reply_normally"

```

这个例子很简单，但它体现了反应性的本质：

**不是按固定流程一走到底，而是根据环境状态切换行为。**

### 4. 在 LLM Agent 中，反应性体现在哪里

比如一个 Coding Agent：

- 先写代码
- 再运行测试
- 如果测试失败，就读取报错
- 根据报错修复代码
- 再次运行测试

这个循环，本质上就是反应性。

因为它不是机械执行，而是在根据环境返回结果动态调整。

你会发现，这正好也是今天很多 Agent 框架采用的核心模式：

**Observe → Reason → Act → Observe**

也就是：

- 观察
- 推理
- 行动
- 再观察

这条链路，是 Agent 的生命线。

---

## 六、核心特征三：主动性（Pro-activeness）

### 1. 什么叫主动性

主动性，指的是 Agent 不只是被动响应外部事件，还能够围绕目标主动采取行动。

这是非常重要的一点。

因为“会反应”不等于“会主动”。

比如一个系统：

- 只有收到用户指令才开始工作
- 没有新输入就什么都不做

它可能具备一定反应性，但主动性很弱。

真正有主动性的 Agent，会表现出：

- 围绕目标提前规划
- 主动寻找完成任务所需的信息
- 在必要时发起下一步动作
- 发现风险后主动提醒或调整策略

### 2. 主动性为什么是高级 Agent 的分水岭

因为从产品价值角度看，用户真正愿意为 Agent 付费，往往不是因为它“问一句答一句”，而是因为它：

**能把事情往前推进。**

比如：

- 一个销售 Agent，不只是回复客户问题，而是会主动跟进线索
- 一个项目管理 Agent，不只是记录任务，而是会主动提醒风险和依赖项
- 一个研究 Agent，不只是回答“是什么”，还会主动补充“你还应该关注什么”

这才叫主动性带来的商业价值。

### 3. 一个主动性的代码

```Python
class TaskAgent:
    def plan_next_step(self, goal, current_state):
        if current_state == "missing_information":
            return "search_more_information"
        if current_state == "enough_context":
            return "draft_solution"
        if current_state == "solution_ready":
            return "deliver_result"

```

这段代码体现的核心思想是：

Agent 不是只等外部事件触发，而是围绕目标持续推进任务状态。

### 4. 在 LLM Agent 里，主动性最容易被误解

很多人把“模型话多”误认为“模型主动”。

这其实是两个完全不同的概念。

真正的主动性，不是多说几句建议，而是：

- 主动识别任务缺口
- 主动补齐上下文
- 主动推进任务阶段
- 主动触发合理行动

所以，课程里你要记住一句话：

**主动性不是表达风格，而是目标驱动下的持续推进能力。**

---

## 七、核心特征四：社交能力（Social Ability）

### 1. 什么叫社交能力

社交能力，指的是 Agent 能够与其他 Agent、人类用户、外部系统进行交互、沟通和协作。

这里的“社交”不是拟人化地聊天，而是更广义上的：

- 信息交换
- 协议遵循
- 协同工作
- 任务移交
- 角色配合

### 2. 为什么社交能力非常关键

因为现实中的任务，很少是一个 Agent 在真空里独立完成的。

它通常要和很多对象协作：

- 与用户确认目标
- 与数据库交互
- 与搜索系统交互
- 与企业内部 API 交互
- 与其他 Agent 分工协作
- 必要时把任务交给人类审批

所以，一个现代 Agent，如果没有社交能力，就很难嵌入真实业务环境。

### 3. 一个社交能力的代码

```Python
class MultiAgentCoordinator:
    def delegate(self, task, agents):
        if task == "research":
            return agents["researcher"].run(task)
        if task == "review":
            return agents["reviewer"].run(task)
        return agents["generalist"].run(task)

```

这个例子虽然简化，但它已经体现了社交能力里的一个重要维度：

**交互与协同。**

### 4. 在今天的 LLM Agent 系统中，社交能力有哪些体现

非常常见的场景包括：

- Planner Agent 负责规划
- Executor Agent 负责执行
- Critic Agent 负责审查
- Human 负责最终确认

或者：

- Agent 先向用户追问缺失信息
- 然后调用搜索工具
- 再调用数据库
- 最后把结果发到飞书或邮件系统

你会发现，一个成熟 Agent 系统，本质上不是“单点智能”，而是“处在关系网络中的行动者”。

这就是社交能力的真正含义。

---

## 八、把四个核心特征放在一起理解

到这里，我们把四个概念分别讲完了。现在要把它们合起来理解。

一个真正有价值的 Agent，通常不是只具备其中一个特征，而是四者共同作用：

- **自主性**：不需要人类逐步遥控
- **反应性**：能根据环境变化及时调整
- **主动性**：能围绕目标持续推进任务
- **社交能力**：能与人和系统协作

如果一个系统只具备其中一个或两个，它往往还不够完整。

例如：

- 只有自主性，没有反应性：可能会一路错下去
- 只有反应性，没有主动性：只能被动应答
- 只有主动性，没有社交能力：很难融入真实业务流程
- 只有社交能力，没有自主性：更像接口层，不像 Agent

所以在设计 Agent 产品时，真正重要的不是“它是不是用了大模型”，而是：

**这四个核心能力到底具备了多少，成熟到了什么程度。**

---

## 九、一个更完整的Agent最小闭环

为了帮助你把概念真正落地，我这里给一个更完整的极简伪代码示意。

```Python
class Agent:
    def __init__(self, goal, tools, memory):
        self.goal = goal
        self.tools = tools
        self.memory = memory

    def observe(self, env):
        return env.get_state()

    def decide(self, state):
        if state["missing_info"]:
            return "search"
        if state["need_human_confirmation"]:
            return "ask_human"
        if state["ready_to_execute"]:
            return "execute"
        return "wait"

    def act(self, action):
        if action == "search":
            return self.tools["search"].query(self.goal)
        if action == "ask_human":
            return self.tools["messenger"].send("请确认关键参数")
        if action == "execute":
            return self.tools["executor"].run(self.goal)

    def run(self, env):
        while True:
            state = self.observe(env)
            action = self.decide(state)
            result = self.act(action)
            self.memory.append(result)
            if state.get("goal_completed"):
                break

```

这段代码虽然不是生产级代码，但它把四个核心特征基本都体现出来了：

- observe：体现**反应性**
- decide：体现**自主性**
- 围绕 goal 持续推进：体现**主动性**
- ask_human / tool coordination：体现**社交能力**

如果你以后做 Agent 产品设计、阅读框架源码、理解论文结构，都可以回到这个最小闭环来判断。

---

## 十、为什么这些经典特征在LLM时代依然不过时

很多新手会觉得，既然今天已经有了 ChatGPT、Claude、AutoGen、LangGraph、CrewAI，这些老的 Agent 定义是不是过时了？

答案恰恰相反：

**越是大模型时代，越需要回到这些经典特征。**

因为 LLM 只是让 Agent 的“认知能力”变强了，但并没有改变 Agent 的本质。

Agent 的本质依然是：

- 感知环境
- 围绕目标决策
- 采取行动
- 与环境持续交互

而大模型时代真正的变化是：

- 自主性更强了
- 反应性更灵活了
- 主动性更容易涌现出来了
- 社交能力从结构化协议扩展到了自然语言协作

所以，我们不是抛弃经典定义，而是要用经典定义去重新审视今天的 LLM Agent。

这样你才能避免被概念包装带偏。

---

## 十一、课程中的实战判断标准：如何判断一个系统是不是Agent

这里我给你一个在业务实践里非常好用的判断框架。

如果你看到一个系统，问自己这五个问题：

### 1. 它有没有明确目标？

如果没有目标，它可能只是一个工具。

### 2. 它能不能感知环境？

如果不能感知环境变化，它更像静态程序。

### 3. 它会不会根据状态变化调整行为？

如果不会调整，它更像固定流程。

### 4. 它能不能自己推进任务？

如果不能主动推进，它更像被动接口。

### 5. 它能不能和人或系统协作？

如果不能协作，它很难真正进入生产环境。

如果这五个问题里，大部分答案都是“能”，那这个系统大概率已经接近 Agent。

如果只能回答前一两个，那它很可能还只是自动化工具或者大模型包装层。

---

## 十二、本节小结

这一节课，我们围绕“什么是 Agent”这个最基础的问题，建立了一个完整认知框架。

你需要记住的核心结论是：

- **Agent 不是单纯会聊天的系统**
- **Agent 也不是普通自动化脚本**
- **Agent 的本质，是围绕目标持续感知、决策与行动的实体**
- 一个经典 Agent，通常应具备四个核心特征：

  - **自主性**：自己控制行为而不是逐步等待指令
  - **反应性**：根据环境变化及时调整
  - **主动性**：围绕目标主动推进任务
  - **社交能力**：与人、系统、其他 Agent 协作
- 在今天的大模型时代，这四个特征不但没有过时，反而成了判断“伪 Agent”和“真 Agent”的最好标准

---

## 十三、延伸阅读

### 经典定义与基础教材

- AIMA（Artificial Intelligence: A Modern Approach）主页：https://aima.cs.berkeley.edu/

### LLM Agent 研究与工程实践

- Lilian Weng，《LLM Powered Autonomous Agents》：https://lilianweng.github.io/posts/2023-06-23-agent/
- ReAct 论文：https://arxiv.org/abs/2210.03629

### 工具使用与现代Agent实现参考

- Anthropic Docs - Tool Use Overview：https://docs.anthropic.com/en/docs/agents-and-tools/tool-use/overview

---

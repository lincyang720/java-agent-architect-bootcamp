# 第十一章第1节：MCP 协议原理与设计目标：上下文管理、跨模型交互

大家好，我是秋天。

这节课我们正式进入"协议层"。前面十章你写的 Agent，工具、记忆、RAG 全都是"我自己家的"——换一个模型厂商、换一个框架，全得重写。这一节我要帮你把这件事从根上搞清楚：为什么业界会冒出一个叫 **MCP（Model Context Protocol，模型上下文协议）** 的东西，它的设计目标到底是什么，以及它怎么解决"上下文管理"和"跨模型交互"这两个一直没人管好的老大难问题。

学完这一节，你将掌握：

1. MCP 诞生的背景与要解决的三个核心痛点；
2. MCP 的分层架构（Host / Client / Server）与五大原语（Resources / Prompts / Tools / Sampling / Roots）；
3. 上下文（Context）在 MCP 里是怎么被"标准化传输"的，为什么这对跨模型交互至关重要；
4. 用一张清晰的图把 MCP 和你之前写的 function calling 区分开；
5. 在招聘 JD 里，MCP 能力到底对应什么岗位要求。

---

## 一、为什么需要 MCP：三个被忽视的痛点

很多同学会问："我已经有 function calling 了，Spring AI、LangChain4j 都能调工具，MCP 是不是又一个营销词？"

我先给你一个判断框架：**function calling 解决的是"模型怎么描述工具"，MCP 解决的是"工具怎么被标准化地提供给任何模型"。** 这是两个完全不同的抽象层级。

在 MCP 出现之前，业界是这么干的：

```text
          没有 MCP 的"N×M"地狱
 ┌──────────┐      ┌──────────┐      ┌──────────┐
 │ 模型 A    │      │ 模型 B    │      │ 模型 C    │
 │ (GPT)    │      │(Claude)  │      │(Qwen)    │
 └────┬─────┘      └────┬─────┘      └────┬─────┘
      │ 各自写一套        │ 各自写一套        │ 各自写一套
      ▼                 ▼                 ▼
 ┌──────────┐      ┌──────────┐      ┌──────────┐
 │ 工具1适配 │      │ 工具1适配 │      │ 工具1适配 │
 │ 工具2适配 │      │ 工具2适配 │      │ 工具2适配 │
 │ 工具3适配 │      │ 工具3适配 │      │ 工具3适配 │
 └──────────┘      └──────────┘      └──────────┘

 结果：N 个模型 × M 个工具 = N×M 套集成代码，改一个工具要改 N 处
```

痛点一：**集成爆炸（Integration Explosion）**。每个 Agent 应用都要把"文件系统、数据库、GitHub、Slack"这些能力重新对接一遍，且每个模型厂商的 function calling 格式还不一样。

痛点二：**上下文割裂（Context Fragmentation）**。记忆、知识、用户画像散落在各个框架的私有实现里，模型之间、Agent 之间无法共享同一份"上下文状态"。

痛点三：**厂商锁定（Vendor Lock-in）**。你用 LangChain4j 写的工具，Spring AI 里用不了；你给 Claude 写的描述，GPT 未必认。企业一旦选型，迁移成本极高。

<callout emoji="💡"><p>MCP 的本质是给"给模型喂上下文"这件事定义了一个 USB-C 接口：任何 Server 只要实现标准接口，任何 Host（Claude Desktop、IDE 插件、你自己的 Agent）只要实现标准客户端，就能即插即用。</p></callout>

---

## 二、MCP 的设计目标与设计哲学

MCP 由 Anthropic 于 2024 年 11 月开源，2025 年起被社区（包括 Spring AI、LangChain4j 官方）快速采纳。它的设计目标，我给你归纳成四条，这四条决定了它的技术形态：

**目标 1：标准化上下文信道（Standard Context Channel）**
把"模型需要知道的额外信息"——文件内容、数据库 schema、API 返回、用户偏好——统一成标准化的消息，不再散落在 prompt 拼接代码里。

**目标 2：关注点分离（Separation of Concerns）**
模型厂商（Host/LLM）只管推理；工具提供方（Server）只管把能力暴露成标准原语；中间的协议层（Client）负责双向通信。三者可以独立迭代。

**目标 3：安全边界内的最小权限（Least Privilege）**
Server 能访问什么资源，由用户/Host 显式授权；Client 不持有任何凭据，凭据只在 Server 进程里。

**目标 4：传输无关（Transport Agnostic）**
协议层定义 JSON-RPC 2.0 的消息结构，但不绑定传输方式。你可以走本地 stdio（进程间管道），也可以走远程 SSE/HTTP（跨网络）。这给企业部署留了很大空间。

<callout emoji="✅"><p>招聘映射：你在 JD 里看到的"熟悉 MCP 协议客户端/服务端开发""具备 Agent 标准化协议落地经验"，指的就是上面这四条目标对应的落地能力。面试时能把"为什么用 MCP"讲清楚，比背 SDK API 值钱得多。</p></callout>

---

## 三、MCP 分层架构：Host / Client / Server

先上一张总架构图，这是理解一切的基础：

```text
                    MCP 分层架构（标准形态）
 ┌──────────────────────────────────────────────────────┐
 │                     HOST 进程                          │
 │  (Claude Desktop / IDE / 你的 Agent 应用)             │
 │                                                       │
 │   ┌─────────────┐         ┌──────────────────────┐   │
 │   │  LLM 推理    │◀──上下文──│   你的业务代码         │   │
 │   └──────┬──────┘         └──────────────────────┘   │
 │          │ 想调用工具                                    │
 │          ▼                                             │
 │   ┌──────────────────────────────────────────────┐   │
 │   │              MCP Client (1:N)                │   │
 │   └───┬───────────────┬───────────────┬─────────┘   │
 └───────┼───────────────┼───────────────┼─────────────┘
         │ stdio         │ stdio         │ SSE/HTTP
         ▼               ▼               ▼
 ┌──────────────┐ ┌──────────────┐ ┌──────────────────┐
 │ MCP Server A  │ │ MCP Server B  │ │ MCP Server C      │
 │ (文件系统)     │ │ (PostgreSQL)  │ │ (远程 SaaS API)   │
 │ 持有凭据/权限  │ │ 持有 DB 连接  │ │ 持 API Token      │
 └──────────────┘ └──────────────┘ └──────────────────┘

 关键点：
 1. 一个 Host 可有多个 Client，每个 Client 连一个 Server（1:1）。
 2. 凭据永远在 Server 侧，Client 只是"传声筒"。
 3. 传输层可插拔：本地用 stdio，跨网络用 SSE/HTTP（Streamable HTTP）。
```

**Host**：运行 LLM 的容器程序，比如 Claude Desktop、Cursor、或者你自己写的 Java Agent 主程序。它负责"要不要把某个工具放进上下文""用户授权哪台 Server 连"。

**Client**：Host 内的连接器，和某一个 Server 建立一条独立会话（session）。注意是 1 个 Client 对应 1 个 Server，不是一对多。

**Server**：把某种能力标准化暴露出来的进程/服务。它可以是本地 Python 脚本（通过 stdio），也可以是远程 Spring Boot 服务（通过 HTTP）。

<callout emoji="⚠️"><p>新手最易踩的坑：把"Server 的业务逻辑"和"Host 的业务逻辑"混在一起。记住——Server 里只放"如何访问某资源"，Host 里只放"何时调用、如何编排"。权限和凭据也只在 Server。这条边界守不住，后面安全那一章的沙箱设计就白搭。</p></callout>

---

## 四、MCP 的五大原语（Primitives）

MCP 把"能给模型的东西"抽象成五类原语。这是协议的核心概念，必须记牢：

| 原语 | 方向 | 作用 | 类比 |
|------|------|------|------|
| **Resources** | Server→Client | 只读数据片段（文件、DB 行、截图） | 给模型的"参考资料" |
| **Prompts** | Server→Client | 预定义的提示词模板 | 可复用的"工作流起点" |
| **Tools** | Client→Server | 可被模型调用的函数（有副作用） | 传统 function calling |
| **Sampling** | Server→Client | Server 反向请求 LLM 补全（让 Server 也能用模型） | 嵌套推理 |
| **Roots** | Client→Server | 客户端告诉 Server"你可访问的目录边界" | 文件系统白名单 |

### 4.1 Resources：标准化的"上下文载荷"

这是 MCP 解决"上下文管理"的关键。以前你给模型塞上下文，是自己拼字符串；现在 Server 把数据封装成 Resource，带 URI 和 MIME 类型，Host 按需读取再注入。

```json
{
  "uri": "file:///Users/qiutian/contracts/2026-Q1.md",
  "name": "2026 Q1 合同",
  "mimeType": "text/markdown",
  "description": "第一季度销售合同汇总",
  "annotations": {
    "audience": ["user", "assistant"],
    "priority": 0.8
  }
}
```

注意 `annotations` 里的 `audience` 和 `priority`：这就是 MCP 在协议层面做的"上下文优先级"标记，比你在代码里硬拼 prompt 科学多了——它把"哪些上下文该优先给模型"这件事标准化了。

### 4.2 Tools：你最熟悉的老朋友

Tools 就是 function calling 的标准化版本。每个 Tool 有 name、description、inputSchema（JSON Schema）。模型决定调用，Client 代为执行，Server 返回结果。

### 4.3 Sampling：被低估的"反向通道"

这是 MCP 区别于普通 function calling 的地方。Server 在处理某个请求时，可以反过来要求 Host 调一次 LLM（Sampling），拿到生成结果再继续。这让 Server 本身也能"思考"，而不只是被动执行。

<callout emoji="💡"><p>Sampling 非常适合做"工具内部的结构化抽取"。比如一个 PDF 解析 Server，先用 Sampling 让模型把表格抽成 JSON，再返回。这样复杂的"LLM 辅助解析"逻辑被封装进 Server，Host 侧代码反而简单了。</p></callout>

---

## 五、上下文管理：MCP 到底"管理"了什么

很多同学把 MCP 当"又一个工具框架"，这是误解。MCP 真正管理的是**上下文的生命周期**：

```text
                 MCP 上下文管理模型
 ┌─────────────────────────────────────────────────┐
 │ 1. 发现 (Discovery)                              │
 │    Client 连上 Server → 列出 Resources/Tools     │
 │    → Host 决定哪些进上下文                        │
 │                                                  │
 │ 2. 注入 (Injection)                              │
 │    Resources 按 audience/priority 注入 system/user│
 │                                                  │
 │ 3. 选择 (Selection)                              │
 │    模型运行中，按需要 read_resource / call_tool   │
 │                                                  │
 │ 4. 回流 (Feedback)                               │
 │    Tool 结果回灌上下文，触发下一轮推理            │
 │                                                  │
 │ 5. 隔离 (Isolation)                              │
 │    多会话上下文互不污染（见第十四章多租户）       │
 └─────────────────────────────────────────────────┘
```

关键点：**上下文不是一次性塞满，而是"按需拉取 + 结果回流"的闭环**。这正好对应第十五章你会学到的 Context Engineering 四大策略里的 *Select* 和 *Compress*——MCP 在协议层为它们提供了基础设施。

和企业场景结合一下：某政企做"合同审查 Agent"，合同可能存在不同部门的 NAS、不同的数据库。用 MCP，每个部门实现一个 Server（带各自权限），审查 Agent（Host）统一通过标准协议拉取上下文，无需把全公司合同都加载进一个 prompt。这就是"上下文隔离 + 按需选择"的真实价值。

---

## 六、跨模型交互：MCP 怎么消厂商锁定

这是这一节标题里"跨模型交互"的落点。看这张对比：

```text
            传统方式：工具绑定模型
 ┌──────────┐   专属适配    ┌──────────┐
 │ GPT 工具  │◀──────────▶│ 我的业务  │
 └──────────┘              └──────────┘
 ┌──────────┐   专属适配    ┌──────────┐
 │ Claude   │◀──────────▶│ 我的业务  │
 └──────────┘              └──────────┘
 换模型 = 重写工具适配

            MCP 方式：工具绑定协议
 ┌──────────┐              ┌──────────────┐
 │ 任意 LLM  │──标准协议──▶│ MCP Server   │
 └──────────┘              │ (我的业务)   │
                           └──────────────┘
 换模型 = 零改动，Server 不变
```

我给你一个**实战判断**：如果你的业务需要同时支持多家模型（比如出于成本/合规要 GPT 和 Qwen 双跑），或者你做的工具要给别的团队/客户复用，MCP 的收益是立竿见影的。如果只是一个内部小工具、只用一家模型，那直接用框架自带的 function calling 更省事——**不要为了用 MCP 而用 MCP**。

<callout emoji="⚠️"><p>秋天给你一句大实话：技术选型最怕"跟风"。MCP 不是银弹，它是"当你需要工具被多方、多模型复用时"的解法。课程里我们把所有框架（Spring AI / LangChain4j / ADK）都接上 MCP，是为了让你具备"标准化互操作"的能力，不是让你每个项目都上 MCP。</p></callout>

---

## 七、MCP 与 function calling 的关系：不是替代，是升华

我画一张映射表，帮你彻底分清：

| 维度 | Function Calling | MCP |
|------|------------------|-----|
| 定义层级 | 模型厂商私有规范 | 开放标准协议 |
| 工具描述 | 厂商自定义 JSON | 统一的 Tool/Resource 原语 |
| 传输 | 无（由框架处理） | JSON-RPC over stdio/SSE/HTTP |
| 多工具复用 | 每个模型各写一套 | 一次实现，任意模型可用 |
| 授权/隔离 | 框架自己管 | 协议内置 Roots + 用户授权 |
| 典型实现 | OpenAI/Claude tools | Anthropic MCP + 社区 SDK |

结论：**function calling 是 MCP 的"子集"**。MCP 的 Tool 原语底层就是 function calling，只是把它包进了标准协议，并补齐了 Resources、Roots、Sampling 这些 function calling 没有的东西。

---

## 八、一个最小可运行示例：用 Python 快速起一个 MCP Server

为了让你今天就能跑通，我用官方 `mcp` Python SDK 给你一个最小 Server（文件系统读取）。注意这是"原理验证"，Java 版完整实现在 lesson02。

先装依赖：

```bash
pip install "mcp[cli]"
```

`filesystem_server.py` —— 暴露一个"读文件"的 Tool 和一个"列目录"的 Resource：

```python
"""最小 MCP Server：文件系统读取（stdio 传输）"""
import asyncio
from pathlib import Path
from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import Tool, TextContent, Resource

app = Server("filesystem-demo")

# 允许的目录白名单（对应 MCP Roots 思想）
ALLOWED_ROOT = Path("/Users/qiutian/workspace")

@app.list_tools()
async def list_tools() -> list[Tool]:
    return [Tool(
        name="read_file",
        description="读取白名单目录内的文本文件",
        inputSchema={
            "type": "object",
            "properties": {"path": {"type": "string"}},
            "required": ["path"],
        },
    )]

@app.call_tool()
async def call_tool(name: str, arguments: dict) -> list[TextContent]:
    if name == "read_file":
        target = ALLOWED_ROOT / arguments["path"]
        # 路径穿越防护：解析后必须仍在白名单内
        if not str(target.resolve()).startswith(str(ALLOWED_ROOT.resolve())):
            return [TextContent(type="text", text="ERROR: 越权路径被拒绝")]
        text = target.read_text(encoding="utf-8")
        return [TextContent(type="text", text=text[:4000])]
    return [TextContent(type="text", text="unknown tool")]

async def main():
    async with stdio_server() as (read, write):
        await app.run(read, write, app.create_initialization_options())

if __name__ == "__main__":
    asyncio.run(main())
```

运行方式（stdio 模式，通常由 Host 拉起，这里单独验证）：

```bash
python filesystem_server.py
```

<callout emoji="💡"><p>这段代码虽然短，但已经体现了 MCP 的两个设计目标：① 工具逻辑（read_file）与 Host 解耦；② 权限边界（ALLOWED_ROOT + 路径穿越防护）内置于 Server。Lesson02 你会看到 Java 版如何做到同样的事，并且更工程化。</p></callout>

---

## 九、协议消息长什么样：JSON-RPC 速览

MCP 底层是 JSON-RPC 2.0。给你看一个 `initialize` 握手的真实报文，让你知道"标准"具体长什么样：

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2024-11-05",
    "capabilities": {
      "roots": { "listChanged": true },
      "sampling": {}
    },
    "clientInfo": { "name": "qiutian-agent", "version": "1.0.0" }
  }
}
```

Server 回：

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "protocolVersion": "2024-11-05",
    "capabilities": { "tools": { "listChanged": false } },
    "serverInfo": { "name": "filesystem-demo", "version": "0.1.0" }
  }
}
```

你不需要手写这些报文（SDK 都封装了），但理解它能帮你在调不通时快速定位：90% 的"连不上"都是 `protocolVersion` 不匹配或 capabilities 没声明。

---

## 十、这一能力在招聘里怎么写

我直接给你一段真实风格的 JD 摘录，你看完就知道这一节在为什么做准备：

```text
【岗位】AI Agent 后端工程师（Java）
【职责】
- 基于 MCP 协议构建标准化工具服务，支撑多模型、多 Agent 复用；
- 设计跨框架的上下文管理层，解决上下文割裂与权限隔离问题；
- 推动内部工具从"框架私有"向"协议标准"演进。
【要求】
- 熟悉 MCP / A2A 等 Agent 标准化协议，有 Client 或 Server 端开发经验；
- 理解 JSON-RPC、stdio / SSE 传输机制；
- 有上下文工程（Context Engineering）实践经验者优先。
```

<callout emoji="✅"><p>看到没？JD 要的不是"会用某个 SDK"，而是"理解协议 + 能落地标准化工具 + 能解决上下文隔离"。这一节练的就是这三项。</p></callout>

---

## 十一、MCP 与企业既有集成层的关系

很多做企业架构的同学会问："我已经有 ESB（企业服务总线）、API Gateway、iPaaS 了，MCP 是不是重复造轮子？"

我的判断框架：**ESB/API Gateway 解决的是"系统之间的连接"，MCP 解决的是"模型与工具之间的语义连接"。** 两者不冲突，是不同层。

```text
           企业集成层全景
 ┌──────────────────────────────────────────────────┐
 │ 应用/模型层                                       │
 │   Agent(Host) ──MCP──▶ MCP Server(工具语义层)     │
 │                         │                         │
 │ 集成层（既有）            │ 适配                     │
 │   MCP Server 内部 ──────▶ ESB / API Gateway       │
 │                         │                         │
 │ 系统层                   ▼                         │
 │   订单系统 / CRM / 数据仓库 / 遗留 SOAP           │
 └──────────────────────────────────────────────────┘

 关键：MCP Server 是"模型视角的适配层"，它底下照样调 ESB/网关，
       而不是推翻 ESB 重写。MCP 不替代集成层，它站在集成层之上，
       把"能力"翻译成模型能理解的语义。
```

<callout emoji="💡"><p>落地建议：别让团队"因为上了 MCP 就拆 ESB"。正确做法是把已有的 ESB/网关能力，通过一层薄薄的 MCP Server 包出来给 Agent 用。既复用了存量投资，又拿到了协议标准化红利。</p></callout>

---

## 十二、性能与规模：MCP 在什么量级会出问题

协议本身不限制规模，但工程上有几个经验阈值，企业规划时心里要有数：

| 维度 | 安全区间 | 风险区间 | 应对 |
|------|----------|----------|------|
| 单 Server 工具数 | < 30 | > 100 | 拆分 Server / 用 Resources 懒加载 |
| 单 Host 连 Server 数 | < 20 | > 50 | 按场景动态连接 |
| 单次 Tool 返回 token | < 4k | > 16k | 压缩/分页/引用溯源 |
| 并发 Client 会话 | 看部署 | 无限制 | 连接池 + 限流 |

```text
   工具数过多的反模式
 ┌──────────────────────────────────────────────────┐
 │ Host 一次性注入 150 个工具描述到 context           │
 │   → 模型选不准、context 腐烂、token 暴涨          │
 │ 正确：按 Roots/用户意图只加载相关子集（<30）       │
 │   → 对应第十五章 Context Engineering 的 Select 策略 │
 └──────────────────────────────────────────────────┘
```

---

## 十三、实战：用 MCP Inspector 连官方 filesystem server

前面给了 Python 最小 Server，这里给你一个"零代码验证协议"的路径——直接拉官方 Server 用 Inspector 看报文：

```bash
# 1. 安装 Node（略），启动 Inspector
npx @modelcontextprotocol/inspector

# 2. 在 UI 里填官方 filesystem server 的启动命令
#    command: npx
#    args: -y @modelcontextprotocol/server-filesystem /path/to/dir

# 3. 连接后可看到 Resources/Tools 列表，手动调用并查看 JSON-RPC 报文
```

```text
   Inspector 看到的握手 + 列工具 报文（节选）
 ┌──────────────────────────────────────────────────┐
 │ → initialize                                      │
 │   {protocolVersion:"2024-11-05", capabilities...} │
 │ ← result {serverInfo, capabilities:{tools:true}}  │
 │ → tools/list                                      │
 │ ← result {tools:[{name:"read_file",...}]}         │
 │ → tools/call {"name":"read_file",args:{path:..}}  │
 │ ← result {content:[{type:"text",text:...}]}       │
 └──────────────────────────────────────────────────┘
```

<callout emoji="⚠️"><p>这一节的所有"原理"在你亲眼看到 JSON-RPC 报文后，会从"概念"变成"确定"。强烈建议你跑一遍 Inspector，把 initialize / tools/list / tools/call 三步走通，后面写 Java Client 时心里有底。</p></callout>

---

## 十四、常见排错 FAQ

1. **握手失败 `protocolVersion` 不匹配**：Client 与 Server 支持的协议版本无交集。对齐到双方都支持的版本（详见 lesson03 第七节）。
2. **stdio Server 启动即退出**：命令路径/依赖错了。先用 `python xxx.py` 单独跑确认能起来，再交给 Host。
3. **工具列出来但调用返回空**：Server 的 `call_tool` 没正确返回 `CallToolResult`，检查返回结构。
4. **SSE 模式连不上**：URL/鉴权/CORS。远程部署务必加认证（lesson02 第四节强调过）。
5. **模型不调用工具**：工具 `description` 太含糊或参数 schema 不对，模型判断不出何时用。

---

## 十五、面试高频追问与作答骨架

```text
【追问1】MCP 和 function calling 到底啥区别？
答：function calling 是厂商私有规范，MCP 是开放协议；MCP 在 Tool 之外
    还定义了 Resources/Sampling/Roots，并标准化了传输与授权。
    一句话：MCP = 标准化 + 升华版 function calling。

【追问2】你们为什么用 MCP 而不是各自写适配？
答：我们有 N 个模型 × M 个工具，MCP 把集成从 N×M 降到 N+M；
    且工具要跨团队复用，协议中立让 Python/Java 工具都能被任意 Host 用。

【追问3】MCP 的安全怎么保证？
答：凭据只在 Server 侧（Host/Client 不持凭据）；Roots 限定可访问边界；
    远程传输必须加鉴权；工具调用过安全网关（见第十二章）。
```

<callout emoji="✅"><p>这三问是 MCP 岗的高频题。能按"区别 / 动机 / 安全"三层答，基本就过关了。注意每句都落到"我们为什么这么选"，体现架构判断。</p></callout>

---

## 小结

这一节我们讲了 MCP 的来龙去脉：

- **痛点**：集成爆炸、上下文割裂、厂商锁定，是 function calling 时代没人管好的三件事；
- **架构**：Host / Client / Server 三层，凭据只在 Server，传输可插拔；
- **原语**：Resources（上下文载荷）、Tools（标准函数）、Prompts（模板）、Sampling（反向推理）、Roots（访问白名单）；
- **上下文管理**：MCP 管的是"发现→注入→选择→回流→隔离"的闭环，而非一次性塞 prompt；
- **跨模型**：MCP 把"工具绑定模型"变成"工具绑定协议"，消除厂商锁定；
- **与 function calling 关系**：MCP 是其标准化升华，Tool 是原语之一。

下一节我们直接上手，用 Java 17 + 官方 SDK 把 Client 和 Server 都写出来，并跑通一条完整链路。

---

## 延伸阅读 / 引用文档

1. MCP 官方规范（Protocol Specification）：https://modelcontextprotocol.io/specification/2024-11-05
2. MCP 官方介绍与架构图：https://modelcontextprotocol.io/introduction
3. Anthropic 发布 MCP 的博客：https://www.anthropic.com/news/model-context-protocol
4. MCP Python SDK（GitHub）：https://github.com/modelcontextprotocol/python-sdk
5. MCP Java SDK（modelcontextprotocol/java-sdk）：https://github.com/modelcontextprotocol/java-sdk
6. Spring AI MCP 官方文档：https://docs.spring.io/spring-ai/reference/1.0/api/mcp/mcp-overview.html

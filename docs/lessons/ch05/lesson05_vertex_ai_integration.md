# 第五章第5节：ADK 与 Vertex AI 的集成——企业级部署与扩展

大家好，我是秋天。

前四节课我们把 ADK 的"内功"——概念、创建、编排、工具——全讲完了。这节课讲"出关"：**怎么把写好的 Agent 从本地 demo 变成生产系统**。ADK 最大的卖点之一，就是和 Google Cloud 的 Vertex AI 深度打通，能一键获得托管运行时、弹性扩缩、可观测、权限治理。

学完这节课你能：

1. 理解 ADK Agent 在 Vertex AI 上的两种部署形态；
2. 用 ADK CLI / Maven 插件把 Agent 打包部署到 Vertex AI Agent Engine；
3. 掌握企业级扩展点：身份认证、私有网络、可观测、成本治理；
4. 看清 ADK+Vertex 的"云锁定"代价，学会做技术决策。

很多同学写得出 Agent，但一上生产就崩。这节课专治"demo 到生产的最后一公里"。

---

## 一、为什么是 Vertex AI，而不是自己搭

先把账算清楚。一个生产级 Agent 系统，自己从零搭要什么？

```text
自建生产 Agent 平台，你需要自己搞定：
  ├─ 运行时托管（容器编排、健康检查、滚动发布）
  ├─ 弹性扩缩（流量高峰自动扩容，平时缩容省钱）
  ├─ 身份认证（谁能在调这个 Agent？OAuth/IAM）
  ├─ 密钥管理（API Key 不能明文，走 Secret Manager）
  ├─ 日志/追踪（OpenTelemetry + 后端）
  ├─ 模型网关（限流、配额、多模型路由）
  └─ 合规（数据驻留区域、审计日志）
```

这些每一项都是坑。Vertex AI Agent Engine 把这整条链路"托管"了，ADK 是它的"一等公民客户端"。所以 ADK + Vertex 的组合，本质是**用云厂商的运维能力，换你自己的开发速度**。

<callout emoji="💡"><p>决策框架：小团队 / 快速验证 / 已经在 GCP → 直接用 Vertex Agent Engine，别自己造轮子。强合规 / 数据不出境 / 混合云 → 评估 Vertex 区域能力，必要时走自托管（Cloud Run + 自己的编排）。没有银弹。</p></callout>

---

## 二、ADK 在 GCP 上的三种运行形态

ADK Agent 上云不是只有"一键部署"一条路，有三种形态，按需选：

```text
┌──────────────────────────────────────────────────────────────┐
│  形态 A：本地 / 自有服务器                                     │
│    mvn spring-boot:run 或 java -jar，自己管进程               │
│    适合：开发、内网、非 GCP 环境                               │
├──────────────────────────────────────────────────────────────┤
│  形态 B：Cloud Run 部署（容器化）                              │
│    ADK Agent 打成镜像跑在 Cloud Run，自动扩缩、按量付费        │
│    适合：需要自定义、但想省运维的中型系统                      │
├──────────────────────────────────────────────────────────────┤
│  形态 C：Vertex AI Agent Engine（全托管）                     │
│    ADK 原生集成，获得会话托管、评测、版本管理的全套能力        │
│    适合：追求"上线最快 + 企业级治理"的团队                    │
└──────────────────────────────────────────────────────────────┘
```

这节课重点讲 C（最契合 ADK 设计意图），B 作为备选提一下。

---

## 三、部署到 Vertex AI Agent Engine

### 3.1 项目结构约定

ADK 对 Agent 项目的结构有约定：`src/main/java` 下暴露一个 `Agent` 实例，通常在 `agent.yaml` 里声明入口。

```yaml
# agent.yaml —— ADK Agent 的部署描述文件
apiVersion: v1
kind: GoogleCloudVertexRagEngine  # 或 Agent 部署声明
metadata:
  name: camp-customer-agent
  description: "电商客服 Agent，演示 ADK + Vertex 部署"
spec:
  agentClass: "com.camp.adk.CustomerAgent"   # 全限定类名，指向你的 Agent 构建方法
  model: gemini-2.0-flash
  region: us-central1
  # 环境变量注入（密钥不写死，走 Secret Manager）
  env:
    - name: GOOGLE_API_KEY
      valueFrom:
        secretManager:
          name: projects/your-project/secrets/adk-api-key
```

<callout emoji="⚠️"><p>密钥铁律：永远不要把 API Key 写进 `agent.yaml` 或代码。GCP 上用 Secret Manager，本地用环境变量。我们课程规范里反复强调"绝不硬编码密钥"，生产事故里"密钥入库被拖库"是头号低级错误。</p></callout>

### 3.2 入口类：暴露一个 Agent

Vertex 部署时，ADK 需要一个稳定的入口拿到 Agent 实例。约定是提供 `rootAgent()` 静态方法或字段：

```java
package com.camp.adk;

import com.google.adk.agents.LlmAgent;

/**
 * 部署入口：Vertex Agent Engine 会加载这个类，读取 rootAgent。
 * 注意：构建逻辑要和本地运行共用，避免"本地能跑线上崩"。
 */
public class CustomerAgent {

    // Vertex 约定：暴露一个名为 rootAgent 的 LlmAgent
    public static final LlmAgent rootAgent = buildAgent();

    private static LlmAgent buildAgent() {
        return LlmAgent.builder()
                .name("customer_root")
                .instruction("""
                        你是电商客服总控。
                        涉及订单转 order_agent，涉及政策转 policy_agent。
                        所有对外承诺必须基于工具真实返回，禁止编造。
                        """)
                .model("gemini-2.0-flash")
                .subAgents(java.util.List.of(
                        orderAgent(), policyAgent()))
                .build();
    }

    private static LlmAgent orderAgent() {
        return LlmAgent.builder().name("order_agent")
                .instruction("处理订单查询，调用查单工具。").model("gemini-2.0-flash").build();
    }
    private static LlmAgent policyAgent() {
        return LlmAgent.builder().name("policy_agent")
                .instruction("回答退换货政策，基于知识库。").model("gemini-2.0-flash").build();
    }
}
```

### 3.3 用 ADK CLI 部署

ADK 提供命令行工具把 Agent 推到 Vertex：

```bash
# 1) 安装 ADK CLI（示意，具体以官方为准）
pip install google-adk        # ADK CLI 以 Python 版提供，跨语言通用

# 2) 登录 GCP 并设项目
gcloud auth login
gcloud config set project your-project-id

# 3) 部署 Agent 到 Vertex AI Agent Engine
adk deploy vertex_ai \
  --project your-project-id \
  --region us-central1 \
  --agent src/main/java/com/camp/adk/CustomerAgent.java \
  --agent_engine_id your-engine-id
```

<callout emoji="💡"><p>注意一个工程细节：ADK CLI 是 Python 版跨语言通用的，但你写的 Agent 是 Java。部署时 CLI 会把你的 Java 工程打包成可在 Vertex 运行的产物。所以"用 Python CLI 部署 Java Agent"是正常的，别被语言迷惑。具体命令参数以官方文档最新版为准。</p></callout>

---

## 四、Cloud Run 备选：容器化部署

如果你不想绑死 Agent Engine，可以把 ADK Agent 打成容器跑 Cloud Run，获得弹性但更自由。

### 4.1 Dockerfile

```dockerfile
# 基于官方 Eclipse Temurin 17 镜像
FROM eclipse-temurin:17-jdk

WORKDIR /app

# 先拷依赖描述，利用层缓存
COPY pom.xml .
RUN ./mvnw -B -q dependency:go-offline || mvn -B -q dependency:go-offline

# 再拷源码构建
COPY src ./src
RUN mvn -B -q package -DskipTests

# 暴露端口（ADK/Spring 默认 8080）
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "target/ch05-google-adk-1.0.0.jar"]
```

### 4.2 部署命令

```bash
# 构建并推镜像
gcloud builds submit --tag gcr.io/your-project/camp-agent

# 部署到 Cloud Run（自动扩缩、最小实例可设为 0 省钱）
gcloud run deploy camp-agent \
  --image gcr.io/your-project/camp-agent \
  --region us-central1 \
  --allow-unauthenticated=false \   # 不允许匿名，必须鉴权
  --min-instances 0 \
  --max-instances 10
```

```text
Cloud Run 扩缩模型：
  流量 0     → 缩到 0 实例（省钱，冷启动 ~秒级）
  流量高峰   → 自动扩到 max-instances（扛压）
  流量回落   → 缩容（按量计费）
```

<callout emoji="✅"><p>成本治理考点：Cloud Run 设 `min-instances 0` 是省钱利器，但 Agent 冷启动有延迟。客服类实时场景可设 `min-instances 1` 保活；离线批处理类设 0 即可。这对应 JD 里"成本治理 / 弹性伸缩"要求。</p></callout>

---

## 五、企业级扩展点：四个绕不开的话题

部署只是开始，生产系统要补四块：

### 5.1 身份认证与权限（IAM）

谁能在调你的 Agent？GCP 用 IAM 控制。Cloud Run 上用 `--allow-unauthenticated=false` 要求调用方带身份令牌：

```java
// 在 Agent 前置一个鉴权拦截（伪代码示意）
// 真实用 Cloud Run + API Gateway / Identity-Aware Proxy 实现
public class AuthFilter {
    public boolean allow(String bearerToken) {
        // 1) 校验 JWT 签名（用 Google 公钥）
        // 2) 查 IAM 策略：该用户是否有权调此 Agent
        // 3) 提取 user_id 写入 session state，供 Agent 做数据隔离
        return verifyJwt(bearerToken) && checkIamPolicy(bearerToken);
    }
}
```

<callout emoji="⚠️"><p>多租户隔离是硬要求：Agent 看到的必须是"当前用户的数据"，绝不能串号。做法是在鉴权后把 `user_id`/`tenant_id` 写进 session state，所有工具查询都带这个过滤条件。这是第十二章"多租户隔离"的核心。</p></callout>

### 5.2 可观测性：日志、追踪、指标

ADK 事件流天然适合接 OpenTelemetry。把每次 Invocation、每次工具调用都打 span：

```text
一次客服会话的 trace：
  [invocation] root_agent
     ├─ [span] 路由判断        12ms
     ├─ [span] order_agent
     │     ├─ [span] 工具:queryOrder  45ms
     │     └─ [span] 模型生成          820ms
     └─ [span] 最终回复组装     5ms
```

接法（概念示意，依赖 ADK 的 tracing 钩子）：

```yaml
# otel 配置（application.yml 片段）
otel:
  exporter: otlp
  endpoint: ${OTEL_COLLECTOR_URL}   # 你的 OTLP 收集器
  service: camp-agent
  traces: enabled
  metrics: enabled
```

```text
数据去向：
  ADK 事件 ──▶ OpenTelemetry ──▶ Collector ──▶ {Jaeger(追踪) / Prometheus(指标) / ELK(日志)}
```

<callout emoji="💡"><p>生产三件套：日志（出事了能查）、追踪（慢了能定位到哪一步）、指标（趋势能监控）。Agent 系统没有可观测性 = 盲人开车。第十章会系统讲 OpenTelemetry / ELK / Prometheus+Grafana。</p></callout>

### 5.3 会话持久化：从内存到数据库

上节课我们用的 `InMemorySessionService` 一重启就没了，生产必须持久化。ADK 支持自定义会话服务，或接 Vertex 托管的会话存储：

```java
package com.camp.adk;

import com.google.adk.sessions.SessionService;

/**
 * 生产会话服务示意：把 Session 存进数据库（PostgreSQL / Spanner）。
 * 关键：Session 序列化 + 按 user_id/tenant_id 隔离查询。
 */
public class DbSessionService implements SessionService {
    // 真实实现：用 JPA / JDBC 把 Session 的 state + events 落库
    // 重点保证：多租户隔离、过期清理（TTL）、并发安全
    // 这里只给签名骨架，强调"生产必须持久化 + 隔离"
    @Override
    public /* Single<Session> */ Object createSession(String app, String user,
                                                      Object state, String id) {
        // INSERT INTO agent_session(...) VALUES (...)
        throw new UnsupportedOperationException("见课程工程完整实现");
    }
}
```

<callout emoji="✅"><p>面试考点："你的 Agent 会话怎么存？"标准答：开发用 InMemory，生产用持久化 SessionService（数据库），重点保证多租户隔离 + TTL 清理 + 并发安全。再补一句"长会话要做上下文压缩防上下文腐烂"（第十五章）。这套回答体现工程深度。</p></callout>

### 5.4 成本治理：模型分级与缓存

生产 Agent 的账单优化，三个杠杆：

```text
杠杆1：模型分级
  粗活（路由/质检/摘要）→ gemini-2.0-flash（便宜快）
  细活（生成/推理/代码） → gemini-2.5-pro（贵但强）

杠杆2：提示词缓存
  系统指令 + 常驻知识 → 缓存命中，省重复 token（第十五章详讲）

杠杆3：提前退出 + 上限
  LoopAgent maxIterations + 达标即停，防烧钱
```

```yaml
# 成本相关配置（外置）
cost:
  default-model: gemini-2.0-flash
  premium-model: gemini-2.5-pro
  enable-prompt-cache: true
  max-iteration-cap: 5
  budget-alert-per-day-usd: 50
```

<callout emoji="💡"><p>架构师思维：成本不是上线后才想的事，是设计期就要埋点的。把"模型分级开关""缓存开关""预算告警"做成配置，运营能实时调。对应 JD 里"具备 Agent 成本治理经验"。</p></callout>

---

## 六、云锁定的代价与决策

我必须泼一盆冷水：**ADK + Vertex 是强绑定的**。好处是"快"，代价是"走不掉"。

```text
绑定程度评估：
  ├─ 模型层：Gemini 为主（也能接其它，但 Grounding/Code Interpreter 是 Google 独占）
  ├─ 工具层：Search / Code Interpreter 仅 Google 提供
  ├─ 部署层：Agent Engine 是 Vertex 独占
  └─ 结论：用了内置 Search/Code Interpreter + Agent Engine → 深度锁定 GCP
```

决策建议：

- **已在 GCP 生态 / 数据允许出境** → ADK + Vertex 是最优解，别犹豫。
- **混合云 / 强数据合规（金融、政务）** → 谨慎。可只用 ADK 框架层（Agent/编排/工具），部署走自有 K8s，内置 Google 工具换成自建（Search→向量库RAG，Code Interpreter→自有沙箱）。
- **完全私有化** → ADK 的云绑定会让你痛，考虑 LangChain4j / Spring AI + 私有模型更顺。

<callout emoji="⚠️"><p>技术选型没有对错，只有匹配。我见过团队为了"用最新框架"硬上 ADK+Vertex，结果数据合规过不了被审计打回。选型时先问"数据能不能出这个域"，再问"框架好不好用"。顺序不能反。</p></callout>

---

## 七、完整上线 checklist

把这一章串起来，给你一份 Agent 上生产的清单：

```text
✅ Agent 逻辑复用（本地/线上同一份代码）
✅ 密钥走 Secret Manager，零硬编码
✅ 会话持久化 + 多租户隔离
✅ 鉴权拦截（IAM / JWT）
✅ 可观测：日志 + 追踪 + 指标接好
✅ 成本：模型分级 + 缓存 + 预算告警
✅ 工具安全：输入校验 + 沙箱 + HITL 节点
✅ 弹性：Cloud Run 扩缩 / Agent Engine 托管
✅ 降级：模型不可用时的 fallback 策略
```

<callout emoji="✅"><p>这清单基本覆盖了 JD 里"企业级工程 / 高可用 / 安全治理"的全部要求。面试被问"Agent 怎么上线"，把这 9 条念一遍，比讲十个小时 API 都管用。</p></callout>

---

## 八、招聘能力映射

这节课是企业级能力的集中体现，对应 JD 一大串：

- "**熟悉云原生部署（GCP/AWS/阿里云）**" → Vertex / Cloud Run 部署。
- "**Agent 可观测性 / OpenTelemetry**" → 第五节。
- "**成本治理 / 弹性伸缩**" → 模型分级 + Cloud Run 扩缩。
- "**多租户隔离 / 权限治理**" → IAM + session state 隔离。
- "**企业级工程化（高可用、降级、限流）**" → 上线 checklist。

<callout emoji="💡"><p>面试金句："Agent 上生产的难点不在写逻辑，在四件事——身份权限、会话持久化隔离、可观测、成本治理。框架帮你写 Agent，但平台工程得自己补。" 这段话能把你从"会用框架"拉到"能交付系统"的层级。</p></callout>

---

## 九、CI/CD 与 Agent 版本管理

Agent 也要进 CI/CD。每次改指令/工具，都要自动跑评测再发布（第十六章详讲评测，这里讲流水线）。

```yaml
# .github/workflows/agent-ci.yml 片段（GitLab/CI 同理）
name: agent-ci
on: [push]
jobs:
  build-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '17', distribution: 'temurin' }
      - run: mvn -B test                 # 跑 Agent 单测
      - run: mvn -B package -DskipTests  # 打镜像用 jar
  eval:
    needs: build-test
    runs-on: ubuntu-latest
    steps:
      - run: python eval/run_eval.py     # 跑离线评测（第十六章）
  deploy:
    needs: eval
    if: github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    steps:
      - run: gcloud run deploy ...        # 评测通过才部署
```

<callout emoji="💡"><p>关键实践：把"评测"卡在"部署"前面。改一句指令如果让准确率掉了 5%，CI 直接红，不让上线。这是 Agent 质量闭环（第十六章）的落地形态——没有评测卡点的发布，等于盲飞。</p></callout>

## 十、灰度发布与回滚

Agent 上线不能"全量一把梭"，要灰度 + 可回滚。

```text
Agent 灰度发布流程：
  1) 新版本部署为独立端点（v2）
  2) 10% 流量切到 v2（网关/服务网格控制）
  3) 监控：准确率 / 成本 / 用户满意度
  4) 达标 → 逐步放量到 100%；不达标 → 切回 v1
```

```java
package com.camp.adk;

/** 简单的权重路由，模拟灰度（生产用网关实现） */
public class GrayRouter {
    private final double v2Ratio;  // 0.1 = 10% 流量走 v2
    private final java.util.Random rnd = new java.util.Random();

    public GrayRouter(double v2Ratio) { this.v2Ratio = v2Ratio; }

    public String routeEndpoint() {
        return rnd.nextDouble() < v2Ratio ? "agent-v2" : "agent-v1";
    }
}
```

<callout emoji="✅"><p>回滚铁律：任何发布都要能"一键回滚"。做法是为每个 Agent 版本打不可变标签（镜像 tag / 指令版本号），回滚就是切回旧标签。绝不"热改线上"——那是事故温床。</p></callout>

## 十一、成本监控看板

成本不监控，月底账单会吓死人。给你一个最小成本监控设计。

```text
成本监控看板指标：
  ├─ 每日 Token 消耗（输入/输出分开）
  ├─ 各模型调用占比（Flash vs Pro）
  ├─ 单次会话平均成本
  ├─ Loop 平均轮数（异常高 = 死循环风险）
  └─ 缓存命中率（低 = 提示词缓存没生效）
```

```java
package com.camp.adk;

import java.util.concurrent.atomic.AtomicLong;

/** 最小成本计数器：每次调用累加 token，定期上报 */
public class CostMeter {
    private final AtomicLong inputTokens = new AtomicLong(0);
    private final AtomicLong outputTokens = new AtomicLong(0);

    public void record(long in, long out) {
        inputTokens.addAndGet(in);
        outputTokens.addAndGet(out);
    }
    public double estimateUsd(double inPrice, double outPrice) {
        return inputTokens.get() / 1_000_000.0 * inPrice
             + outputTokens.get() / 1_000_000.0 * outPrice;
    }
}
```

<callout emoji="⚠️"><p>成本意识是架构师底线。我见过团队 Agent 跑一个月，账单比人力还贵——就是因为没监控、没上限、没缓存。Lesson02 的提示词缓存 + 这里的监控，是成本治理的两条腿。</p></callout>

## 十二、避坑清单与收官

```text
生产部署避坑：
✅ 密钥走 Secret Manager，零硬编码
✅ 会话持久化 + 多租户隔离
✅ 鉴权拦截（IAM / JWT）+ user_id 写死进 state
✅ 可观测：日志 + 追踪 + 指标
✅ 成本：模型分级 + 缓存 + 预算告警
✅ CI 卡评测 + 灰度 + 可回滚
❌ 全量发布、热改线上
❌ 忽略数据合规（出境/驻留）
❌ 无限 Loop 烧钱无监控
```

<callout emoji="💡"><p>第五章收官：从概念到部署，ADK 的全链路你走通了。记住——框架帮你写 Agent，但"能不能上线"靠的是这一章的工程纪律：鉴权、隔离、可观测、成本、灰度、回滚。这九件事，决定你是"调包侠"还是"架构师"。</p></callout>

## 十三、灾备与多区域部署

生产系统要抗单点故障。Agent 上云也要考虑灾备。

```text
灾备设计要点：
  ├─ 多区域部署：主区域故障，流量切到备区域（GCP 多 region）
  ├─ 模型冗余：主模型（Gemini Pro）不可用时，降级到备选（Flash/其它）
  ├─ 会话存储冗余：数据库主从 + 跨区备份
  └─ 限流降级：流量洪峰时拒绝非核心请求，保主流程
```

```yaml
# 模型降级配置（外置）
resilience:
  primary-model: gemini-2.5-pro
  fallback-model: gemini-2.0-flash
  session-store:
    primary: spanner-us
    backup: spanner-eu
```

<callout emoji="⚠️"><p>降级不等于"低质量"，而是"保可用"。主模型挂了，用次模型先顶着，比"整个 Agent 挂掉"强。生产系统必须预设"当 X 不可用时怎么办"——这是高可用的基本功。</p></callout>

## 十四、安全合规清单（等保视角）

政企/金融场景上 Agent，合规是硬门槛。一份检查清单：

```text
合规检查清单：
  ✅ 数据不出境：模型/存储区域合规（国内业务用国内 region）
  ✅ 传输加密（TLS）+ 静态加密（KMS）
  ✅ 访问审计：谁在什么时间调了什么 Agent、返回什么
  ✅ 敏感数据脱敏：日志/记忆里不落明文身份证/手机号
  ✅ 权限最小化：Agent 只拥有完成任务所需权限
  ✅ 内容合规审查：输出过合规过滤器（第十二章）
```

<callout emoji="💡"><p>国内政企项目，合规往往比技术更决定成败。我见过技术方案满分，却因"数据出境"一条被否。所以这节课反复强调"先问数据边界，再选框架"——这不是保守，是工程常识。</p></callout>

## 十五、上线前最后自检清单

把这一章浓缩成一份"上线前必过"的清单，照着打钩：

```text
Agent 上生产线·终检清单
─────────────────────────────────────────
[ ] 密钥零硬编码（Secret Manager / 环境变量）
[ ] 会话持久化 + 多租户隔离（user_id 过滤）
[ ] 鉴权拦截（IAM/JWT），不可逆动作需 HITL
[ ] 可观测：日志 + 追踪 + 指标三件套接好
[ ] 成本：模型分级 + 提示词缓存 + 预算告警
[ ] 弹性：扩缩配置（Cloud Run / Agent Engine）
[ ] 降级：主模型/存储不可用时的 fallback
[ ] 灰度：10% 流量先放量，可回滚
[ ] 评测：CI 卡评测指标，不达标不发布
[ ] 合规：数据驻留/加密/审计满足要求
─────────────────────────────────────────
```

<callout emoji="💡"><p>这份清单是第五章的"毕业证书"。能逐条说清怎么实现的，说明你不是"会写 Agent"，而是"能交付 Agent 系统"。这也是资深岗和初级岗最本质的差异。</p></callout>

## 十六、常见部署故障与排查

给你一份生产常见故障速查，省去你踩坑时间：

```text
故障1：Agent 部署后调用 401
  原因：鉴权令牌失效 / IAM 权限不足 → 检查 JWT 校验与 IAM 策略

故障2：首次调用延迟极高（冷启动）
  原因：Cloud Run 缩到 0 后冷启动 → 设 min-instances=1 保活

故障3：会话丢失
  原因：用了 InMemorySessionService → 换持久化 SessionService

故障4：成本暴涨
  原因：Loop 无上限 / 无缓存 → 查 maxIterations + 提示词缓存

故障5：多用户数据串号
  原因：Session 未隔离 → 检查 user_id/tenant 过滤
```

<callout emoji="⚠️"><p>这五条故障覆盖了 80% 的部署事故。每次出问题先对照这张表，比盲目改代码快得多。记住：部署的问题，90% 出在"鉴权 / 会话 / 成本 / 隔离"这四件事上。</p></callout>

## 小结

这节课收尾了整个第五章，把 ADK 从本地 demo 推到生产：

1. **为什么上云**：自建生产 Agent 平台要补运维/扩缩/鉴权/密钥/可观测/合规六件事，Vertex 帮你托管。
2. **三种形态**：本地、Cloud Run（容器化弹性）、Vertex Agent Engine（全托管，ADK 一等公民）。
3. **部署实操**：`agent.yaml` 声明入口 + `rootAgent` 静态暴露 + ADK CLI 推 Vertex；或 Dockerfile 打镜像上 Cloud Run。
4. **四大扩展点**：IAM 鉴权与多租户隔离、OpenTelemetry 可观测、会话持久化、成本治理（模型分级/缓存/预算）。
5. **云锁定决策**：GCP 重度用户最优；强合规/混合云要解耦内置工具与部署层；私有化慎选。
6. **上线 checklist**：9 条覆盖企业级工程全部要求。

记住一句话：**ADK 的价值一半在"写 Agent 快"，另一半在"上 Vertex 快"——但快是有代价的（云锁定），选型要先看数据边界，再看框架便利。**

---

## 延伸阅读 / 引用文档

1. Vertex AI Agent Engine 官方文档：<https://cloud.google.com/vertex-ai/generative-ai/docs/agent-engine/overview>
2. ADK 部署指南（Deploy）：<https://google.github.io/adk-docs/deploy/>
3. Cloud Run 文档：<https://cloud.google.com/run/docs/overview>
4. Google Secret Manager：<https://cloud.google.com/secret-manager/docs>
5. OpenTelemetry 官方：<https://opentelemetry.io/docs/>
6. GCP IAM 概览：<https://cloud.google.com/iam/docs/overview>

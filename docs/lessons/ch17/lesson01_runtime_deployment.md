# 第十七章第1节：从 Demo 到生产：Agent 运行时与部署架构

大家好，我是秋天。

前面十六章，你写的 Agent 基本都跑在 `main` 方法里、跑在 IDE 控制台里、跑在一个 JVM 进程里。聊天、调工具、跑循环，看起来都挺顺。但只要你试着把它"上线"，把真实流量打进来，问题立刻冒出来：进程一重启，用户上一句问了什么全没了；一个 request 跑到一半 OOM 崩溃，任务直接废了；流量一上来，单实例扛不住，你想加机器，发现加不了——因为"会话状态"全锁在这个进程的内存里。

这一节，我们正式从"Demo"跨到"生产"。我会带你把 Agent 当成一个**有状态的在线服务**来设计它的运行时与部署架构：无状态 vs 有状态的本质区别、会话保持怎么做、水平扩展的命门在哪、容器化怎么落地。这是 Java 后端工程师的看家本领，但放到 Agent 场景里有几个特殊坑，今天一次性讲透。

这节课讲什么、学完掌握什么：

- 讲什么：① Agent 运行时的"状态从哪来"——无状态（stateless）和有状态（stateful）两种架构的本质；② 会话保持（session affinity / 外部化会话存储）的几种做法与取舍；③ 水平扩展（horizontal scaling）为什么对有状态 Agent 是难题、怎么解；④ 容器化（Docker + Kubernetes）部署 Agent 的真实形态与 YAML/Dockerfile 示例。
- 学完掌握：你能画出一个生产级 Agent 部署拓扑；你能说清楚"为什么 Agent 默认要往无状态方向设计"；你能写出一个把会话状态外置到 Redis 的 Spring Boot Agent 服务；你能看懂并改一份 K8s Deployment 配置。

<callout emoji="💡"><p>招聘映射：2026 年 Java Agent 岗位的 JD 里，"工程化部署""容器化""高并发高可用""可水平扩展"几乎成了标配。阿里/字节/腾讯的 Agent 平台岗明确写"熟悉 Agent 运行时与部署架构、有生产级 AI 服务上线经验"。这一节就是冲着这条要求去的——它区分的是"会写 Demo 的人"和"能把 Agent 真正交出去的人"。</p></callout>

---

## 一、先搞清：Agent 的"状态"到底在哪

很多同学一上手就把 Agent 写成这样：

```java
@RestController
public class AgentController {

    // ⚠️ 灾难：把会话状态放在单例/静态字段里
    private final Map<String, ChatMemory> sessions = new ConcurrentHashMap<>();

    private final Agent agent;

    @PostMapping("/chat")
    public String chat(@RequestBody ChatReq req) {
        ChatMemory mem = sessions.computeIfAbsent(
                req.userId(), k -> TokenWindowChatMemory.withMaxTokens(2000));
        return agent.chat(mem, req.message());
    }
}
```

这段代码本地跑很爽，但一上线就爆。为什么？因为它把**会话状态绑死在这个 JVM 进程的内存里**。这个"状态"包含什么？

```
Agent 一次对话的"状态" = 以下内容的总和
┌──────────────────────────────────────────────┐
│ 1. 对话历史（ChatMemory / Message 列表）        │
│ 2. 当前任务进度（PlanNotebook / 子任务状态）    │
│ 3. 工具调用中间结果（草稿、检索到的文档）       │
│ 4. 用户身份与权限上下文（JWT 解析结果）         │
│ 5. 长任务的执行位置（在跑第几步、卡在哪）       │
└──────────────────────────────────────────────┘
```

注意第 5 点——普通 Web 服务的"状态"通常只是 session 里的几个字段；但 Agent 一旦涉及长任务（对账、报告生成、多步编排），它的状态是一台"正在运行的自动机"。这是 Agent 部署和传统 Web 部署最大的不同。

<callout emoji="⚠️"><p>头号坑：把 Agent 当无状态 HTTP 服务部署，但代码里偷偷藏了进程内状态。部署两台机器做负载均衡，用户第一次打到机器 A，第二次打到机器 B，机器 B 压根不认识他——会话直接断裂。这不是 Agent 的锅，是你架构没设计对。</p></callout>

---

## 二、无状态 vs 有状态：本质区别

我先给一个干净的定义，别被名词绕晕：

```
无状态（Stateless）架构：
   每次请求自包含——请求里携带（或可寻址获取）它需要的全部上下文。
   服务端不保存"跨请求的会话"，只做"接收输入→处理→返回输出"。
   例：一个纯 RAG 问答接口，问一句答一句，历史全由客户端带来，或按 sessionId 从外部存储取。

有状态（Stateful）架构：
   服务端保留会话/任务的运行上下文，跨多个请求持续演进。
   例：一个多轮 Agent，服务端维护它的 ChatMemory 和当前 plan，
       用户每发一句，服务端在"已有状态"上继续推进。
```

对 Agent 来说，真正的分野是：**状态存在哪、由谁持有**。

### 方案 A：无状态化（推荐的主流方向）

把上面那张"状态清单"全部外置到一个**共享存储**（Redis / PostgreSQL / 专门的记忆服务）。服务进程本身不持有任何会话数据，只处理逻辑。

```
        ┌──────────┐   ┌──────────┐
请求 ──▶│ Agent 服务│   │ Agent 服务│  ← 任意一台都能处理任意 session
        │ (无状态) │   │ (无状态) │
        └────┬─────┘   └────┬─────┘
             │              │
             └──────┬───────┘
                    ▼
           ┌─────────────────┐
           │ 共享状态存储      │  ← ChatMemory / Plan / 权限上下文
           │ Redis / PG / 向量库│
           └─────────────────┘
```

好处：加机器就扩容、进程挂了不丢状态、灰度发布无感。这是云原生 Agent 的标准姿势。

### 方案 B：有状态（会话亲和 / Sticky Session）

如果状态实在外置成本高（比如长任务执行位置很难序列化），可以退而求其次：用负载均衡的**会话亲和**（Sticky Session），把同一 `sessionId` 的请求固定打到同一台机器。

```
LB (按 sessionId 哈希) ──▶ 机器A(持有 session-X 状态)
                      └──▶ 机器B(持有 session-Y 状态)
```

坏处也很明显：机器挂了，它持有的所有会话当场蒸发；扩缩容要迁移状态；负载不均。所以这只适合"状态极难外置"的特殊长任务场景（而且通常会和下一节的 Durable Execution 结合）。

<callout emoji="✅"><p>判断口诀：能外置的尽量外置，外置不了才上 Sticky。绝大多数 Agent 的 ChatMemory、用户信息、工具结果都能序列化进 Redis，所以**无状态化是默认正确解**。只有"长任务执行位置"这种最难序列化的，才留给 Durable Execution（lesson02）或 Sticky 兜底。</p></callout>

---

## 三、会话保持的具体实现：状态外置到 Redis

Spring AI / LangChain4j 的 `ChatMemory` 都有可插拔的存储实现。以 Spring AI Alibaba + Redis 为例，把 `ChatMemory` 的底层存储换成 Redis，服务本身就是无状态的。

先看依赖（`pom.xml` 片段）：

```xml
<dependencies>
    <dependency>
        <groupId>com.alibaba.cloud.ai</groupId>
        <artifactId>spring-ai-alibaba-starter</artifactId>
        <version>1.0.0.2</version>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
</dependencies>
```

配置（`application.yml`），密钥走环境变量，绝不硬编码：

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}   # 仅从环境变量读，禁止写死
server:
  port: 8080
```

无状态 Agent 服务的核心——用 Redis 做会话存储的 `ChatMemory` 实现：

```java
package com.example.ch17.deploy;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 把会话历史存在 Redis，服务本身不持有内存状态 → 可水平扩展。
 */
public class RedisChatMemory implements ChatMemory {

    private final RedisTemplate<String, Object> redis;
    private final String namespace;
    private final int ttlSeconds;

    public RedisChatMemory(RedisTemplate<String, Object> redis,
                           String namespace, int ttlSeconds) {
        this.redis = redis;
        this.namespace = namespace;
        this.ttlSeconds = ttlSeconds;
    }

    private String key(String conversationId) {
        return namespace + ":mem:" + conversationId;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        String k = key(conversationId);
        redis.opsForList().rightPushAll(k, messages.toArray());
        redis.expire(k, ttlSeconds, TimeUnit.SECONDS);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Message> get(String conversationId, int lastN) {
        String k = key(conversationId);
        long size = redis.opsForList().size(k);
        long start = Math.max(0, size - lastN);
        return (List<Message>) redis.opsForList().range(k, start, -1);
    }

    @Override
    public void clear(String conversationId) {
        redis.delete(key(conversationId));
    }
}
```

控制器层——注意它**不持有任何会话字段**，全部靠 `conversationId` 去 Redis 取：

```java
package com.example.ch17.deploy;

import org.springframework.web.bind.annotation.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;

@RestController
@RequestMapping("/agent")
public class StatelessAgentController {

    private final ChatClient chatClient;
    private final ChatMemory memory;

    public StatelessAgentController(ChatClient.Builder builder, ChatMemory memory) {
        this.chatClient = builder.build();
        this.memory = memory;
    }

    public record ChatReq(String conversationId, String message) {}
    public record ChatResp(String reply) {}

    @PostMapping("/chat")
    public ChatResp chat(@RequestBody ChatReq req) {
        // 从 Redis 取历史 → 推理 → 把新消息写回 Redis
        // 任意一台实例都能处理任意 conversationId
        String reply = chatClient.prompt()
                .user(req.message())
                .advisors(a -> a.param(
                        org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID,
                        req.conversationId))
                .call()
                .content();
        return new ChatResp(reply);
    }
}
```

<callout emoji="💡"><p>关键认知：无状态 ≠ 没有状态，而是**状态不在进程里**。这一行之差，决定了你能否在凌晨三点安心扩缩容、能否在发布时不影响任何一个正在对话的用户。对 Java 工程师这是基本功，但放到 Agent 上要主动把"记忆/Plan/权限上下文"都外置，别让框架的默认内存实现把你锁死。</p></callout>

---

## 四、水平扩展的命门：长任务怎么办

短问答（"查一下我的订单"）无状态化很轻松。但真实企业场景里大量是**长任务**：

```
长任务示例（金融/政企常见）：
- 月度对账 Agent：拉 3 个系统数据 → 比对 → 生成差异报告 → 推送审批（15 分钟）
- 合同审查 Agent：解析 200 页 PDF → 抽取条款 → 风险标注 → 出意见书（8 分钟）
- 代码迁移 Agent：扫描仓库 → 逐文件改 → 跑测试 → 提 PR（40 分钟）
```

这种任务，用户发一个"开始"指令后，Agent 要跑很久。问题来了：

```
                   致命场景
用户 ──"开始对账"──▶ 实例A 开始跑（状态在A的内存里）
                          │
                          │ 实例A 被 K8s 重启 / 扩容漂移 / OOM
                          ▼
                    任务进度全部丢失，用户只看到超时
```

无状态化在这里**失效**——因为任务执行位置这层状态太难塞进 Redis（它不是一个简单列表，而是一台运行中的状态机）。这里有两套正解：

1. **Durable Execution（lesson02 主角）**：用 Temporal / Restate，把"执行位置"也变成可持久化、可恢复的对象。进程挂了，框架从最近的检查点接着跑。
2. **任务与请求解耦（异步化）**：用户的 HTTP 请求只负责"提交任务"，立即返回 `taskId`；真正的长任务由一个 Worker 异步执行，状态落在任务存储里。HTTP 层完全无状态，长任务用专门的 durable 机制保命。

第二种的简化版拓扑：

```
   ┌────────────┐   提交任务    ┌──────────────────┐
   │  API 网关   │ ───────────▶ │ 任务调度/队列      │
   │ (无状态,可扩)│              │ (Kafka / RabbitMQ) │
   └────────────┘              └─────────┬────────┘
                                          │ 投递
                                          ▼
                                  ┌──────────────────┐
                                  │ Agent Worker 池   │
                                  │ (Durable 执行)     │
                                  │ 进度 → 任务存储     │
                                  └──────────────────┘
                                          │
                                  用户轮询/WebSocket 查 taskId 进度
```

<callout emoji="⚠️"><p>别用"同步 HTTP 等 40 分钟"的方式跑长任务。网关超时、LB 断开、客户端超时都会让你前功尽弃。长任务必须异步化 + 幂等 + 可恢复，这是生产 Agent 和玩具 Agent 的分水岭。lesson02 的 Durable Execution 专门治这个。</p></callout>

---

## 五、容器化：Docker 怎么打

Agent 服务本质是 Spring Boot 应用，Docker 化没啥花活，但有几个 Agent 特有的注意点。

`Dockerfile`（多阶段构建，Java 17）：

```dockerfile
# 构建阶段
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# 运行阶段
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/agent-service.jar app.jar
# Agent 常需较大堆外内存（向量检索/模型缓存），按实际调
ENV JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC"
EXPOSE 8080
# 密钥绝不进镜像，运行时由 K8s Secret 注入
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

几个 Agent 特有的点：

```
普通 Spring Boot 镜像 vs Agent 镜像 的差异点：
1. 内存：Agent 要缓存 ChatMemory、可能本地嵌向量索引，堆/堆外要留足
2. 密钥：DashScope/OpenAI Key 走 Secret 注入，绝不写进 ENV 固化到镜像
3. 模型权重：除非本地推理，否则不打包权重；推理类见第十八章
4. 启动探针：Agent 第一次冷启动可能要加载上下文，readiness 探针要等它真就绪
5. 资源限额：防单个 Agent 任务吃满节点（沙箱思想延伸到部署层）
```

<callout emoji="💡"><p>镜像里出现 `DASHSCOPE_API_KEY=sk-xxx` 这种东西，直接算生产事故。用 `--build-arg` 也不行（会留在镜像历史）。正确做法：镜像里只有 `${ENV}`，运行时由 K8s `secretKeyRef` 注入。这条既关乎安全，也是面试常考的"密钥管理"。</p></callout>

---

## 六、Kubernetes 部署：真实形态

一份生产级 Agent 服务的 `Deployment` + `Service`（注意资源限额、探针、密钥注入）：

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: agent-service
  labels:
    app: agent-service
spec:
  replicas: 3                # 无状态 → 直接多副本水平扩展
  selector:
    matchLabels:
      app: agent-service
  template:
    metadata:
      labels:
        app: agent-service
    spec:
      containers:
        - name: agent
          image: registry.internal/agent-service:1.4.0
          ports:
            - containerPort: 8080
          env:
            - name: REDIS_HOST
              value: "redis-prod.svc.cluster.local"
            - name: DASHSCOPE_API_KEY      # 从 Secret 注入，镜像里看不到
              valueFrom:
                secretKeyRef:
                  name: agent-secrets
                  key: dashscope-api-key
          resources:
            requests:
              cpu: "500m"
              memory: "1Gi"
            limits:
              cpu: "1"
              memory: "2Gi"               # 防单实例吃满节点
          readinessProbe:                 # 就绪探针：Agent 真能接客才放流量
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 15
            periodSeconds: 10
          livenessProbe:                  # 存活探针：卡死就重启
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            periodSeconds: 20
---
apiVersion: v1
kind: Service
metadata:
  name: agent-service
spec:
  selector:
    app: agent-service
  ports:
    - port: 80
      targetPort: 8080
  type: ClusterIP
```

配合 HPA（水平 Pod 自动扩缩）按 CPU/自定义指标（如 `in_flight_requests`）扩缩：

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: agent-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: agent-service
  minReplicas: 3
  maxReplicas: 20
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 65
```

<callout emoji="✅"><p>Agent 服务的 HPA 不能只看 CPU。很多 Agent 瓶颈在"等待 LLM 返回的空闲期"——CPU 低但并发连接高（不是计算密集，是 IO 等待密集）。这时应按"并发请求数"或"在途任务数"扩缩，否则会出现"CPU 没满但用户全超时"的诡异现象。自定义指标 + KEDA 是更对的解法。</p></callout>

---

## 七、完整生产拓扑：把前面串起来

一个真实的企业级 Agent 平台部署长这样（以客服/数字员工为例）：

```text
                          ┌─────────────────────────────────────────┐
   用户/前端               │            Kubernetes 集群                │
      │                   │                                          │
      ▼                   │   ┌────────────┐   ┌────────────┐        │
  ┌────────┐  HTTPS       │   │ Gateway    │   │ Gateway    │        │
  │  Web/  │──────────────▶│   │(Higress/  │──▶│ (鉴权/限流/ │        │
  │  App   │              │   │ Sentinel)  │   │ 灰度)      │        │
  └────────┘              │   └─────┬──────┘   └─────┬──────┘        │
                          │         │                │               │
                          │         ▼                ▼               │
                          │   ┌──────────┐    ┌──────────────┐       │
                          │   │ Agent API│    │ Agent Worker │        │
                          │   │ (无状态, │    │ (Durable长任务│       │
                          │   │ 可HPA)   │    │ ,Temporal)   │        │
                          │   └────┬─────┘    └──────┬───────┘       │
                          │        │                  │               │
                          │        ▼                  ▼               │
                          │   ┌──────────────────────────────┐       │
                          │   │ 共享存储层                    │       │
                          │   │ Redis(会话) PG(业务) 向量库   │       │
                          │   │ 对象存储(文件/报告)           │       │
                          │   └──────────────────────────────┘       │
                          │                                          │
                          │   ┌──────────┐  ┌──────────────────┐    │
                          │   │ 可观测    │  │ 模型网关          │    │
                          │   │ OTel/Prom │  │ (DashScope/自建)  │    │
                          │   └──────────┘  └──────────────────┘    │
                          └─────────────────────────────────────────┘
```

要点一句话总结：**API 层无状态可 HPA，长任务层 Durable 可恢复，状态全外置，密钥进 Secret，全链路可观测**。这一张图就是这节课的核心 deliverable。

---

## 八、企业落地：为什么这样设计

我讲一个真实踩过的坑。某政企项目的"政策问答 Agent"上线第一周，单机 2 核 4G，白天峰值 300 并发，频繁 Full GC 超时。运维说"加机器"，加了 3 台之后问题反而更怪：用户 A 第一轮打到机器 1，第二轮打到机器 2，机器 2 没有他的历史，Agent 像失忆了一样重新问一遍。

根因就是这节课讲的状态问题：ChatMemory 在 JVM 进程内。解法：

```
第 1 步：ChatMemory 外置 Redis（第三节的做法）→ 失忆问题消失，可随便加机器
第 2 步：API 层 HPA 按并发请求扩缩 → 峰值自动到 8 副本，闲时缩回 3
第 3 步：长任务（政策文件批量解析）拆出来进 Temporal Worker → 不再同步阻塞 HTTP
第 4 步：密钥从配置文件迁到 K8s Secret → 安全评审通过
```

改完之后，同样的 2 核 4G 配置，单机扛量翻倍，且能弹性扩容。这就是"架构设计"带来的真实收益，不是堆机器能解决的。

<callout emoji="⚠️"><p>别把"加机器"当架构。无状态化 + 状态外置是一次性投入、长期收益；堆机器是每次流量上来都再花一遍钱，还解决不了失忆和丢任务。招聘时面试官问"你的 Agent 怎么扛高并发"，他要听的是这套分层设计，不是"我们买了 20 台服务器"。</p></callout>

---

## 九、滚动发布与优雅下线：Agent 特有的发布难题

普通无状态服务滚动发布很简单：新 Pod 起来、就绪探针通过、旧 Pod 摘流量、删掉。但 Agent 服务有两个特殊点，处理不好发布一次就丢一批任务：

```
难点 1：长任务在途。滚动更新杀掉旧 Pod 时，它正在跑的 40 分钟对账任务直接没了。
难点 2：WebSocket / SSE 长连接。实时推送给前端的流式中断，用户看到"连接断开"。
```

正解：**优雅下线（Graceful Shutdown）+ 任务可恢复**。

Spring Boot 开启优雅下线（让旧实例处理完在途请求再退出）：

```yaml
server:
  shutdown: graceful          # 优雅关闭，等待在途请求完成
spring:
  lifecycle:
    timeout-per-shutdown-phase: 60s   # 最多等 60s 让在途请求收尾
```

K8s 侧配合 `preStop` 钩子 + `terminationGracePeriodSeconds`，先摘流量再停进程：

```yaml
spec:
  template:
    spec:
      terminationGracePeriodSeconds: 90
      containers:
        - name: agent
          lifecycle:
            preStop:
              exec:
                command: ["/bin/sh", "-c", "sleep 15"]  # 先等 LB 摘流，再优雅退出
```

而真正跑长任务的 Worker（Durable 那层），关闭时**不要强行杀**，交给 Temporal/Restate 的"任务可恢复"机制——进程没了，任务状态在外部存储里，新 Worker 起来接着跑（lesson02 会看到）。所以 Agent 发布的正确姿势是：

```
滚动发布顺序：
1. 新版本 Pod 起，readiness 未过 → 不收流量
2. 旧 Pod 进 termination：preStop sleep 摘流 → 优雅处理完在途短请求
3. Durable 长任务不强制中断，由框架保证可恢复
4. 旧 Pod 退出，新 Pod 承接 → 用户无感
```

<callout emoji="✅"><p>判断你部署是否"生产级"的一条硬标准：发布/缩容时，正在对话的用户是否会断、正在跑的长任务是否会废。如果答案是"会"，说明你还没解决优雅下线和任务可恢复。这两点是 Agent 上线前必须过的关。</p></callout>

## 十、上线前部署 Checklist

把这一节内容收敛成一个可直接用的检查清单，建议你每次上线前过一遍：

```text
Agent 生产部署 Checklist
✅ 会话状态外置（Redis/PG），服务进程本身无状态
✅ 长任务异步化 + Durable Execution（或任务队列解耦）
✅ Dockerfile 多阶段构建，密钥不进镜像
✅ K8s Secret 注入所有 API Key，镜像里只有 ${ENV}
✅ readiness/liveness 探针配置且等待真就绪
✅ resources.requests/limits 设置，防单实例吃满节点
✅ HPA 按并发/在途任务数（而非仅 CPU）扩缩
✅ 优雅下线：server.shutdown=graceful + preStop 摘流
✅ 全链路可观测（OTel trace + Prometheus 指标 + 日志）
✅ 沙箱/权限/审计（lesson03、第十二章）已就位
```

这份清单其实就是把第十七章四节课（运行时、Durable、沙箱、平台工程）的部署相关要点捏在一起。

---

## 十一、面试自测

1. **Agent 的状态包含什么？** —— 对话历史、任务进度、工具中间结果、权限上下文、长任务执行位置。
2. **无状态和有状态怎么选？** —— 默认无状态（状态外置 Redis/PG）；只有难序列化的长任务执行位置才上 Durable Execution 或 Sticky 兜底。
3. **水平扩展的命门？** —— 长任务执行位置难外置；正解是异步化 + Durable Execution，且 HPA 应按并发而非仅 CPU。
4. **密钥怎么管？** —— K8s Secret 注入，镜像里只有 `${ENV}`，绝不固化。

---

## 十、小结

这一节我们把 Agent 从 Demo 推到了生产门口：

1. **状态是核心问题**：Agent 的状态不只是 session 字段，而是一台"运行中的自动机"，尤其长任务。
2. **无状态是默认正确解**：把 ChatMemory / Plan / 权限上下文全部外置到 Redis / PG / 向量库，服务进程本身不持有状态，才能水平扩展、进程挂了不丢数据。
3. **长任务必须异步化 + 可恢复**：同步 HTTP 等几十分钟必崩；正确姿势是任务与请求解耦，长任务交给 Durable Execution（lesson02）保命。
4. **容器化没花活但有特例**：密钥走 Secret、内存要留足、探针要等真就绪；HPA 按并发而非仅 CPU。
5. **完整拓扑一句话**：API 无状态可 HPA，长任务 Durable 可恢复，状态全外置，密钥进 Secret，全链路可观测。

下一节（lesson02）我们专门攻克"长任务执行位置"这个最难外置的状态——用 Temporal / Restate 的 Durable Execution，让 Agent 跑 40 分钟中途崩溃也能从检查点续上。

---

## 延伸阅读 / 引用文档

1. Kubernetes 官方文档（Deployment / HPA / Secret）：https://kubernetes.io/docs/concepts/workloads/controllers/deployment/
2. Spring AI 记忆（ChatMemory）API 文档：https://docs.spring.io/spring-ai/reference/api/chatmemory.html
3. Spring AI Alibaba 文档（DashScope / 部署）：https://java2ai.com/docs/1.0.0.2/
4. Spring Boot 生产就绪（Actuator 探针）：https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html
5. KEDA 基于事件/自定义指标的弹性伸缩：https://keda.sh/docs/
6. 12-Factor App（无状态/配置/后端服务外置原则）：https://12factor.net/

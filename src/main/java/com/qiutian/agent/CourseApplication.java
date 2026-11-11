package com.qiutian.agent;

/**
 * 课程配套代码入口。
 * 本仓库不是单一 Spring Boot 应用，而是「按章节分包的可运行示例集合」。
 * 每个章节包（com.qiutian.agent.chxx_xxx）下都有带 main 方法的独立示例，
 * 可直接用 {@code java -cp} 或 IDE 运行。需要真实模型调用的示例会读取环境变量里的 API Key，
 * 未设置时给出友好提示（不会抛出异常堆栈），方便你先读代码、后接 Key 运行。
 */
public class CourseApplication {

    public static void main(String[] args) {
        System.out.println("=====================================================");
        System.out.println("  Java Agent 架构师训练营 · 配套代码 (v1.0.0)");
        System.out.println("  技术栈: Spring Boot 3.4 + Spring AI 1.0");
        System.out.println("          + LangChain4j 1.0 + Spring AI Alibaba");
        System.out.println("=====================================================");
        System.out.println("章节包一览（每个包下均有可运行示例）:");
        System.out.println("  ch01_02_langchain4j  - LangChain4j 基础与 Agent");
        System.out.println("  ch03_springai        - Spring AI ChatClient / 结构化输出 / 工具");
        System.out.println("  ch04_multiagent      - 多 Agent 编排（Graph / Supervisor / Loop）");
        System.out.println("  ch06_memory          - 记忆（PgVector / 向量检索）");
        System.out.println("  ch07_rag             - RAG 全链路（解析 / 嵌入 / 混合检索 / 重排）");
        System.out.println("  ch09_patterns        - Agent 架构设计模式");
        System.out.println("  ch10_engineering     - 工程化（可观测 / 容错 / 成本）");
        System.out.println("  ch11_mcp             - MCP 协议（Server / Client）");
        System.out.println("  ch12_security        - 工具安全与输出护栏");
        System.out.println("  ch15_context         - 上下文工程（Write/Select/Compress/Isolate）");
        System.out.println("  ch16_eval            - 评测（RAGAS / LLM-as-Judge 参考实现）");
        System.out.println("  ch17_deployment      - 部署与 Durable Execution");
        System.out.println("  ch18_finetune        - 微调（Python 脚本，见 ch18_finetune/）");
        System.out.println("=====================================================");
        System.out.println("运行示例: 例如 java -cp target/classes com.qiutian.agent.ch03_springai.ChatClientDemo");
        System.out.println("需要 Key 的示例请先: export OPENAI_API_KEY=sk-xxx  或  export DASHSCOPE_API_KEY=sk-xxx");
    }
}

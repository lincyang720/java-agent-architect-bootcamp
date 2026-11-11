package com.qiutian.agent.ch03_springai;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Map;

/**
 * 第3章示例的轻量运行支撑。
 *
 * <p>这些示例都是「带 main 的独立类」，需要在运行时拿到一个 ChatClient（底层依赖 ChatModel）。
 * 为了同时满足两件事——① 演示「注入 ChatClient.Builder 构建 ChatClient」的写法；
 * ② 保证离线编译、无密钥环境下 {@code java -cp} 不抛异常——这里用最小化的 Spring 上下文
 * （不开 Web 服务器、不启用 MCP、排除自动生成的 ChatClient.Builder 以避免多 ChatModel 下的歧义）
 * 来提供模型 Bean，再由其构建 ChatClient。</p>
 *
 * <p>因为所有真实模型调用前都已 {@code Demo.ensureKey(...)} 守卫，无密钥时 main 会提前 return，
 * 根本不会走到上下文启动这一步，所以 CI / 无密钥环境永远安全。</p>
 */
public final class Ch03Support {

    private Ch03Support() {
    }

    /** 默认系统提示词：统一口吻。 */
    public static final String DEFAULT_SYSTEM =
            "你是一名资深 Java 后端工程师，回答要简洁、可落地、不啰嗦。";

    /**
     * 启动一个仅含自动配置、不开 Web 的 Spring 上下文，用于拿到自动配置的模型 Bean。
     * 排除自动生成的 {@code ChatClient.Builder}，避免 OpenAi / DashScope 两个 ChatModel 并存时的歧义。
     */
    public static ConfigurableApplicationContext bootstrap() {
        SpringApplication app = new SpringApplication(Cfg.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(Map.of(
                // 关闭可能无关且会绑端口/连服务的 MCP 自动配置
                "spring.ai.mcp.server.enabled", "false",
                "spring.ai.mcp.client.enabled", "false",
                // 排除自动 ChatClient.Builder，自己用 ChatClient.builder(model) 构建，避免歧义
                "spring.autoconfigure.exclude",
                "org.springframework.ai.autoconfigure.chat.client.ChatClientAutoConfiguration"
        ));
        return app.run();
    }

    /** 仅启用自动配置的配置类（不做组件扫描，避免扫描到其他示例）。 */
    @org.springframework.context.annotation.Configuration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    public static class Cfg {
    }

    /** 从上下文取出 OpenAiChatModel，构建带默认系统提示的 ChatClient（对应注入 ChatClient.Builder）。 */
    public static ChatClient openAiClient(ConfigurableApplicationContext ctx) {
        OpenAiChatModel model = ctx.getBean(OpenAiChatModel.class);
        // 这里等价于在真实工程里 @Autowired ChatClient.Builder 后 .defaultSystem(...).build()
        return ChatClient.builder(model).defaultSystem(DEFAULT_SYSTEM).build();
    }

    /** 从上下文取出 DashScopeChatModel（通义千问），构建 ChatClient。 */
    public static ChatClient dashScopeClient(ConfigurableApplicationContext ctx) {
        DashScopeChatModel model = ctx.getBean(DashScopeChatModel.class);
        return ChatClient.builder(model).defaultSystem(DEFAULT_SYSTEM).build();
    }
}

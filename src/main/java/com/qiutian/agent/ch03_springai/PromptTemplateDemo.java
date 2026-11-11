package com.qiutian.agent.ch03_springai;

import com.qiutian.agent.common.Demo;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Map;

/**
 * Lesson03：提示词工程 —— 用 PromptTemplate / SystemPromptTemplate 做变量化提示词。
 *
 * 把「角色 / 任务 / 当轮输入」拆成可复用的模板，变量外置，便于版本化、灰度、A/B。
 */
public class PromptTemplateDemo {

    public static void main(String[] args) {
        Demo.banner("PromptTemplateDemo · 变量化提示词");

        if (!Demo.ensureKey("OPENAI_API_KEY")) {
            return;
        }

        try (ConfigurableApplicationContext ctx = Ch03Support.bootstrap()) {
            ChatClient chatClient = Ch03Support.openAiClient(ctx);

            // ① 系统提示词模板：用 {role} 占位，可复用、可外置到配置中心
            Demo.note("① 用 SystemPromptTemplate 定义可复用的「角色」模板，注入 {role}");
            String systemTpl = "你是一名{role}，回答要专业、简洁，禁止使用你不知道的数据。";
            SystemPromptTemplate systemPrompt = new SystemPromptTemplate(systemTpl);
            String systemText = systemPrompt.render(Map.of("role", "电商客诉处理专家"));

            // ② 用户提示词模板：用 {complaint} 占位，注入当轮输入
            Demo.note("② 用 PromptTemplate 定义「任务」模板，注入 {complaint}");
            String userTpl = "请将下面的客诉归类到【物流问题/商品质量/退换货/其他】之一，"
                    + "只输出类别名称：\n{complaint}";
            PromptTemplate userPrompt = new PromptTemplate(userTpl);
            String userText = userPrompt.render(Map.of(
                    "complaint", "我买的手机屏幕有亮点，申请退货被拒，怎么办？"));

            // 先展示渲染结果（验证占位符替换），再发起真实调用
            Demo.note("渲染后的系统提示词： " + systemText);
            Demo.note("渲染后的用户提示词： " + userText);

            String category = chatClient.prompt()
                    .system(systemText)
                    .user(userText)
                    .call()
                    .content();
            System.out.println("【客诉分类结果】 " + category);
        }

        Demo.note("示例结束。提示词模板应外置到配置中心 / Prompt 平台，做到可版本化、可灰度、可回滚。");
    }
}

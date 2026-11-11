package com.qiutian.agent.ch10_engineering;

import com.qiutian.agent.common.Demo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 第十章第三节：提示词版本管理（Prompt as Code）。
 * 把提示词当成「代码资产」：每个版本是 resources 下一个独立文件，进 Git、可评审、可回滚。
 * 本演示从 classpath resources 加载 v1 / v2 两个版本的提示词模板，并演示运行时切换与渲染。
 */
public class PromptVersioningDemo {

    public static void main(String[] args) {
        Demo.banner("第十章·提示词版本管理（Prompt as Code）：从 resources 加载版本化模板");

        Demo.note("把提示词当成代码资产：每个版本是 resources 下一个独立文件，进 Git、可评审、可回滚。");
        // 从 classpath resources 加载 v1 / v2 两个版本的提示词模板
        Map<String, String> versions = new LinkedHashMap<>();
        versions.put("v1", loadFromResources("ch10_engineering/prompts/classifier_v1.txt"));
        versions.put("v2", loadFromResources("ch10_engineering/prompts/classifier_v2.txt"));

        // 模拟「配置中心」声明的当前启用版本（生产从 Nacos / 配置中心动态拉取）
        String active = "v2";

        Demo.note("当前启用版本 = " + active + "（生产可灰度 / 回滚，详见课程正文）");
        String template = versions.get(active);
        System.out.println("── 已加载的提示词模板 ──");
        System.out.println(template);

        Demo.note("渲染模板：用模板变量 {domain} 注入（严禁字符串拼接拼提示词）");
        String rendered = render(template, Map.of("domain", "电商客服"));
        System.out.println("── 渲染后（domain=电商客服）──");
        System.out.println(rendered);

        Demo.note("演示版本切换：切回 v1 对比差异（回滚时即切回上一个版本文件）");
        System.out.println("── v1 模板（渲染后）──");
        System.out.println(render(versions.get("v1"), Map.of("domain", "电商客服")));

        Demo.note("结尾：提示词变更应跑评估集(第二节)做质量门禁，指标跌了就回滚（git revert）。");
    }

    /** 从 classpath resources 读取提示词文件（Prompt as Code 的运行时加载）。 */
    static String loadFromResources(String path) {
        InputStream in = PromptVersioningDemo.class.getClassLoader().getResourceAsStream(path);
        if (in == null) {
            throw new RuntimeException("resources 未找到: " + path);
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString().stripTrailing();
        } catch (IOException e) {
            throw new RuntimeException("提示词加载失败: " + path, e);
        }
    }

    /** 极简模板渲染：把 {key} 替换成 value（框架可用 Spring AI PromptTemplate / LangChain4j 模板）。 */
    static String render(String template, Map<String, String> vars) {
        String s = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            s = s.replace("{" + e.getKey() + "}", e.getValue());
        }
        return s;
    }
}

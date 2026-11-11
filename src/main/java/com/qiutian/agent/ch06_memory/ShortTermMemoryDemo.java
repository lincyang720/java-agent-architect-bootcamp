package com.qiutian.agent.ch06_memory;

import com.qiutian.agent.common.Demo;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 第六章第2节 · 短期记忆（Short-Term Memory）：滑动窗口演示。
 *
 * 短期记忆的载体就是「上下文窗口」。最朴素也最常用的管理策略是滑动窗口：
 * 只保留最近 N 条消息，更老的直接丢弃，从而恒定成本、避免 token 爆掉。
 *
 * 这里用纯 Java（Deque + LangChain4j 的 ChatMessage 类型）离线演示窗口行为：
 * 每加入一条消息后打印当前窗口，直观看到「最旧消息被滑出」。
 *
 * ── 框架标准写法（本仓库 pom 当前未含 langchain4j-memory 模块，故未直接 import）──
 *   ChatMemory memory = MessageWindowChatMemory.withMaxMessages(4);
 *   memory.add(UserMessage.from("..."));
 *   memory.add(AiMessage.from("..."));
 *   // memory.messages() 即当前窗口内的消息列表
 *
 * 坑点（lesson02）：纯滑动窗口会「丢身份」——第一轮说的『我是 VIP』到了第 N+1 轮
 * 就被滑出去了。所以身份卡等关键事实应常驻 prompt，不能只靠滑动窗口。
 */
public class ShortTermMemoryDemo {

    /** 最小滑动窗口短期记忆：只保留最近 maxMessages 条。 */
    static final class SlidingWindowMemory {
        private final int maxMessages;
        private final Deque<ChatMessage> window = new ArrayDeque<>();

        SlidingWindowMemory(int maxMessages) {
            this.maxMessages = maxMessages;
        }

        void add(ChatMessage message) {
            window.addLast(message);
            while (window.size() > maxMessages) {
                window.pollFirst(); // 超出则丢弃最老的消息
            }
        }

        List<ChatMessage> snapshot() {
            return List.copyOf(window);
        }
    }

    public static void main(String[] args) {
        Demo.banner("ShortTermMemoryDemo · 短期记忆滑动窗口");

        int maxMessages = 4;
        Demo.note("创建滑动窗口，最多保留 " + maxMessages + " 条消息（用户+AI 都算）。");
        SlidingWindowMemory memory = new SlidingWindowMemory(maxMessages);

        // 模拟多轮对话：user / ai 交替追加
        String[] turns = {
                "我叫秋田，是后端工程师。",
                "好的，我记住了你的身份。",
                "我最近在学 Agent 架构。",
                "Agent 架构很值得深入。",
                "我最喜欢的是记忆模块。",
                "记忆模块是 Agent 的核心之一。"
        };

        for (int i = 0; i < turns.length; i++) {
            ChatMessage msg = (i % 2 == 0)
                    ? UserMessage.from(turns[i])
                    : AiMessage.from(turns[i]);
            memory.add(msg);

            Demo.note("第 " + (i + 1) + " 条加入后，窗口内消息数 = "
                    + memory.snapshot().size() + "（上限 " + maxMessages + "）");
            for (ChatMessage m : memory.snapshot()) {
                String content;
                if (m instanceof UserMessage um) {
                    content = um.singleText();
                } else if (m instanceof AiMessage am) {
                    content = am.text();
                } else {
                    content = m.type().toString();
                }
                System.out.println("    [" + m.type() + "] " + content);
            }
        }

        Demo.note("结论：第 1 条『我叫秋田』已被滑出窗口，窗口恒定保留最近 "
                + maxMessages + " 条。生产环境用 MessageWindowChatMemory 等价，但身份/关键事实应常驻 prompt（见 lesson02）。");
    }
}

package com.qiutian.agent.common;

/**
 * 示例运行辅助：统一的横幅打印与 API Key 校验。
 * 课程里所有需要真实模型调用的 demo，都会先通过 {@link #ensureKey(String)} 检查，
 * 没有 Key 时给出友好提示并返回 false，避免在 CI / 无密钥环境下抛异常。
 */
public final class Demo {

    private Demo() {
    }

    /** 打印章节 / 示例标题横幅。 */
    public static void banner(String title) {
        String line = "═".repeat(Math.min(60, Math.max(20, title.length() + 8)));
        System.out.println("\n" + line);
        System.out.println("  " + title);
        System.out.println(line);
    }

    /** 打印一条提示。 */
    public static void note(String msg) {
        System.out.println("• " + msg);
    }

    /**
     * 检查某个 API Key 是否已设置。
     *
     * @return true=已设置可继续；false=未设置，调用方应直接 return
     */
    public static boolean ensureKey(String envName) {
        if (Env.get(envName) == null) {
            System.out.println("⚠️ 未设置环境变量 " + envName
                    + "，本示例需要真实模型调用。请先设置后运行，例如：");
            System.out.println("    export " + envName + "=sk-xxxx");
            System.out.println("（代码本身已编译通过，仅模型调用需要密钥。）");
            return false;
        }
        return true;
    }

    /** 小暂停，便于观察流式输出（仅本地演示用）。 */
    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}

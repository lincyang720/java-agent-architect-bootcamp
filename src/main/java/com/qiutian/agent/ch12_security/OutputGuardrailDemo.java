package com.qiutian.agent.ch12_security;

import com.qiutian.agent.common.Demo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 第十二章第二节：输出安全 —— 输出护栏。
 * 所有 Agent 最终回复先过护栏：敏感词 / 合规词硬拦截 + PII 检测脱敏。
 * 并把每一次「决策日志」写入审计器（AuditLogger 类），呼应第三节行为审计。
 * 纯 Java，离线可跑。
 */
public class OutputGuardrailDemo {

    /** 审计器：把护栏的「决策日志」写进来，便于事后回溯与合规报送（呼应 ch12 lesson03）。 */
    public static class AuditLogger {
        record AuditEntry(String ts, String type, String detail) {
        }

        private final List<AuditEntry> entries = new ArrayList<>();

        synchronized void log(String type, String detail) {
            AuditEntry e = new AuditEntry(Instant.now().toString(), type, detail);
            entries.add(e);
            System.out.println("  [AUDIT] " + type + " | " + detail);
        }

        List<AuditEntry> all() {
            return List.copyOf(entries);
        }
    }

    /** 敏感词 / 合规禁语过滤。 */
    static class SensitiveWordFilter {
        static final List<Pattern> BLOCK = List.of(
                Pattern.compile("保本(收益|保息)", Pattern.CASE_INSENSITIVE),
                Pattern.compile("绝对(稳赚|不会亏)", Pattern.CASE_INSENSITIVE),
                Pattern.compile("内部消息", Pattern.CASE_INSENSITIVE)
        );

        boolean hits(String text) {
            return BLOCK.stream().anyMatch(p -> p.matcher(text).find());
        }

        String firstHit(String text) {
            for (Pattern p : BLOCK) {
                Matcher m = p.matcher(text);
                if (m.find()) {
                    return m.group();
                }
            }
            return null;
        }
    }

    /** PII 检测 + 脱敏：手机号 / 身份证替换成掩码。 */
    static class PiiMasker {
        static final Pattern PHONE = Pattern.compile("1[3-9]\\d{9}");
        static final Pattern ID = Pattern.compile("\\d{17}[\\dXx]");

        String mask(String text) {
            String s1 = PHONE.matcher(text).replaceAll(m ->
                    m.group().replaceAll("\\d(?=\\d{3})", "*"));
            return ID.matcher(s1).replaceAll(m ->
                    m.group().substring(0, 6) + "********" + m.group().substring(14));
        }
    }

    /** 输出护栏：所有 Agent 最终回复先过它。 */
    static class OutputGuardrail {
        private final SensitiveWordFilter swf = new SensitiveWordFilter();
        private final PiiMasker masker = new PiiMasker();
        private final AuditLogger audit;

        OutputGuardrail(AuditLogger audit) {
            this.audit = audit;
        }

        record Result(String finalText, boolean blocked, String reason) {
        }

        Result process(String raw) {
            // 1. 敏感词 / 合规禁语 硬拦截
            if (swf.hits(raw)) {
                String hit = swf.firstHit(raw);
                audit.log("OUTPUT_BLOCKED", "敏感词:" + hit);
                return new Result("", true, "命中敏感词:" + hit);
            }
            // 2. PII 脱敏（即使未拦截，输出前也要脱敏）
            String masked = masker.mask(raw);
            boolean maskedHappened = !masked.equals(raw);
            // 3. 规则通过，记录放行
            audit.log("OUTPUT_PASSED", "脱敏=" + maskedHappened + " 长度=" + masked.length());
            return new Result(masked, false, "ok");
        }
    }

    public static void main(String[] args) {
        Demo.banner("第十二章·输出安全：护栏（敏感词 / PII 脱敏）+ 决策日志审计");
        AuditLogger audit = new AuditLogger();
        OutputGuardrail guard = new OutputGuardrail(audit);

        String[] outputs = {
                "已为您登记退款，3个工作日内原路返回。",
                "客户手机号13812345678，身份证110101199001011234已登记。",
                "本产品保本保息，绝对稳赚不亏！"
        };

        Demo.note("Agent 输出依次过护栏：敏感词拦截 / PII 脱敏 / 决策日志写入审计器：");
        for (String o : outputs) {
            OutputGuardrail.Result r = guard.process(o);
            if (r.blocked()) {
                System.out.println("  ⛔ 拦截 → " + r.reason());
            } else {
                System.out.println("  ✅ 放行 → \"" + r.finalText() + "\"");
            }
        }

        Demo.note("审计器共记录 " + audit.all().size() + " 条决策日志，可用于回溯与合规报送。");
        Demo.note("结尾：护栏放在 Agent 输出出口，与工具权限网关、沙箱构成「手-嘴-可追溯」安全闭环。");
    }
}

package com.qiutian.agent.ch12_security;

import com.qiutian.agent.common.Demo;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 第十二章第一节：工具调用安全 —— 权限网关（最小权限白名单）。
 * 维护一份「工具名 → 允许角色」策略，所有工具调用在「执行前」先过网关校验，
 * 越权直接拒绝。安全逻辑集中在 Host 侧网关，便于统一开关与审计。
 * 纯 Java，离线可跑。
 */
public class ToolPermissionGateDemo {

    /** 权限策略：工具名 → 允许的角色集合（最小权限白名单，* 表示所有角色）。 */
    static class PermissionPolicy {
        private final Map<String, Set<String>> allowlist = new java.util.HashMap<>();

        void grant(String tool, String... roles) {
            allowlist.put(tool, new HashSet<>(Arrays.asList(roles)));
        }

        boolean allowed(String tool, String role) {
            Set<String> roles = allowlist.get(tool);
            return roles != null && (roles.contains("*") || roles.contains(role));
        }
    }

    /** 安全网关：所有工具调用先过网关再执行。 */
    static class ToolPermissionGate {
        private final PermissionPolicy policy;

        ToolPermissionGate(PermissionPolicy p) {
            this.policy = p;
        }

        record Decision(boolean allow, String reason) {
        }

        Decision check(String tool, String role) {
            if (!policy.allowed(tool, role)) {
                return new Decision(false, "角色 " + role + " 无权调用工具 " + tool);
            }
            return new Decision(true, "ok");
        }
    }

    public static void main(String[] args) {
        Demo.banner("第十二章·工具调用安全：权限网关（最小权限白名单）");

        PermissionPolicy policy = new PermissionPolicy();
        policy.grant("query_order", "*");              // 所有角色可查订单
        policy.grant("send_sms", "agent", "admin");    // 仅 agent/admin 可发短信
        policy.grant("delete_records", "admin");       // 仅 admin 可删数据

        ToolPermissionGate gate = new ToolPermissionGate(policy);

        Demo.note("模拟不同角色尝试调用不同工具，网关在「调用前」统一校验：");
        run(gate, "query_order", "guest");      // 放行
        run(gate, "send_sms", "guest");         // 越权 → 拒绝
        run(gate, "send_sms", "agent");         // 授权 → 放行
        run(gate, "delete_records", "agent");   // 越权 → 拒绝
        run(gate, "delete_records", "admin");   // 授权 → 放行

        Demo.note("结尾：安全逻辑集中在 Host 侧网关，统一拦截、可审计"
                + "（下一节演示把决策日志写入审计器）。");
    }

    static void run(ToolPermissionGate gate, String tool, String role) {
        ToolPermissionGate.Decision d = gate.check(tool, role);
        if (d.allow()) {
            System.out.println("  ✅ [" + role + "] 调用 " + tool + " → 放行并执行");
        } else {
            System.out.println("  ⛔ [" + role + "] 调用 " + tool + " → 拒绝: " + d.reason());
        }
    }
}

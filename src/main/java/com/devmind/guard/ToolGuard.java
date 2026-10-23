package com.devmind.guard;

import java.util.Locale;
import java.util.Set;

/**
 * 工具执行期授权判定（G1 的纯逻辑部分）。
 *
 * <p>职责只有一个：在 {@code ToolRegistry.execute} 真正调用工具之前，回答
 * 「这次调用允许 / 拒绝，命中了哪条策略，为什么」。不做 IO、不依赖 Spring，
 * 便于单测；副作用（写审计、抛异常）由 {@code ToolRegistry} 负责。</p>
 *
 * <p>策略（按顺序判定，先命中先返回）：</p>
 * <ol>
 *   <li>总开关关闭 → ALLOW（GUARD_DISABLED，保持既有行为）</li>
 *   <li>工具在拒绝清单 → DENY（TOOL_DENIED）</li>
 *   <li>写操作 + 只读任务 → DENY（TASK_SCOPE_READ_ONLY）</li>
 *   <li>写操作 + 角色不在写角色内 → DENY（WRITE_REQUIRES_ROLE）</li>
 *   <li>其余 → ALLOW（DEFAULT_ALLOW）</li>
 * </ol>
 */
public class ToolGuard {

    static final String POLICY_DISABLED = "GUARD_DISABLED";
    static final String POLICY_TOOL_DENIED = "TOOL_DENIED";
    static final String POLICY_TASK_SCOPE_READ_ONLY = "TASK_SCOPE_READ_ONLY";
    static final String POLICY_WRITE_REQUIRES_ROLE = "WRITE_REQUIRES_ROLE";
    static final String POLICY_DEFAULT_ALLOW = "DEFAULT_ALLOW";

    private final boolean enabled;
    private final Set<String> writeRoles;
    private final Set<String> deniedTools;

    public ToolGuard(boolean enabled, Set<String> writeRoles, Set<String> deniedTools) {
        this.enabled = enabled;
        this.writeRoles = normalise(writeRoles);
        this.deniedTools = normalise(deniedTools);
    }

    public GuardDecision decide(GuardRequest request) {
        if (!enabled) {
            return GuardDecision.allow(POLICY_DISABLED);
        }
        if (deniedTools.contains(normalise(request.toolName()))) {
            return GuardDecision.deny(POLICY_TOOL_DENIED,
                    "工具 " + request.toolName() + " 已在拒绝清单中");
        }
        if (request.writeOperation()) {
            if (request.taskMode().readOnly()) {
                return GuardDecision.deny(POLICY_TASK_SCOPE_READ_ONLY,
                        "当前为只读任务，不允许执行写操作 " + request.toolName());
            }
            if (!writeRoles.contains(normalise(request.userRole()))) {
                return GuardDecision.deny(POLICY_WRITE_REQUIRES_ROLE,
                        "写操作需要写角色，当前角色不具备");
            }
        }
        return GuardDecision.allow(POLICY_DEFAULT_ALLOW);
    }

    private static Set<String> normalise(Set<String> values) {
        if (values == null) {
            return Set.of();
        }
        return values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(ToolGuard::normalise)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String normalise(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}

package com.devmind.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 执行期授权配置（G1）。
 *
 * <p>角色目前来自 {@code UserService#isAdmin}（ADMIN / USER），后续接入 RBAC 时只需换角色来源，
 * 判定逻辑（{@code ToolGuard}）不变。</p>
 *
 * @param enabled     总开关；关 = 不做任何判定，保持 G1 之前的行为
 * @param writeRoles  允许执行写操作的角色，逗号分隔（如 {@code ADMIN} 或 {@code ADMIN,USER}）
 * @param deniedTools 一律拒绝的工具名，逗号分隔；优先于其它规则
 */
@ConfigurationProperties(prefix = "devmind.guard")
public record DevMindGuardProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("ADMIN") String writeRoles,
        @DefaultValue("") String deniedTools
) {
}

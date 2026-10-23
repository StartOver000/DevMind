package com.devmind.guard;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 执行期授权判定（G1）的纯逻辑测试：不依赖 Spring / 数据库 / 网络。
 */
class ToolGuardTest {

    private static ToolGuard guard(boolean enabled, Set<String> writeRoles, Set<String> deniedTools) {
        return new ToolGuard(enabled, writeRoles, deniedTools);
    }

    private static GuardRequest write() {
        return new GuardRequest("reimburse_submit", true, TaskMode.WRITE, "ADMIN");
    }

    private static GuardRequest read() {
        return new GuardRequest("kb_search", false, TaskMode.READ_ONLY, "USER");
    }

    @Test
    void guardDisabledAllowsEverything() {
        GuardDecision decision = guard(false, Set.of("ADMIN"), Set.of("reimburse_submit"))
                .decide(new GuardRequest("reimburse_submit", true, TaskMode.READ_ONLY, "USER"));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.policyId()).isEqualTo("GUARD_DISABLED");
    }

    @Test
    void readOnlyTaskBlocksWriteTool() {
        GuardDecision decision = guard(true, Set.of("ADMIN"), Set.of())
                .decide(new GuardRequest("reimburse_submit", true, TaskMode.READ_ONLY, "ADMIN"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.policyId()).isEqualTo("TASK_SCOPE_READ_ONLY");
        assertThat(decision.reason()).contains("只读");
    }

    @Test
    void readOnlyTaskAllowsReadTool() {
        GuardDecision decision = guard(true, Set.of("ADMIN"), Set.of()).decide(read());

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.policyId()).isEqualTo("DEFAULT_ALLOW");
    }

    @Test
    void writeToolRequiresWriteRole() {
        GuardDecision decision = guard(true, Set.of("ADMIN"), Set.of())
                .decide(new GuardRequest("reimburse_submit", true, TaskMode.WRITE, "USER"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.policyId()).isEqualTo("WRITE_REQUIRES_ROLE");
    }

    @Test
    void writeToolAllowedForWriteRole() {
        assertThat(guard(true, Set.of("ADMIN"), Set.of()).decide(write()).allowed()).isTrue();
    }

    @Test
    void missingRoleIsNeverAWriteRole() {
        GuardDecision decision = guard(true, Set.of("ADMIN"), Set.of())
                .decide(new GuardRequest("reimburse_submit", true, TaskMode.WRITE, null));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.policyId()).isEqualTo("WRITE_REQUIRES_ROLE");
    }

    @Test
    void deniedToolBlocksEvenRead() {
        GuardDecision decision = guard(true, Set.of("ADMIN"), Set.of("kb_search")).decide(read());

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.policyId()).isEqualTo("TOOL_DENIED");
    }

    @Test
    void deniedToolTakesPrecedenceOverTaskScope() {
        GuardDecision decision = guard(true, Set.of("ADMIN"), Set.of("reimburse_submit"))
                .decide(new GuardRequest("reimburse_submit", true, TaskMode.READ_ONLY, "ADMIN"));

        assertThat(decision.policyId()).isEqualTo("TOOL_DENIED");
    }
}

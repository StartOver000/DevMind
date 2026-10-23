package com.devmind.guard;

/**
 * 授权判定所需的运行时事实（与配置无关，便于纯逻辑测试）。
 *
 * @param toolName       工具名（如 reimburse_submit）
 * @param writeOperation 是否为有副作用的写操作
 * @param taskMode       当前任务模式（只读 / 可写）
 * @param userRole       调用者角色（ADMIN / USER；未知为 null）
 */
public record GuardRequest(
        String toolName,
        boolean writeOperation,
        TaskMode taskMode,
        String userRole
) {
    public GuardRequest {
        taskMode = taskMode == null ? TaskMode.WRITE : taskMode;
    }
}

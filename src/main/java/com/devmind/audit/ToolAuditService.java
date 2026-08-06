package com.devmind.audit;

import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.user.UserService;
import com.devmind.workflow.WorkflowRunRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 审计与用量（M2-3）：
 * - 工具调用统计/明细（tool_call_log，由 ToolRegistry 统一写入）；
 * - 工作流运行统计（workflow_run）。
 * 用量端点按当前用户自己的数据；管理端点查全租户（可指定用户）。
 */
@Service
public class ToolAuditService {

    private final ToolCallLogRepository callLogRepository;
    private final WorkflowRunRepository runRepository;
    private final UserService userService;

    public ToolAuditService(
            ToolCallLogRepository callLogRepository,
            WorkflowRunRepository runRepository,
            UserService userService
    ) {
        this.callLogRepository = callLogRepository;
        this.runRepository = runRepository;
        this.userService = userService;
    }

    /** 当前用户的工具调用统计（用量页） */
    public List<Map<String, Object>> toolStats(Long userId, int days) {
        Long tenantId = userService.tenantIdOf(userId);
        return callLogRepository.stats(tenantId, userId, Math.max(1, Math.min(days, 90)));
    }

    /** 管理员的租户级工具调用统计，可按用户过滤 */
    public List<Map<String, Object>> adminToolStats(Long operatorId, Long targetUserId, int days) {
        requireAdmin(operatorId);
        Long tenantId = userService.tenantIdOf(operatorId);
        return callLogRepository.stats(tenantId, targetUserId, Math.max(1, Math.min(days, 90)));
    }

    /** 管理员的工具调用明细 */
    public List<Map<String, Object>> adminToolLogs(Long operatorId, Long targetUserId, int days, int limit) {
        requireAdmin(operatorId);
        Long tenantId = userService.tenantIdOf(operatorId);
        return callLogRepository.logs(tenantId, targetUserId, Math.max(1, Math.min(days, 90)), limit);
    }

    /** 当前用户的工作流运行统计（按工作流聚合） */
    public List<Map<String, Object>> workflowStats(Long userId, int days) {
        Long tenantId = userService.tenantIdOf(userId);
        return runRepository.statsByWorkflow(tenantId, Math.max(1, Math.min(days, 90)));
    }

    private void requireAdmin(Long userId) {
        if (!userService.isAdmin(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "仅管理员可查看审计数据");
        }
    }
}

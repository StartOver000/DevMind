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

    /**
     * 管理员回放：某用户最近 N 分钟的工具调用时间线 + 拒绝摘要（G2）。
     *
     * <p>回放要回答的是「这次操作到底发生了什么」：按发生顺序列出每个工具调用，
     * 带授权结论（ALLOW/DENY）与命中策略号、参数摘要、耗时、结果状态；
     * 摘要部分给出总次数、被拒绝次数、涉及工具与被拒策略，便于一眼看出「该不该发生」。</p>
     */
    public Map<String, Object> adminReplay(Long operatorId, Long targetUserId, int minutes) {
        requireAdmin(operatorId);
        if (targetUserId == null) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "回放需要指定 userId");
        }
        Long tenantId = userService.tenantIdOf(operatorId);
        int window = Math.max(1, Math.min(minutes, 24 * 60));
        List<Map<String, Object>> events = callLogRepository.timeline(tenantId, targetUserId, window);

        int denied = 0;
        java.util.Set<String> tools = new java.util.LinkedHashSet<>();
        java.util.Set<String> deniedPolicies = new java.util.LinkedHashSet<>();
        for (Map<String, Object> event : events) {
            tools.add(String.valueOf(event.get("tool_name")));
            if ("DENY".equals(event.get("decision"))) {
                denied++;
                Object policy = event.get("policy_id");
                if (policy != null) {
                    deniedPolicies.add(String.valueOf(policy));
                }
            }
        }
        java.util.Map<String, Object> replay = new java.util.LinkedHashMap<>();
        replay.put("userId", targetUserId);
        replay.put("windowMinutes", window);
        replay.put("total", events.size());
        replay.put("denied", denied);
        replay.put("tools", List.copyOf(tools));
        replay.put("deniedPolicies", List.copyOf(deniedPolicies));
        replay.put("events", events);
        return replay;
    }

    private void requireAdmin(Long userId) {
        if (!userService.isAdmin(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "仅管理员可查看审计数据");
        }
    }
}

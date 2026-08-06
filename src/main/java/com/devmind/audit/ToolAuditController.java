package com.devmind.audit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 审计与用量 API：
 * - /api/usage/*：当前用户自己的用量（工具调用统计、工作流运行统计）
 * - /api/admin/audit/*：管理员查看全租户审计（可按用户过滤）
 */
@RestController
public class ToolAuditController {

    private final ToolAuditService auditService;

    public ToolAuditController(ToolAuditService auditService) {
        this.auditService = auditService;
    }

    /** 当前用户工具调用统计 */
    @GetMapping("/api/usage/tools")
    public List<Map<String, Object>> myToolStats(
            @RequestParam(defaultValue = "7") int days,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return auditService.toolStats(userId, days);
    }

    /** 当前用户工作流运行统计 */
    @GetMapping("/api/usage/workflows")
    public List<Map<String, Object>> myWorkflowStats(
            @RequestParam(defaultValue = "7") int days,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return auditService.workflowStats(userId, days);
    }

    /** 管理员：租户级工具调用统计（可按用户过滤） */
    @GetMapping("/api/admin/audit/tools")
    public List<Map<String, Object>> adminToolStats(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) Long userId,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long operatorId
    ) {
        return auditService.adminToolStats(operatorId, userId, days);
    }

    /** 管理员：工具调用明细 */
    @GetMapping("/api/admin/audit/tools/logs")
    public List<Map<String, Object>> adminToolLogs(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) Long userId,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long operatorId
    ) {
        return auditService.adminToolLogs(operatorId, userId, days, limit);
    }
}

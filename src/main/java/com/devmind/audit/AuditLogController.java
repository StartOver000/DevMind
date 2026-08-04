package com.devmind.audit;

import com.devmind.audit.dto.AuditLogListResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public AuditLogListResponse list(
            @RequestParam(defaultValue = "1") Long userId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return auditLogService.listByUser(userId, limit);
    }
}

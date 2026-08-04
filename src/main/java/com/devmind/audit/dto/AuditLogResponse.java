package com.devmind.audit.dto;

import java.time.OffsetDateTime;

public record AuditLogResponse(
        Long id,
        Long userId,
        String action,
        String targetType,
        Long targetId,
        String detail,
        Long teamId,
        OffsetDateTime createdTime
) {
}

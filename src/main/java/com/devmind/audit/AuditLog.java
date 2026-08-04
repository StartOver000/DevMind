package com.devmind.audit;

import java.time.OffsetDateTime;

public record AuditLog(
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

package com.devmind.user;

import java.time.OffsetDateTime;

public record User(
        Long id,
        Long tenantId,
        String username,
        String displayName,
        String role,
        OffsetDateTime createdTime
) {
}

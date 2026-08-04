package com.devmind.auth;

import java.time.OffsetDateTime;

public record AuthToken(
        Long id,
        Long userId,
        String token,
        OffsetDateTime expiresAt,
        OffsetDateTime createdTime
) {
}

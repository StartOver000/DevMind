package com.devmind.user;

import java.time.OffsetDateTime;

public record User(
        Long id,
        String username,
        String displayName,
        String role,
        OffsetDateTime createdTime
) {
}

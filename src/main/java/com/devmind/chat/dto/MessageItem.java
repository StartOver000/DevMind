package com.devmind.chat.dto;

import java.time.OffsetDateTime;

public record MessageItem(
        String role,
        String content,
        OffsetDateTime createdTime
) {
}

package com.devmind.chat;

import java.time.OffsetDateTime;

public record ChatMessage(
        Long id,
        Long conversationId,
        String role,
        String content,
        Integer promptTokens,
        Integer completionTokens,
        OffsetDateTime createdTime
) {
}

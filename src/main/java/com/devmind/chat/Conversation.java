package com.devmind.chat;

import java.time.OffsetDateTime;

public record Conversation(
        Long id,
        Long knowledgeBaseId,
        Long userId,
        String title,
        OffsetDateTime createdTime,
        OffsetDateTime updatedTime
) {
}

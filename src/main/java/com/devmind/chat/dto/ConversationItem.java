package com.devmind.chat.dto;

import java.time.OffsetDateTime;

public record ConversationItem(
        Long id,
        Long knowledgeBaseId,
        String title,
        OffsetDateTime createdTime,
        OffsetDateTime updatedTime
) {
}

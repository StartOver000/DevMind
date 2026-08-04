package com.devmind.knowledge;

import java.time.OffsetDateTime;

public record KnowledgeBaseMember(
        Long knowledgeBaseId,
        Long userId,
        String role,
        OffsetDateTime createdTime
) {
}

package com.devmind.knowledge;

public record KnowledgeBaseItem(
        Long id,
        String name,
        String status,
        Long documentCount,
        Long teamId
) {
}

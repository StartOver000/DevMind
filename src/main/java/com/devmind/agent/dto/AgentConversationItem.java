package com.devmind.agent.dto;

import java.time.OffsetDateTime;

public record AgentConversationItem(
        Long id,
        String title,
        OffsetDateTime createdTime
) {
}

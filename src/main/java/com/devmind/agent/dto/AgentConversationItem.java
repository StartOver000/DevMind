package com.devmind.agent.dto;

import java.time.LocalDateTime;

public record AgentConversationItem(
        Long id,
        String title,
        LocalDateTime createdTime
) {
}

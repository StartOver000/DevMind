package com.devmind.agent.dto;

import java.time.OffsetDateTime;

/** Agent 会话消息（记忆持久化/历史展示用） */
public record AgentMessage(
        String role,
        String content,
        OffsetDateTime createdTime
) {
}

package com.devmind.agent.dto;

/** Agent 会话消息（记忆持久化/历史展示用） */
public record AgentMessage(
        String role,
        String content
) {
}

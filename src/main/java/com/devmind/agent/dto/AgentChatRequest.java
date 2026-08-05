package com.devmind.agent.dto;

import java.util.List;

public record AgentChatRequest(
        /** 0 或 null = 新建会话 */
        Long conversationId,
        String question,
        List<HistoryItem> history
) {
    public record HistoryItem(String role, String content) {
    }
}

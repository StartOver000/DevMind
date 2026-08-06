package com.devmind.agent.dto;

import java.util.List;

public record AgentChatRequest(
        /** 0 或 null = 新建会话 */
        Long conversationId,
        String question,
        List<HistoryItem> history,
        /** 可选：本次对话携带的上传文件 id（文件文本注入为上下文） */
        List<String> fileIds
) {
    public AgentChatRequest(Long conversationId, String question, List<HistoryItem> history) {
        this(conversationId, question, history, null);
    }

    public record HistoryItem(String role, String content) {
    }
}

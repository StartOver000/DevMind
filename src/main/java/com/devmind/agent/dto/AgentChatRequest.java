package com.devmind.agent.dto;

import java.util.List;

public record AgentChatRequest(
        /** 0 或 null = 新建会话 */
        Long conversationId,
        String question,
        List<HistoryItem> history,
        /** 可选：本次对话携带的上传文件 id（文件文本注入为上下文） */
        List<String> fileIds,
        /**
         * 任务模式（G1）：READ_ONLY 时本次对话的写工具在执行前被授权策略拒绝；
         * 空 / 未知按 WRITE 处理（保持既有行为）。
         */
        String taskMode
) {
    public AgentChatRequest(Long conversationId, String question, List<HistoryItem> history, List<String> fileIds) {
        this(conversationId, question, history, fileIds, null);
    }

    public AgentChatRequest(Long conversationId, String question, List<HistoryItem> history) {
        this(conversationId, question, history, null, null);
    }

    public record HistoryItem(String role, String content) {
    }
}

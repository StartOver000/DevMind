package com.devmind.agent.dto;

import com.devmind.chat.dto.Reference;

import java.util.List;

public record AgentChatResponse(
        Long conversationId,
        String answer,
        List<Reference> references,
        List<ToolTraceItem> toolTrace
) {
}

package com.devmind.chat.dto;

import java.util.List;

public record ChatResponse(
        Long conversationId,
        String answer,
        List<Reference> references
) {
}

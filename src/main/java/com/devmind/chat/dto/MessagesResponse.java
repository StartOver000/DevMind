package com.devmind.chat.dto;

import java.util.List;

public record MessagesResponse(
        Long conversationId,
        List<MessageItem> messages
) {
}

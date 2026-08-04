package com.devmind.chat.dto;

import java.util.List;

public record ConversationListResponse(List<ConversationItem> items) {
}

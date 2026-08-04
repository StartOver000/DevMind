package com.devmind.chat;

import com.devmind.chat.dto.AggregateChatRequest;
import com.devmind.chat.dto.ChatRequest;
import com.devmind.chat.dto.ChatResponse;
import com.devmind.chat.dto.ConversationListResponse;
import com.devmind.chat.dto.MessagesResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/knowledge-bases/{knowledgeBaseId}/chat")
    public ChatResponse chat(
            @PathVariable Long knowledgeBaseId,
            @Valid @RequestBody ChatRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return chatService.chat(knowledgeBaseId, request, userId);
    }

    @PostMapping("/chat/aggregate")
    public ChatResponse chatAcross(
            @Valid @RequestBody AggregateChatRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return chatService.chatAcrossKnowledgeBases(request, userId);
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public MessagesResponse messages(
            @PathVariable Long conversationId,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return chatService.messages(conversationId, userId);
    }

    @GetMapping("/conversations")
    public ConversationListResponse conversations(
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return chatService.listConversations(userId, limit);
    }

    @DeleteMapping("/conversations/{conversationId}")
    public void deleteConversation(
            @PathVariable Long conversationId,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        chatService.deleteConversation(conversationId, userId);
    }
}

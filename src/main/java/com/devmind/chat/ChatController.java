package com.devmind.chat;

import com.devmind.chat.dto.AggregateChatRequest;
import com.devmind.chat.dto.ChatRequest;
import com.devmind.chat.dto.ChatResponse;
import com.devmind.chat.dto.ConversationListResponse;
import com.devmind.chat.dto.MessagesResponse;
import com.devmind.common.SsePusher;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService chatService;
    private final SsePusher ssePusher;

    public ChatController(ChatService chatService, SsePusher ssePusher) {
        this.chatService = chatService;
        this.ssePusher = ssePusher;
    }

    @PostMapping("/knowledge-bases/{knowledgeBaseId}/chat")
    public ChatResponse chat(
            @PathVariable Long knowledgeBaseId,
            @Valid @RequestBody ChatRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return chatService.chat(knowledgeBaseId, request, userId);
    }

    /**
     * SSE 流式问答：
     * - event: meta   —— 会话 ID + 引用来源
     * - event: delta  —— 回答文本分块（纯文本）
     * - event: done   —— 结束
     * - event: error  —— 出错
     */
    @PostMapping("/knowledge-bases/{knowledgeBaseId}/chat/stream")
    public SseEmitter chatStream(
            @PathVariable Long knowledgeBaseId,
            @Valid @RequestBody ChatRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        SseEmitter emitter = ssePusher.createEmitter();
        ssePusher.async(emitter, () -> {
            try {
                runStream(emitter, knowledgeBaseId, request, userId);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        return emitter;
    }

    private void runStream(SseEmitter emitter, Long knowledgeBaseId, ChatRequest request, Long userId)
            throws Exception {
        // 真流式（token 级）：检索与会话准备 → 发 meta（引用来源）→ 模型 token 流逐块推送
        ChatService.StreamSession session = chatService.prepareStream(knowledgeBaseId, request, userId);
        ssePusher.sendJson(emitter, "meta", Map.of(
                "conversationId", session.conversationId() == null ? 0L : session.conversationId(),
                "references", session.references() == null ? List.of() : session.references()
        ));
        chatService.streamAnswer(session, chunk -> {
            try {
                ssePusher.sendDelta(emitter, chunk);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        ssePusher.sendJson(emitter, "done", Map.of("ok", true));
        emitter.complete();
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

package com.devmind.agent;

import com.devmind.agent.dto.AgentChatRequest;
import com.devmind.agent.dto.AgentChatResponse;
import com.devmind.agent.dto.AgentConversationItem;
import com.devmind.agent.dto.AgentMessage;
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

import java.util.List;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agentService;
    private final AgentConversationRepository conversationRepository;

    public AgentController(AgentService agentService, AgentConversationRepository conversationRepository) {
        this.agentService = agentService;
        this.conversationRepository = conversationRepository;
    }

    @PostMapping("/chat")
    public AgentChatResponse chat(
            @Valid @RequestBody AgentChatRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return agentService.chat(request, userId);
    }

    @GetMapping("/conversations")
    public List<AgentConversationItem> conversations(
            @RequestParam(defaultValue = "20") int limit,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return conversationRepository.listByUser(userId, limit);
    }

    @GetMapping("/conversations/{id}/messages")
    public List<AgentMessage> messages(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return agentService.messages(id, userId);
    }

    @DeleteMapping("/conversations/{id}")
    public void deleteConversation(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        conversationRepository.delete(id, userId);
    }
}

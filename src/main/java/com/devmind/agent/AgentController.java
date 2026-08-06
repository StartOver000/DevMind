package com.devmind.agent;

import com.devmind.agent.dto.AgentChatRequest;
import com.devmind.agent.dto.AgentChatResponse;
import com.devmind.agent.dto.AgentConversationItem;
import com.devmind.agent.dto.AgentMessage;
import com.devmind.agent.dto.MemoryItem;
import com.devmind.agent.dto.MemoryUpdateRequest;
import com.devmind.agent.dto.ToolTraceItem;
import com.devmind.common.SsePusher;
import com.devmind.common.StreamingChunkSplitter;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    /** 分片推送间隔（毫秒） */
    private static final long DELTA_INTERVAL_MS = 40L;

    private final AgentService agentService;
    private final AgentConversationRepository conversationRepository;
    private final SsePusher ssePusher;

    public AgentController(
            AgentService agentService,
            AgentConversationRepository conversationRepository,
            SsePusher ssePusher
    ) {
        this.agentService = agentService;
        this.conversationRepository = conversationRepository;
        this.ssePusher = ssePusher;
    }

    @PostMapping("/chat")
    public AgentChatResponse chat(
            @Valid @RequestBody AgentChatRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return agentService.chat(request, userId);
    }

    /**
     * Agent SSE 流式问答：
     * - event: thinking —— 模型原生思考过程（reasoning，实时）
     * - event: trace  —— 工具执行轨迹（实时，工具完成即推）
     * - event: delta  —— 最终回答文本分块
     * - event: done   —— 结束（含会话 ID + 完整轨迹）
     * - event: error  —— 出错
     */
    @PostMapping("/chat/stream")
    public SseEmitter chatStream(
            @Valid @RequestBody AgentChatRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        SseEmitter emitter = ssePusher.createEmitter();
        ssePusher.async(emitter, () -> {
            try {
                runStream(emitter, request, userId);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        return emitter;
    }

    private void runStream(SseEmitter emitter, AgentChatRequest request, Long userId) throws Exception {
        AgentChatResponse response = agentService.chatStream(request, userId, item -> {
            try {
                ssePusher.sendJson(emitter, "trace", item);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }, thinking -> {
            try {
                ssePusher.sendJson(emitter, "thinking", Map.of("text", thinking));
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        for (String chunk : StreamingChunkSplitter.split(response.answer())) {
            ssePusher.sendDelta(emitter, chunk);
            Thread.sleep(DELTA_INTERVAL_MS);
        }
        ssePusher.sendJson(emitter, "done", Map.of(
                "conversationId", response.conversationId() == null ? 0L : response.conversationId(),
                "trace", response.toolTrace() == null ? List.of() : response.toolTrace()
        ));
        emitter.complete();
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

    @GetMapping("/conversations/{id}/trace")
    public List<ToolTraceItem> trace(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return agentService.trace(id, userId);
    }

    @DeleteMapping("/conversations/{id}")
    public void deleteConversation(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        conversationRepository.delete(id, userId);
    }

    // ---- 长期记忆 ----

    @GetMapping("/memory")
    public List<MemoryItem> memory(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return agentService.memory(userId);
    }

    @PutMapping("/memory")
    public void updateMemory(
            @RequestBody MemoryUpdateRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        agentService.updateMemory(request, userId);
    }

    @DeleteMapping("/memory/{id}")
    public void deleteMemory(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        agentService.deleteMemory(id, userId);
    }
}

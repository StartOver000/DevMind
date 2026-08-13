package com.devmind.agent;

import com.devmind.agent.dto.AgentMessage;
import com.devmind.agent.dto.ToolTraceItem;
import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Agent 会话与工具轨迹持久化（P2 拆分：从 AgentService 抽出，职责单一）。
 * 负责会话创建/复用、消息保存、工具轨迹记录与历史查询。
 */
public class AgentConversationStore {

    private static final Logger log = LoggerFactory.getLogger(AgentConversationStore.class);

    private final AgentConversationRepository conversationRepository;
    private final UserService userService;

    public AgentConversationStore(AgentConversationRepository conversationRepository, UserService userService) {
        this.conversationRepository = conversationRepository;
        this.userService = userService;
    }

    /** 查询会话消息（历史展示） */
    public List<AgentMessage> messages(Long conversationId, Long userId) {
        userService.requireUser(userId);
        if (conversationId == null || !conversationRepository.existsForUser(conversationId, userId)) {
            throw new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "会话不存在");
        }
        return conversationRepository.listMessages(conversationId);
    }

    /** 查询会话工具调用轨迹（历史展示） */
    public List<ToolTraceItem> trace(Long conversationId, Long userId) {
        userService.requireUser(userId);
        if (conversationId == null || !conversationRepository.existsForUser(conversationId, userId)) {
            throw new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "会话不存在");
        }
        return conversationRepository.listTraces(conversationId);
    }

    /** 保存一问一答（user + assistant），失败不影响主流程 */
    public void saveMessages(Long conversationId, String question, String answer) {
        if (conversationId == null || conversationId <= 0) {
            return;
        }
        try {
            conversationRepository.saveMessage(conversationId, "user", question);
            conversationRepository.saveMessage(conversationId, "assistant", answer == null ? "" : answer);
        } catch (Exception ex) {
            log.warn("agent 消息持久化失败: {}", ex.getMessage());
        }
    }

    /** 记录工具调用轨迹，失败不影响主流程 */
    public void persistTrace(Long conversationId, String tool, String args, boolean ok, long costMs) {
        if (conversationId == null || conversationId <= 0) {
            return;
        }
        try {
            conversationRepository.saveTrace(conversationId, tool, args, ok, costMs);
        } catch (Exception ex) {
            log.warn("agent 轨迹持久化失败: {}", ex.getMessage());
        }
    }

    /** 解析/创建会话：传入有效会话 ID 则复用，否则按问题标题新建 */
    public Long resolveConversation(Long conversationId, String question, Long userId) {
        if (conversationId != null && conversationId > 0) {
            return conversationId;
        }
        String title = AgentTools.truncate(question, 100);
        Long id = conversationRepository.create(userId, title);
        return id == null ? 0L : id;
    }
}

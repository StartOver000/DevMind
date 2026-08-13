package com.devmind.agent;

import com.devmind.agent.dto.MemoryItem;
import com.devmind.agent.dto.MemoryUpdateRequest;
import com.devmind.ai.AiModelGateway;
import com.devmind.ai.ChatRouter;
import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Agent 长期记忆（P2 拆分：提取 + CRUD 从 AgentService 抽出，职责单一）。
 * 会话结束后自动提取用户偏好，提供查询/覆盖/删除。
 */
public class AgentMemoryManager {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryManager.class);

    /** 每次会话结束自动提取长期记忆的最大条数 */
    private static final int MAX_EXTRACT_ITEMS = 5;
    private static final int MAX_MEMORY_KEY_CHARS = 20;
    private static final int MAX_MEMORY_VALUE_CHARS = 100;

    /** 会话结束后自动提取用户长期偏好的提取器提示词 */
    private static final String MEMORY_EXTRACT_PROMPT = """
            你是一个用户偏好提取器。根据用户与 AI 助手的对话，提取用户明确表达的长期偏好或关键事实。
            规则：
            1. 只提取用户明确表达的内容（如使用的技术栈、语言偏好、回答风格要求、常用工具等），不要猜测或推断。
            2. 每行一条，格式：key: value。key 简短（不超过 20 字），value 为具体内容（不超过 100 字）。
            3. 没有可提取的内容时输出空。
            """;

    private final AgentMemoryRepository memoryRepository;
    private final ChatRouter chatRouter;
    private final UserService userService;

    public AgentMemoryManager(
            AgentMemoryRepository memoryRepository,
            ChatRouter chatRouter,
            UserService userService
    ) {
        this.memoryRepository = memoryRepository;
        this.chatRouter = chatRouter;
        this.userService = userService;
    }

    /**
     * 会话结束后自动从对话中提取用户长期偏好，合并写入 agent_memory。
     * 完全静默：模型不可用（429/熔断/降级）或输出解析失败都不影响主流程。
     */
    public void extractMemory(Long userId, String question, String answer) {
        if (userId == null) {
            return;
        }
        try {
            String dialogue = "用户：" + (question == null ? "" : question)
                    + "\n助手：" + (answer == null ? "" : answer);
            AiModelGateway.ChatResult result = chatRouter.chat(MEMORY_EXTRACT_PROMPT, dialogue);
            String content = result == null ? "" : result.content();
            if (content == null || content.isBlank()) {
                return;
            }
            int count = 0;
            for (String line : content.split("\\n")) {
                String trimmed = line.trim();
                int idx = trimmed.indexOf(':');
                if (idx <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, idx).trim();
                String value = trimmed.substring(idx + 1).trim();
                if (key.isEmpty() || value.isEmpty()) {
                    continue;
                }
                if (key.length() > MAX_MEMORY_KEY_CHARS) {
                    key = key.substring(0, MAX_MEMORY_KEY_CHARS);
                }
                if (value.length() > MAX_MEMORY_VALUE_CHARS) {
                    value = value.substring(0, MAX_MEMORY_VALUE_CHARS);
                }
                memoryRepository.upsert(userId, key, value);
                count++;
                if (count >= MAX_EXTRACT_ITEMS) {
                    break;
                }
            }
            if (count > 0) {
                log.info("agent 自动提取长期记忆 {} 条（user={}）", count, userId);
            }
        } catch (Exception ex) {
            log.warn("agent 长期记忆自动提取失败（不影响主流程）: {}", ex.getMessage());
        }
    }

    /** 查询长期记忆 */
    public List<MemoryItem> memory(Long userId) {
        userService.requireUser(userId);
        return memoryRepository.listByUser(userId);
    }

    /** 更新长期记忆（全量覆盖） */
    public void updateMemory(MemoryUpdateRequest request, Long userId) {
        userService.requireUser(userId);
        memoryRepository.replaceAll(userId, request == null ? List.of() : request.items());
    }

    /** 删除单条长期记忆（可追溯记忆：按 id 删除；不存在时抛错提示） */
    public void deleteMemory(Long id, Long userId) {
        userService.requireUser(userId);
        if (id == null || id <= 0) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "缺少有效的记忆 ID");
        }
        int affected = memoryRepository.deleteById(userId, id);
        if (affected == 0) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "记忆不存在或无权删除: " + id);
        }
    }
}

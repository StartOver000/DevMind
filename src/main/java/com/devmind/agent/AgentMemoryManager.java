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

    /**
     * 分层记忆选择（P2-1）：核心记忆（source=manual，用户明确编辑）常驻全量注入；
     * 场景记忆（source=auto，自动提取）按当前问题关键词筛选——避免全量注入导致
     * 无关偏好稀释当前任务；场景记忆无命中时回退全量（保证用户偏好不漏）。
     */
    public List<MemoryItem> selectForQuestion(List<MemoryItem> memory, String question) {
        if (memory == null || memory.isEmpty()) {
            return List.of();
        }
        List<MemoryItem> core = new java.util.ArrayList<>();
        List<MemoryItem> context = new java.util.ArrayList<>();
        for (MemoryItem m : memory) {
            if ("manual".equals(m.source())) {
                core.add(m);
            } else {
                context.add(m);
            }
        }
        if (context.isEmpty() || question == null || question.isBlank()) {
            return List.copyOf(core.isEmpty() ? context : core);
        }
        // 关键词抽取：英文按空白/标点分词（长度>=2）；中文用 2-gram（覆盖中文无空格分词）
        String q = question.toLowerCase();
        java.util.Set<String> keywords = new java.util.HashSet<>();
        for (String token : q.split("[\\s\\p{Punct}]+")) {
            String t = token.trim();
            if (t.length() >= 2) {
                keywords.add(t);
            }
        }
        // 中文补充：连续中文片段按 2-gram 切，让"订单系统"能匹配"查订单"
        StringBuilder chinese = new StringBuilder();
        for (int i = 0; i < q.length(); i++) {
            char c = q.charAt(i);
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                chinese.append(c);
            } else {
                flushChineseGrams(chinese, keywords);
                chinese.setLength(0);
            }
        }
        flushChineseGrams(chinese, keywords);
        List<MemoryItem> matched = new java.util.ArrayList<>();
        for (MemoryItem m : context) {
            String hay = ((m.key() == null ? "" : m.key()) + " " + (m.value() == null ? "" : m.value())).toLowerCase();
            boolean hit = keywords.stream().anyMatch(hay::contains);
            if (hit) {
                matched.add(m);
            }
        }
        if (matched.isEmpty()) {
            // 无命中回退全量（含核心 + 场景），保证偏好不被遗漏
            return List.copyOf(memory);
        }
        java.util.ArrayList<MemoryItem> result = new java.util.ArrayList<>(core);
        result.addAll(matched);
        return List.copyOf(result);
    }

    /** 把连续中文片段按 2-gram 切分加入关键词（如"查订单"→"查订""订单"） */
    private static void flushChineseGrams(StringBuilder chinese, java.util.Set<String> keywords) {
        if (chinese.length() < 2) {
            return;
        }
        String seq = chinese.toString();
        for (int i = 0; i + 2 <= seq.length(); i++) {
            keywords.add(seq.substring(i, i + 2));
        }
        // 整段也加入（长度>2 时覆盖更长匹配）
        keywords.add(seq);
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

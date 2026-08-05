package com.devmind.agent;

import com.devmind.agent.dto.AgentChatRequest;
import com.devmind.agent.dto.AgentChatResponse;
import com.devmind.agent.dto.AgentMessage;
import com.devmind.agent.dto.MemoryUpdateRequest;
import com.devmind.agent.dto.ToolTraceItem;
import com.devmind.ai.AiModelGateway;
import com.devmind.ai.ChatRouter;
import com.devmind.chat.dto.Reference;
import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.config.DevMindProperties;
import com.devmind.knowledge.KnowledgeBaseService;
import com.devmind.knowledge.dto.KnowledgeBaseListResponse;
import com.devmind.modelusage.ModelUsageService;
import com.devmind.retrieval.LocalRagAnswerer;
import com.devmind.retrieval.RetrievalResult;
import com.devmind.retrieval.RetrievalService;
import com.devmind.user.UserService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 研发问答 Agent：ReAct 式执行循环。
 * 模型自主调用工具（kb_search / sql_diagnose 等）→ 工具结果回填 → 再决策，直到输出最终回答。
 * 韧性：模型调用走 {@link ChatRouter}（超时/熔断/降级）；单工具失败回填错误不中断；
 * 全链路失败降级为本地 RAG 回答。
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    /** 最大工具调用轮数（防死循环） */
    private static final int MAX_TOOL_ROUNDS = 3;
    /** 工具结果回填给模型的最大字符数 */
    private static final int MAX_TOOL_RESULT_CHARS = 2000;
    /** 每次会话结束自动提取长期记忆的最大条数 */
    private static final int MAX_EXTRACT_ITEMS = 5;
    private static final int MAX_MEMORY_KEY_CHARS = 20;
    private static final int MAX_MEMORY_VALUE_CHARS = 100;

    private static final String SYSTEM_PROMPT = """
            你是 DevMind 研发助手 Agent。根据用户问题自主决定调用哪些工具获取信息，再给出最终回答。

            可调用工具：
            - kb_search：检索研发知识库，获取与问题相关的文档片段（含相似度分数）。
            - kb_info：查询当前用户可访问的知识库列表。
            - doc_list：查询指定知识库内的文档清单（文件名、状态、文本块数）。
            - sql_diagnose：分析 SQL 执行计划，识别风险并给出优化建议。
            - usage_query：查询当前用户的模型用量与估算费用。

            规则：
            1. 先调用需要的工具，拿到结果后再回答；不要编造工具结果。
            2. 多维度问题（如 SQL 性能 + 优化方案）可依次调用多个工具。
            3. 工具结果不足以回答时，明确说明。
            4. 最终回答需引用来源文件名，格式 [来源: 文件名]。
            """;

    /** 会话结束后自动提取用户长期偏好的提取器提示词 */
    private static final String MEMORY_EXTRACT_PROMPT = """
            你是一个用户偏好提取器。根据用户与 AI 助手的对话，提取用户明确表达的长期偏好或关键事实。
            规则：
            1. 只提取用户明确表达的内容（如使用的技术栈、语言偏好、回答风格要求、常用工具等），不要猜测或推断。
            2. 每行一条，格式：key: value。key 简短（不超过 20 字），value 为具体内容（不超过 100 字）。
            3. 没有可提取的内容时输出空。
            """;

    private final ChatRouter chatRouter;
    private final ToolRegistry toolRegistry;
    private final AgentConversationRepository conversationRepository;
    private final AgentMemoryRepository memoryRepository;
    private final UserService userService;
    private final ModelUsageService modelUsageService;
    private final AiModelGateway modelGateway;
    private final RetrievalService retrievalService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final DevMindProperties properties;
    private final MeterRegistry meterRegistry;

    public AgentService(
            ChatRouter chatRouter,
            ToolRegistry toolRegistry,
            AgentConversationRepository conversationRepository,
            AgentMemoryRepository memoryRepository,
            UserService userService,
            ModelUsageService modelUsageService,
            AiModelGateway modelGateway,
            RetrievalService retrievalService,
            KnowledgeBaseService knowledgeBaseService,
            DevMindProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.chatRouter = chatRouter;
        this.toolRegistry = toolRegistry;
        this.conversationRepository = conversationRepository;
        this.memoryRepository = memoryRepository;
        this.userService = userService;
        this.modelUsageService = modelUsageService;
        this.modelGateway = modelGateway;
        this.retrievalService = retrievalService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    public AgentChatResponse chat(AgentChatRequest request, Long userId) {
        return doChat(request, userId, null);
    }

    /**
     * Agent 流式入口：与 {@link #chat} 逻辑一致，但每次工具执行完成时通过
     * {@code onTrace} 实时回调（供 SSE 推送工具轨迹），最终答案由调用方分片推送。
     */
    public AgentChatResponse chatStream(
            AgentChatRequest request,
            Long userId,
            Consumer<ToolTraceItem> onTrace
    ) {
        return doChat(request, userId, onTrace);
    }

    private AgentChatResponse doChat(
            AgentChatRequest request,
            Long userId,
            Consumer<ToolTraceItem> onTrace
    ) {
        userService.requireUser(userId);
        String question = request.question() == null ? "" : request.question().trim();
        if (question.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "问题不能为空");
        }

        Long conversationId = resolveConversation(request.conversationId(), question, userId);

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        // 长期记忆：注入用户偏好（跨会话保留）
        List<com.devmind.agent.dto.MemoryItem> memory = memoryRepository.listByUser(userId);
        if (memory != null && !memory.isEmpty()) {
            String memoryText = memory.stream()
                    .map(m -> m.key() + ": " + m.value())
                    .collect(java.util.stream.Collectors.joining("；"));
            messages.add(Map.of("role", "system", "content", "【用户长期记忆】" + memoryText + "（回答时可参考这些用户偏好）"));
        }
        // 多轮记忆：加载该会话最近的历史消息作为上下文
        if (conversationId != null && conversationId > 0) {
            List<AgentMessage> history = conversationRepository.listMessages(conversationId);
            if (history != null && !history.isEmpty()) {
                int from = Math.max(0, history.size() - 6);
                for (int i = from; i < history.size(); i++) {
                    AgentMessage historyItem = history.get(i);
                    messages.add(Map.of("role", historyItem.role(), "content", historyItem.content()));
                }
            }
        }
        if (request.history() != null) {
            for (AgentChatRequest.HistoryItem item : request.history()) {
                messages.add(Map.of("role", item.role(), "content", item.content()));
            }
        }
        messages.add(Map.of("role", "user", "content", question));

        List<ToolTraceItem> trace = new ArrayList<>();
        List<AiModelGateway.ToolSpec> tools = toolRegistry.all().stream()
                .map(tool -> new AiModelGateway.ToolSpec(tool.name(), tool.description(), tool.parametersJsonSchema()))
                .toList();

        try {
            for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
                AiModelGateway.ChatResult result = chatRouter.chatWithTools(SYSTEM_PROMPT, messages, tools);
                recordUsage(userId, result, question);
                List<AiModelGateway.ToolCall> toolCalls = result.toolCalls();
                if (toolCalls == null || toolCalls.isEmpty()) {
                    String answer = result.content() == null ? "" : result.content();
                    saveMessages(conversationId, question, answer);
                    extractMemory(userId, question, answer);
                    return new AgentChatResponse(conversationId, answer, List.of(), trace);
                }
                // 回填 assistant（含 tool_calls）
                Map<String, Object> assistantMsg = new LinkedHashMap<>();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", result.content() == null ? "" : result.content());
                assistantMsg.put("tool_calls", toolCalls.stream()
                        .map(tc -> Map.of(
                                "id", tc.id(),
                                "type", "function",
                                "function", Map.of("name", tc.name(), "arguments", tc.argumentsJson())
                        ))
                        .toList());
                messages.add(assistantMsg);
                // 逐个执行工具
                for (AiModelGateway.ToolCall tc : toolCalls) {
                    long start = System.currentTimeMillis();
                    String output;
                    boolean ok;
                    try {
                        output = toolRegistry.execute(tc.name(), tc.argumentsJson(), userId);
                        ok = true;
                    } catch (Exception ex) {
                        log.warn("agent 工具 {} 执行失败: {}", tc.name(), ex.getMessage());
                        output = "{\"error\": \"工具执行失败: " + ex.getMessage() + "\"}";
                        ok = false;
                    }
                    output = truncate(output, MAX_TOOL_RESULT_CHARS);
                    messages.add(Map.of(
                            "role", "tool",
                            "tool_call_id", tc.id(),
                            "content", output
                    ));
                    long costMs = System.currentTimeMillis() - start;
                    ToolTraceItem item = new ToolTraceItem(
                            tc.name(),
                            truncate(tc.argumentsJson(), 120),
                            ok,
                            costMs
                    );
                    trace.add(item);
                    if (onTrace != null) {
                        onTrace.accept(item);
                    }
                    persistTrace(conversationId, tc.name(), truncate(tc.argumentsJson(), 200), ok, costMs);
                }
            }
            throw new ApiException(ErrorCode.MODEL_CALL_FAILED, "Agent 工具调用轮数超限");
        } catch (Exception ex) {
            log.warn("agent 链路失败，降级本地 RAG: {}", ex.getMessage());
            AgentChatResponse fallback = fallbackToLocalRag(conversationId, question, userId, trace);
            saveMessages(conversationId, question, fallback.answer());
            return fallback;
        }
    }

    /**
     * 会话结束后自动从对话中提取用户长期偏好，合并写入 agent_memory。
     * 完全静默：模型不可用（429/熔断/降级）或输出解析失败都不影响主流程。
     */
    private void extractMemory(Long userId, String question, String answer) {
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

    /** 查询长期记忆 */
    public List<com.devmind.agent.dto.MemoryItem> memory(Long userId) {
        userService.requireUser(userId);
        return memoryRepository.listByUser(userId);
    }

    /** 更新长期记忆（全量覆盖） */
    public void updateMemory(MemoryUpdateRequest request, Long userId) {
        userService.requireUser(userId);
        memoryRepository.replaceAll(userId, request == null ? List.of() : request.items());
    }

    private void saveMessages(Long conversationId, String question, String answer) {
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

    private void persistTrace(Long conversationId, String tool, String args, boolean ok, long costMs) {
        if (conversationId == null || conversationId <= 0) {
            return;
        }
        try {
            conversationRepository.saveTrace(conversationId, tool, args, ok, costMs);
        } catch (Exception ex) {
            log.warn("agent 轨迹持久化失败: {}", ex.getMessage());
        }
    }

    private Long resolveConversation(Long conversationId, String question, Long userId) {
        if (conversationId != null && conversationId > 0) {
            return conversationId;
        }
        String title = truncate(question, 100);
        Long id = conversationRepository.create(userId, title);
        return id == null ? 0L : id;
    }

    private AgentChatResponse fallbackToLocalRag(
            Long conversationId,
            String question,
            Long userId,
            List<ToolTraceItem> trace
    ) {
        meterRegistry.counter("devmind.rag.degraded").increment();
        try {
            Long kbId = firstAccessibleKnowledgeBase(userId);
            if (kbId == null) {
                return new AgentChatResponse(conversationId, "Agent 暂不可用，且没有可访问的知识库。", List.of(), trace);
            }
            List<RetrievalResult> results = searchWithFallback(kbId, question);
            if (results.isEmpty()) {
                return new AgentChatResponse(conversationId, "知识库中没有找到足够相关内容。", List.of(), trace);
            }
            String answer = LocalRagAnswerer.answer(question, results) + "\n\n（本地降级模式）";
            List<Reference> refs = results.stream()
                    .map(r -> new Reference(
                            r.documentId(),
                            r.documentName(),
                            r.chunkId(),
                            truncate(r.content(), 300),
                            round(r.similarityScore()),
                            r.metadata()
                    ))
                    .toList();
            return new AgentChatResponse(conversationId, answer, refs, trace);
        } catch (Exception ex) {
            log.error("agent 本地降级失败", ex);
            return new AgentChatResponse(conversationId, "Agent 暂不可用，请稍后重试。", List.of(), trace);
        }
    }

    private Long firstAccessibleKnowledgeBase(Long userId) {
        KnowledgeBaseListResponse list = knowledgeBaseService.list(userId);
        if (list.items() == null || list.items().isEmpty()) {
            return null;
        }
        // 优先选有文档的库，避免检索空库
        return list.items().stream()
                .filter(item -> "ENABLED".equals(item.status()) && item.documentCount() != null && item.documentCount() > 0)
                .findFirst()
                .map(item -> item.id())
                .orElseGet(() -> list.items().stream()
                        .filter(item -> "ENABLED".equals(item.status()))
                        .findFirst()
                        .map(item -> item.id())
                        .orElse(null));
    }

    private List<RetrievalResult> searchWithFallback(Long kbId, String question) {
        try {
            List<Double> vector = modelGateway.embed(List.of(question)).get(0);
            return retrievalService.searchHybrid(
                    kbId,
                    vector,
                    question,
                    properties.retrievalTopK(),
                    properties.retrievalMinScore(),
                    properties.retrievalVectorWeight(),
                    properties.retrievalKeywordWeight(),
                    properties.retrievalHybridEnabled()
            );
        } catch (Exception ex) {
            log.warn("agent 降级检索 embedding 不可用，走关键词检索: {}", ex.getMessage());
            return retrievalService.searchByKeywords(kbId, question, properties.retrievalTopK(), Map.of());
        }
    }

    private void recordUsage(Long userId, AiModelGateway.ChatResult result, String question) {
        try {
            modelUsageService.record(
                    userId,
                    "agent",
                    result.model(),
                    result.promptTokens(),
                    result.completionTokens(),
                    question,
                    result.content() == null ? "" : result.content()
            );
        } catch (Exception ex) {
            log.warn("agent 用量记录失败: {}", ex.getMessage());
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}

package com.devmind.chat;

import com.devmind.ai.AiModelGateway;
import com.devmind.ai.ChatRouter;
import com.devmind.audit.AuditLogService;
import com.devmind.chat.dto.AggregateChatRequest;
import com.devmind.chat.dto.ChatRequest;
import com.devmind.chat.dto.ChatResponse;
import com.devmind.chat.dto.ConversationItem;
import com.devmind.chat.dto.ConversationListResponse;
import com.devmind.chat.dto.MessageItem;
import com.devmind.chat.dto.MessagesResponse;
import com.devmind.chat.dto.Reference;
import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.config.DevMindProperties;
import com.devmind.knowledge.KnowledgeBaseService;
import com.devmind.modelusage.ModelUsageService;
import com.devmind.retrieval.LocalRagAnswerer;
import com.devmind.retrieval.QueryRewriter;
import com.devmind.retrieval.QueryRouter;
import com.devmind.retrieval.RerankService;
import com.devmind.retrieval.RetrievalResult;
import com.devmind.retrieval.RetrievalService;
import com.devmind.user.UserService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final String SYSTEM_PROMPT = """
            你是一个研发知识库助手。请优先依据提供的参考资料回答用户问题。
            如果参考资料不足以回答，请明确说明“知识库中没有足够信息”，不要编造结论。
            回答中尽量标注引用来源（文件名和标题），格式如 [来源: 文件名#标题]。
            """;

    /** 多轮上下文：最多携带最近 3 轮问答（6 条消息） */
    private static final int HISTORY_MESSAGE_LIMIT = 6;

    private final KnowledgeBaseService knowledgeBaseService;
    private final ChatRepository chatRepository;
    private final RetrievalService retrievalService;
    private final RerankService reranker;
    private final AiModelGateway modelGateway;
    private final ChatRouter chatRouter;
    private final AuditLogService auditLogService;
    private final ModelUsageService modelUsageService;
    private final UserService userService;
    private final DevMindProperties properties;
    private final MeterRegistry meterRegistry;
    private final ObservationRegistry observationRegistry;

    public ChatService(
            KnowledgeBaseService knowledgeBaseService,
            ChatRepository chatRepository,
            RetrievalService retrievalService,
            RerankService reranker,
            AiModelGateway modelGateway,
            ChatRouter chatRouter,
            AuditLogService auditLogService,
            ModelUsageService modelUsageService,
            UserService userService,
            DevMindProperties properties,
            MeterRegistry meterRegistry,
            ObservationRegistry observationRegistry
    ) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.chatRepository = chatRepository;
        this.retrievalService = retrievalService;
        this.reranker = reranker;
        this.modelGateway = modelGateway;
        this.chatRouter = chatRouter;
        this.auditLogService = auditLogService;
        this.modelUsageService = modelUsageService;
        this.userService = userService;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.observationRegistry = observationRegistry;
    }

    @Transactional
    public ChatResponse chat(Long knowledgeBaseId, ChatRequest request, Long userId) {
        knowledgeBaseService.requireEnabledKnowledgeBaseAccess(knowledgeBaseId, userId);
        String question = request.question().trim();
        int topK = request.topK() == null
                ? properties.retrievalTopK()
                : Math.min(Math.max(request.topK(), 1), properties.retrievalMaxTopK());

        Long conversationId = resolveConversation(knowledgeBaseId, question, request.conversationId(), userId);
        chatRepository.insertMessage(conversationId, "user", question, null, null);

        // 多轮上下文：加载最近历史（排除刚插入的当前问题），改写检索查询
        List<ChatMessage> history = recentHistory(conversationId);
        List<String> historyQuestions = history.stream()
                .filter(m -> "user".equals(m.role()))
                .map(ChatMessage::content)
                .toList();
        String searchQuery = QueryRewriter.rewrite(question, historyQuestions);
        QueryRouter.Route route = QueryRouter.route(searchQuery);

        List<RetrievalResult> results = Observation.createNotStarted("devmind.retrieval", observationRegistry)
                .observe(() -> searchWithFallback(
                        knowledgeBaseId,
                        searchQuery,
                        topK,
                        route.vectorWeight(),
                        route.keywordWeight(),
                        buildMetadataFilter(request.tags())
                ));

        String answer;
        if (results.isEmpty()) {
            answer = "知识库中没有找到足够相关内容。";
        } else {
            answer = Observation.createNotStarted("devmind.model.chat", observationRegistry)
                    .observe(() -> callModel(userId, question, results, history));
        }

        chatRepository.insertMessage(conversationId, "assistant", answer, null, null);
        auditLogService.log(userId, "CHAT", "knowledge_base", knowledgeBaseId, question);
        log.info("chat answered, knowledgeBaseId={}, questionLen={}, answerLen={}, refs={}, topK={}",
                knowledgeBaseId, question.length(), answer.length(), results.size(), topK);

        List<Reference> references = toReferences(results);
        return new ChatResponse(conversationId, answer, references);
    }

    private List<Reference> toReferences(List<RetrievalResult> results) {
        return results.stream()
                .map(result -> new Reference(
                        result.documentId(),
                        result.documentName(),
                        result.chunkId(),
                        result.content(),
                        round(result.similarityScore()),
                        result.metadata()
                ))
                .toList();
    }

    public ChatResponse chatAcrossKnowledgeBases(AggregateChatRequest request, Long userId) {
        List<Long> knowledgeBaseIds = request.knowledgeBaseIds().stream().distinct().toList();
        if (knowledgeBaseIds.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "请选择至少一个知识库");
        }
        String question = request.question().trim();
        for (Long id : knowledgeBaseIds) {
            knowledgeBaseService.requireEnabledKnowledgeBaseAccess(id, userId);
        }
        int topK = request.topK() == null
                ? properties.retrievalTopK()
                : Math.min(Math.max(request.topK(), 1), properties.retrievalMaxTopK());
        Map<String, Object> metadataFilter = buildMetadataFilter(request.tags());

        List<RetrievalResult> merged = new ArrayList<>();
        List<Double> queryVector = null;
        try {
            queryVector = modelGateway.embed(List.of(question)).get(0);
        } catch (Exception ex) {
            log.warn("embedding 不可用，跨库问答降级关键词检索: {}", ex.getMessage());
        }
        QueryRouter.Route route = QueryRouter.route(question);
        for (Long id : knowledgeBaseIds) {
            if (queryVector == null) {
                merged.addAll(retrievalService.searchByKeywords(id, question, topK * 2, metadataFilter));
            } else {
                merged.addAll(retrievalService.searchHybrid(
                        id,
                        queryVector,
                        question,
                        topK * 2,
                        properties.retrievalMinScore(),
                        route.vectorWeight(),
                        route.keywordWeight(),
                        properties.retrievalHybridEnabled(),
                        metadataFilter
                ));
            }
        }
        Set<Long> seen = new HashSet<>();
        List<RetrievalResult> deduped = merged.stream()
                .filter(result -> seen.add(result.chunkId()))
                .toList();
        List<RetrievalResult> top = reranker.rerank(question, deduped, topK);

        String answer = top.isEmpty()
                ? "知识库中没有找到足够相关内容。"
                : callModel(userId, question, top, List.of());
        return new ChatResponse(null, answer, toReferences(top));
    }

    public MessagesResponse messages(Long conversationId, Long userId) {
        Conversation conversation = chatRepository.findConversationById(conversationId)
                .orElseThrow(() -> new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "会话不存在"));
        knowledgeBaseService.requireKnowledgeBaseAccess(conversation.knowledgeBaseId(), userId);
        List<MessageItem> messages = chatRepository.listMessages(conversationId).stream()
                .map(message -> new MessageItem(message.role(), message.content(), message.createdTime()))
                .toList();
        return new MessagesResponse(conversation.id(), messages);
    }

    public ConversationListResponse listConversations(Long userId, int limit) {
        userService.requireUser(userId);
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        List<ConversationItem> items = chatRepository.listByUser(userId, safeLimit).stream()
                .map(c -> new ConversationItem(
                        c.id(),
                        c.knowledgeBaseId(),
                        c.title(),
                        c.createdTime(),
                        c.updatedTime()
                ))
                .toList();
        return new ConversationListResponse(items);
    }

    public void deleteConversation(Long conversationId, Long userId) {
        userService.requireUser(userId);
        if (!chatRepository.deleteConversation(conversationId, userId)) {
            throw new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "会话不存在");
        }
        auditLogService.log(userId, "DELETE_CONVERSATION", "chat_conversation", conversationId, null);
    }

    private Map<String, Object> buildMetadataFilter(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return Map.of();
        }
        List<String> cleaned = tags.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
        return cleaned.isEmpty() ? Map.of() : Map.of("tags", cleaned);
    }

    /**
     * 会话标题（G8 自动选档）：走便宜档生成一句话标题（简单任务，省成本）；
     * 便宜档未配置/生成失败/结果异常时回退截断——标题生成不影响主流程可用性。
     */
    private String buildTitle(String question) {
        try {
            AiModelGateway.ChatResult result = chatRouter.chatAuto(
                    "你是标题生成器。把用户问题压缩成 20 字以内的一句话标题，只输出标题本身，不要引号、不要解释。",
                    question);
            String title = result == null || result.content() == null ? "" : result.content().trim();
            if (!title.isEmpty() && title.length() <= 50) {
                return title;
            }
        } catch (Exception ex) {
            log.warn("会话标题生成失败，回退截断: {}", ex.getMessage());
        }
        return question.length() <= 50 ? question : question.substring(0, 50);
    }

    private Long resolveConversation(Long knowledgeBaseId, String question, Long conversationId, Long userId) {
        if (conversationId == null) {
            return chatRepository.createConversation(knowledgeBaseId, buildTitle(question), userId);
        }
        Conversation conversation = chatRepository.findConversationById(conversationId)
                .orElseThrow(() -> new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "会话不存在"));
        if (!conversation.knowledgeBaseId().equals(knowledgeBaseId)) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "会话不属于该知识库");
        }
        return conversation.id();
    }

    private List<ChatMessage> recentHistory(Long conversationId) {
        List<ChatMessage> all = chatRepository.listMessages(conversationId);
        if (all.size() <= 1) {
            return List.of();
        }
        // 去掉最后一条（刚插入的当前用户问题）
        List<ChatMessage> history = new ArrayList<>(all.subList(0, all.size() - 1));
        if (history.size() > HISTORY_MESSAGE_LIMIT) {
            return history.subList(history.size() - HISTORY_MESSAGE_LIMIT, history.size());
        }
        return history;
    }

    private String callModel(Long userId, String question, List<RetrievalResult> results, List<ChatMessage> history) {
        StringBuilder userPrompt = new StringBuilder();
        if (history != null && !history.isEmpty()) {
            userPrompt.append("历史对话：\n");
            for (ChatMessage message : history) {
                userPrompt.append("用户".equals(message.role()) ? "用户：" : "助手：")
                        .append(message.content())
                        .append('\n');
            }
            userPrompt.append('\n');
        }
        userPrompt.append("问题：").append(question).append("\n\n参考资料：\n");
        for (int i = 0; i < results.size(); i++) {
            RetrievalResult result = results.get(i);
            userPrompt.append('[').append(i + 1).append("] 文件名：").append(result.documentName());
            Object heading = result.metadata().get("heading");
            if (heading != null) {
                userPrompt.append("，标题：").append(heading);
            }
            userPrompt.append('\n').append(result.content()).append('\n');
        }
        try {
            AiModelGateway.ChatResult result = chatRouter.chat(SYSTEM_PROMPT, userPrompt.toString());
            modelUsageService.record(
                    userId,
                    "chat",
                    result.model(),
                    result.promptTokens(),
                    result.completionTokens(),
                    userPrompt.toString(),
                    result.content()
            );
            return result.content();
        } catch (ApiException ex) {
            if (ex.getCode() == ErrorCode.MODEL_CALL_FAILED && properties.localRagFallback()) {
                log.warn("模型调用失败，降级为本地 RAG 回答: {}", ex.getMessage());
                meterRegistry.counter("devmind.rag.degraded").increment();
                return LocalRagAnswerer.answer(question, results);
            }
            throw ex;
        } catch (Exception ex) {
            if (properties.localRagFallback()) {
                log.warn("模型调用异常，降级为本地 RAG 回答: {}", ex.getMessage());
                meterRegistry.counter("devmind.rag.degraded").increment();
                return LocalRagAnswerer.answer(question, results);
            }
            throw new ApiException(ErrorCode.MODEL_CALL_FAILED, "模型调用失败: " + ex.getMessage());
        }
    }

    /**
     * 混合检索，向量模型不可用（限流/故障）时自动降级为纯关键词检索，
     * 保证检索链路在模型不可用期间仍可用。
     */
    private List<RetrievalResult> searchWithFallback(
            Long knowledgeBaseId,
            String question,
            int topK,
            double vectorWeight,
            double keywordWeight,
            Map<String, Object> metadataFilter
    ) {
        List<Double> queryVector;
        try {
            queryVector = modelGateway.embed(List.of(question)).get(0);
            if (queryVector == null) {
                // 防御：embedding 返回空向量（不抛异常）时与失败同等处理，走关键词降级，
                // 否则 searchHybrid(null) 会检索空结果，chat 误报"没有足够相关内容"
                log.warn("embedding 返回空向量，降级为关键词检索: {}", question);
                return reranker.rerank(
                        question,
                        retrievalService.searchByKeywords(knowledgeBaseId, question, topK, metadataFilter),
                        topK
                );
            }
        } catch (Exception ex) {
            log.warn("embedding 不可用，降级为关键词检索: {}", ex.getMessage());
            return reranker.rerank(
                    question,
                    retrievalService.searchByKeywords(knowledgeBaseId, question, topK, metadataFilter),
                    topK
            );
        }
        return reranker.rerank(
                question,
                retrievalService.searchHybrid(
                        knowledgeBaseId,
                        queryVector,
                        question,
                        topK,
                        properties.retrievalMinScore(),
                        vectorWeight,
                        keywordWeight,
                        properties.retrievalHybridEnabled(),
                        metadataFilter
                ),
                topK
        );
    }

    private double round(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }
}

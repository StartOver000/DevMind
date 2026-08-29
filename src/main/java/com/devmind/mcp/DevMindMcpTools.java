package com.devmind.mcp;

import com.devmind.ai.AiModelGateway;
import com.devmind.audit.AuditLogService;
import com.devmind.chat.ChatService;
import com.devmind.chat.dto.ChatRequest;
import com.devmind.chat.dto.ChatResponse;
import com.devmind.config.DevMindProperties;
import com.devmind.config.McpServerProperties;
import com.devmind.knowledge.KnowledgeBaseService;
import com.devmind.retrieval.QueryRouter;
import com.devmind.retrieval.RetrievalResult;
import com.devmind.retrieval.RetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 二期·双向互联（docs/architecture/05）：MCP 工具实现——把平台自有能力暴露为 MCP 工具。
 *
 * <p>设计约束（治理平权）：MCP 调用与本地调用在审计/访问校验中同构——
 * 每次调用先走 {@code KnowledgeBaseService.requireEnabledKnowledgeBaseAccess}（与 HTTP 端点同源），
 * 再写 {@code AuditLogService}（action 前缀 mcp.）；模型用量由 ChatService 内的
 * {@code ModelUsageService.record} 自然计入服务账号（{@code devmind.mcp-server-user-id}）。</p>
 *
 * <p>工具注册与白名单过滤见 {@link McpServerConfig}；本类只承载实现，不感知 MCP 协议。</p>
 */
@Service
public class DevMindMcpTools {

    private static final Logger log = LoggerFactory.getLogger(DevMindMcpTools.class);

    /** 命中片段内容截断长度（与 KnowledgeBaseSearchController 的响应口径一致） */
    private static final int CONTENT_PREVIEW_CHARS = 500;
    /** 审计 detail 截断长度 */
    private static final int AUDIT_DETAIL_MAX = 200;

    private final KnowledgeBaseService knowledgeBaseService;
    private final RetrievalService retrievalService;
    private final AiModelGateway modelGateway;
    private final ChatService chatService;
    private final AuditLogService auditLogService;
    private final DevMindProperties properties;
    private final McpServerProperties mcpServerProperties;

    public DevMindMcpTools(
            KnowledgeBaseService knowledgeBaseService,
            RetrievalService retrievalService,
            AiModelGateway modelGateway,
            ChatService chatService,
            AuditLogService auditLogService,
            DevMindProperties properties,
            McpServerProperties mcpServerProperties
    ) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.retrievalService = retrievalService;
        this.modelGateway = modelGateway;
        this.chatService = chatService;
        this.auditLogService = auditLogService;
        this.properties = properties;
        this.mcpServerProperties = mcpServerProperties;
    }

    /** kb_search 工具入参（Spring AI 按此生成 MCP inputSchema） */
    public record KbSearchInput(Long knowledgeBaseId, String question, Integer topK, List<String> tags) {
    }

    /** kb_chat 工具入参 */
    public record KbChatInput(Long knowledgeBaseId, String question, Long conversationId) {
    }

    /**
     * 知识库纯语义检索（不调 LLM）：与 {@code /api/knowledge-bases/{id}/search} 同一条链路——
     * 问题向量化失败降级纯关键词检索；查询路由决定向量/关键词权重。
     */
    public Object kbSearch(KbSearchInput input) {
        long knowledgeBaseId = requireKnowledgeBaseId(input == null ? null : input.knowledgeBaseId());
        long userId = mcpServerProperties.userId();
        String question = input.question() == null ? "" : input.question().trim();
        if (question.isEmpty()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        knowledgeBaseService.requireEnabledKnowledgeBaseAccess(knowledgeBaseId, userId);

        int topK = input.topK() == null
                ? properties.retrievalTopK()
                : Math.min(Math.max(input.topK(), 1), properties.retrievalMaxTopK());
        Map<String, Object> metadataFilter = buildMetadataFilter(input.tags());

        List<Double> queryVector = null;
        try {
            queryVector = modelGateway.embed(List.of(question)).get(0);
        } catch (Exception ex) {
            log.warn("MCP kb_search 向量化失败，降级关键词检索: {}", ex.getMessage());
        }
        QueryRouter.Route route = QueryRouter.route(question);
        List<RetrievalResult> results = queryVector == null
                ? retrievalService.searchByKeywords(knowledgeBaseId, question, topK, metadataFilter)
                : retrievalService.searchHybrid(
                        knowledgeBaseId,
                        queryVector,
                        question,
                        topK,
                        properties.retrievalMinScore(),
                        route.vectorWeight(),
                        route.keywordWeight(),
                        properties.retrievalHybridEnabled(),
                        metadataFilter
                );

        List<Map<String, Object>> hits = results.stream()
                .map(DevMindMcpTools::toHit)
                .toList();

        auditLogService.log(userId, "mcp.kb_search", "knowledge_base", knowledgeBaseId, abbreviate(question));
        return Map.of(
                "knowledgeBaseId", knowledgeBaseId,
                "hitCount", hits.size(),
                "hits", hits
        );
    }

    /**
     * 知识库 RAG 问答（检索增强 + 引用溯源）：与 {@code /api/knowledge-bases/{id}/chat} 同源，
     * 模型用量计入 MCP 服务账号，配额（日次数/日成本）照常拦截。
     */
    public Object kbChat(KbChatInput input) {
        long knowledgeBaseId = requireKnowledgeBaseId(input == null ? null : input.knowledgeBaseId());
        long userId = mcpServerProperties.userId();
        String question = input.question() == null ? "" : input.question().trim();
        if (question.isEmpty()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        knowledgeBaseService.requireEnabledKnowledgeBaseAccess(knowledgeBaseId, userId);

        ChatRequest request = new ChatRequest(question, null, input.conversationId(), null);
        ChatResponse response = chatService.chat(knowledgeBaseId, request, userId);

        auditLogService.log(userId, "mcp.kb_chat", "knowledge_base", knowledgeBaseId, abbreviate(question));
        return response;
    }

    private static Map<String, Object> toHit(RetrievalResult result) {
        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("documentId", result.documentId());
        hit.put("documentName", result.documentName());
        hit.put("chunkId", result.chunkId());
        hit.put("chunkIndex", result.chunkIndex());
        hit.put("preview", truncate(result.content(), CONTENT_PREVIEW_CHARS));
        hit.put("score", result.similarityScore());
        hit.put("metadata", result.metadata());
        return hit;
    }

    private Map<String, Object> buildMetadataFilter(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return Map.of();
        }
        List<String> cleaned = tags.stream()
                .map(s -> s == null ? "" : s.trim())
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
        return cleaned.isEmpty() ? Map.of() : Map.of("tags", cleaned);
    }

    private long requireKnowledgeBaseId(Long knowledgeBaseId) {
        if (knowledgeBaseId == null || knowledgeBaseId <= 0) {
            throw new IllegalArgumentException("knowledgeBaseId 必须为正整数");
        }
        return knowledgeBaseId;
    }

    private static String truncate(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }

    private static String abbreviate(String text) {
        return truncate(text, AUDIT_DETAIL_MAX);
    }
}

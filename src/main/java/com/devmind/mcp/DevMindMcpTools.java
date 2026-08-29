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
    private final com.devmind.workflow.WorkflowService workflowService;
    private final com.devmind.workflow.WorkflowGenerationService workflowGenerationService;
    private final com.devmind.skill.SkillService skillService;

    public DevMindMcpTools(
            KnowledgeBaseService knowledgeBaseService,
            RetrievalService retrievalService,
            AiModelGateway modelGateway,
            ChatService chatService,
            AuditLogService auditLogService,
            DevMindProperties properties,
            McpServerProperties mcpServerProperties,
            com.devmind.workflow.WorkflowService workflowService,
            com.devmind.workflow.WorkflowGenerationService workflowGenerationService,
            com.devmind.skill.SkillService skillService
    ) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.retrievalService = retrievalService;
        this.modelGateway = modelGateway;
        this.chatService = chatService;
        this.auditLogService = auditLogService;
        this.properties = properties;
        this.mcpServerProperties = mcpServerProperties;
        this.workflowService = workflowService;
        this.workflowGenerationService = workflowGenerationService;
        this.skillService = skillService;
    }

    /** kb_search 工具入参（Spring AI 按此生成 MCP inputSchema） */
    public record KbSearchInput(Long knowledgeBaseId, String question, Integer topK, List<String> tags) {
    }

    /** kb_chat 工具入参 */
    public record KbChatInput(Long knowledgeBaseId, String question, Long conversationId) {
    }

    /** workflow_run 工具入参：执行一个已有工作流 */
    public record WorkflowRunInput(Long workflowId) {
    }

    /** workflow_generate 工具入参：自然语言生成工作流草稿 */
    public record WorkflowGenerateInput(String description) {
    }

    /** skill_invoke 工具入参：按技能 id 执行其关联工作流 */
    public record SkillInvokeInput(Long skillId) {
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

    /**
     * 执行一个已有工作流（写操作）：复用 WorkflowService.run 的完整治理链路
     * （引擎选择/授权/审计/配额），返回运行句柄。
     */
    public Object workflowRun(WorkflowRunInput input) {
        long userId = mcpServerProperties.userId();
        Long workflowId = input == null ? null : input.workflowId();
        if (workflowId == null || workflowId <= 0) {
            throw new IllegalArgumentException("workflowId 必须为正整数");
        }
        com.devmind.workflow.WorkflowRun run = workflowService.run(workflowId, userId);
        auditLogService.log(userId, "mcp.workflow_run", "workflow", workflowId, "runId=" + run.id());
        return Map.of(
                "workflowId", workflowId,
                "runId", run.id(),
                "status", run.status(),
                "error", run.error() == null ? "" : run.error()
        );
    }

    /**
     * 自然语言生成工作流草稿（只生成，不执行）：复用工详情 WorkflowGenerationService，
     * 返回步骤结构与 stepsJson，供后端确认后再创建。
     */
    public Object workflowGenerate(WorkflowGenerateInput input) {
        long userId = mcpServerProperties.userId();
        String description = input == null ? null : input.description();
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description 不能为空");
        }
        com.devmind.workflow.WorkflowGenerationService.GenerationResult result =
                workflowGenerationService.generate(userId, description.trim());
        auditLogService.log(userId, "mcp.workflow_generate", "workflow", null, abbreviate(description));
        return Map.of(
                "stepsJson", result.stepsJson(),
                "stepCount", result.steps() == null ? 0 : result.steps().size()
        );
    }

    /**
     * 按技能 id 执行其关联工作流：技能 references 中 type=workflow 的资源为执行目标。
     * 一次技能执行一个关联工作流（取第一个），未关联则返回提示。
     */
    public Object skillInvoke(SkillInvokeInput input) {
        long userId = mcpServerProperties.userId();
        Long skillId = input == null ? null : input.skillId();
        if (skillId == null || skillId <= 0) {
            throw new IllegalArgumentException("skillId 必须为正整数");
        }
        com.devmind.skill.Skill skill = skillService.get(userId, skillId);
        Long workflowId = extractWorkflowId(skill.references());
        if (workflowId == null) {
            auditLogService.log(userId, "mcp.skill_invoke", "skill", skillId, "无关联工作流");
            return Map.of("skillId", skillId, "status", "NO_WORKFLOW", "message", "技能未关联工作流");
        }
        com.devmind.workflow.WorkflowRun run = workflowService.run(workflowId, userId);
        auditLogService.log(userId, "mcp.skill_invoke", "skill", skillId, "workflowId=" + workflowId + ", runId=" + run.id());
        return Map.of(
                "skillId", skillId,
                "workflowId", workflowId,
                "runId", run.id(),
                "status", run.status(),
                "error", run.error() == null ? "" : run.error()
        );
    }

    /** 从技能 references JSON 中提取第一个 type=workflow 的引用 id */
    private Long extractWorkflowId(String referencesJson) {
        if (referencesJson == null || referencesJson.isBlank() || "[]".equals(referencesJson.trim())) {
            return null;
        }
        try {
            var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(referencesJson);
            if (root.isArray()) {
                for (var node : root) {
                    if ("workflow".equalsIgnoreCase(node.path("type").asText("")) && node.has("id")) {
                        return node.get("id").asLong();
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("MCP skill_invoke 解析 references 失败: {}", ex.getMessage());
        }
        return null;
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

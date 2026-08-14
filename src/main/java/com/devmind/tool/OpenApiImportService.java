package com.devmind.tool;

import com.devmind.ai.AiModelGateway;
import com.devmind.ai.ChatRouter;
import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.tool.dto.ToolCreateRequest;
import com.devmind.tool.dto.ToolResponse;
import com.devmind.tool.openapi.OpenApiOperation;
import com.devmind.tool.openapi.OpenApiParser;
import com.devmind.user.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * OpenAPI 导入 + 接口语义化（P1，guide-57 M1）。
 *
 * 闭环：上传 OpenAPI 3.0 文档 → 解析每个接口 → 批量登记为动态接口工具（复用
 * {@link InterfaceToolService}）→ 生成语义档案文本 → embedding 向量化入 pgvector
 * （tool_semantic 表）→ 支持"自然语言 → 命中对应接口"的语义检索。
 *
 * 设计取舍：
 * - 导入阶段不调用 LLM（几百个接口逐个生成太慢太贵）：OpenAPI 自带的
 *   summary/description/tags 已是优质检索文本，直接组合向量化；
 * - LLM 语义增强（enhanceSemantic）按需触发：对单个接口生成"业务用途 + 调用场景"。
 * - 向量化失败自动降级为仅关键词检索，不影响接口登记本身。
 */
@Service
public class OpenApiImportService {

    private static final Logger log = LoggerFactory.getLogger(OpenApiImportService.class);

    private static final ObjectMapper objectMapper = new ObjectMapper();
    /** 语义检索最低相似度（score = 1 - cosine distance）。
     * 接口语义检索用于"候选召回"，0.20 已足够过滤无关项（真实 embedding 下无关通常 <0.1，
     * mock 伪向量下相关项约 0.2-0.5），且 Agent/用户会自行判断最终选用。 */
    private static final double SEMANTIC_MIN_SCORE = 0.20;
    /** 单个文件大小上限（10MB） */
    private static final long MAX_FILE_BYTES = 10 * 1024 * 1024L;

    private final OpenApiParser parser;
    private final InterfaceToolService toolService;
    private final ToolSemanticRepository semanticRepository;
    private final ToolDefinitionRepository toolDefinitionRepository;
    private final AiModelGateway modelGateway;
    private final ChatRouter chatRouter;
    private final UserService userService;

    public OpenApiImportService(
            OpenApiParser parser,
            InterfaceToolService toolService,
            ToolSemanticRepository semanticRepository,
            ToolDefinitionRepository toolDefinitionRepository,
            AiModelGateway modelGateway,
            ChatRouter chatRouter,
            UserService userService
    ) {
        this.parser = parser;
        this.toolService = toolService;
        this.semanticRepository = semanticRepository;
        this.toolDefinitionRepository = toolDefinitionRepository;
        this.modelGateway = modelGateway;
        this.chatRouter = chatRouter;
        this.userService = userService;
    }

    /** 导入结果：成功/跳过（已存在）/失败的接口明细 + 汇总 */
    public record ImportedItem(
            String method, String path, String name,
            Long toolId, String semanticText, String error
    ) {
    }

    public record ImportResult(
            String docTitle, int total, int created, int skipped, int failed,
            List<ImportedItem> items, List<String> warnings
    ) {
    }

    /** LLM 语义增强结果 */
    public record EnhanceResult(String name, String semanticText) {
    }

    /**
     * 导入 OpenAPI 文档：解析 → 批量登记接口工具 → 向量化语义档案。
     * 仅管理员可操作。同一文档重复导入时已存在的接口自动跳过（幂等）。
     */
    public ImportResult importOpenApi(MultipartFile file, Long userId) {
        requireAdmin(userId);
        Long tenantId = userService.tenantIdOf(userId);

        String content = readFile(file);
        OpenApiParser.ParsedDocument doc = parser.parse(content, file.getOriginalFilename());

        Set<String> usedNames = new HashSet<>();
        List<ImportedItem> items = new ArrayList<>();
        for (OpenApiOperation op : doc.operations()) {
            String name = uniqueName(normalizeName(op), usedNames);
            try {
                ToolCreateRequest req = buildRequest(op, name, doc.baseUrl());
                ToolResponse created = toolService.create(req, userId);
                String semanticText = buildSemanticText(op, created);
                items.add(new ImportedItem(op.method(), op.path(), name, created.id(), semanticText, null));
            } catch (ApiException e) {
                // 工具名已存在（幂等跳过）→ 刷新语义档案：接口描述可能已更新，
                // 保证重复导入后语义检索仍能命中（tool_semantic 不被遗漏）。
                if (e.getMessage() != null && e.getMessage().contains("已存在")) {
                    ToolDefinition existing = toolDefinitionRepository.findByName(name);
                    if (existing != null && "READY".equals(existing.status())) {
                        String semanticText = buildSemanticText(op, ToolResponse.from(existing));
                        // error 标记"已存在"：统计上计入 skipped（未新建），但参与语义档案刷新
                        items.add(new ImportedItem(op.method(), op.path(), name,
                                existing.id(), semanticText, "已存在"));
                        continue;
                    }
                }
                // 其他冲突（与系统工具同名等）→ 幂等跳过
                items.add(new ImportedItem(op.method(), op.path(), name, null, null, e.getMessage()));
            } catch (Exception e) {
                log.warn("导入接口 {} {} 失败: {}", op.method(), op.path(), e.getMessage());
                items.add(new ImportedItem(op.method(), op.path(), name, null, null, e.getMessage()));
            }
        }

        // 向量化语义档案（失败降级：接口仍登记，仅无语义检索）
        List<ImportedItem> ok = items.stream().filter(i -> i.toolId() != null).toList();
        if (!ok.isEmpty()) {
            try {
                List<String> texts = ok.stream().map(ImportedItem::semanticText).toList();
                List<List<Double>> embeddings = modelGateway.embed(texts);
                for (int i = 0; i < ok.size(); i++) {
                    ImportedItem item = ok.get(i);
                    semanticRepository.upsert(tenantId, item.toolId(), item.semanticText(), embeddings.get(i));
                }
            } catch (Exception e) {
                log.warn("接口语义向量化失败，已降级为仅关键词检索: {}", e.getMessage());
            }
        }

        int created = (int) ok.stream().filter(i -> i.error() == null).count();
        int skipped = (int) items.stream().filter(i -> i.error() != null
                && i.error().contains("已存在")).count();
        int failed = items.size() - created - skipped;
        log.info("OpenAPI 导入完成『{}』：共 {}，新建 {}，跳过 {}，失败 {}",
                doc.title(), items.size(), created, skipped, failed);
        return new ImportResult(doc.title(), items.size(), created, skipped, failed, items, doc.warnings());
    }

    /**
     * 语义检索：把自然语言查询向量化后按余弦相似度命中接口。
     * embedding 不可用（模型挂/超时）时自动降级为 name/description 关键词匹配。
     */
    public List<ToolSemanticRepository.SemanticHit> semanticSearch(String query, Long userId, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Long tenantId = userService.tenantIdOf(userId);
        int topK = Math.min(Math.max(limit, 1), 20);
        try {
            List<List<Double>> vectors = modelGateway.embed(List.of(query));
            return semanticRepository.semanticSearch(tenantId, vectors.get(0), topK, SEMANTIC_MIN_SCORE);
        } catch (Exception e) {
            log.warn("语义检索降级为关键词匹配: {}", e.getMessage());
            return semanticRepository.keywordSearch(tenantId, query.trim(), topK);
        }
    }

    /**
     * LLM 语义增强（按需触发）：对单个接口调用 LLM 生成"业务用途 + 调用场景"，
     * 合并进语义档案并重新向量化，提升自然语言检索命中率。仅管理员可操作。
     */
    public EnhanceResult enhanceSemantic(Long toolId, Long userId) {
        requireAdmin(userId);
        Long tenantId = userService.tenantIdOf(userId);
        ToolResponse tool = toolService.get(toolId, userId);

        String baseText = semanticRepository.findSemanticText(toolId);
        if (baseText == null) {
            baseText = buildBaseText(tool);
        }
        String llmEnhance = callLlmEnhance(tool, baseText);
        String combined = baseText + "\n\n【AI 增强】" + llmEnhance;

        try {
            List<List<Double>> embeddings = modelGateway.embed(List.of(combined));
            semanticRepository.upsert(tenantId, toolId, combined, embeddings.get(0));
        } catch (Exception e) {
            log.warn("接口 {} 语义向量化失败，增强文本已生成但未入库向量: {}", tool.name(), e.getMessage());
        }
        return new EnhanceResult(tool.name(), combined);
    }

    // ---------- 私有工具方法 ----------

    private void requireAdmin(Long userId) {
        if (!userService.isAdmin(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "仅管理员可导入 OpenAPI / 增强接口语义");
        }
    }

    private String readFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "请上传 OpenAPI 3.0 文档（JSON 或 YAML）");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new ApiException(ErrorCode.FILE_TOO_LARGE, "OpenAPI 文档超过 10MB 上限");
        }
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "读取 OpenAPI 文档失败: " + e.getMessage());
        }
    }

    /** 工具名规范化：operationId 去非法字符；缺省时由 method+path 生成 */
    private String normalizeName(OpenApiOperation op) {
        String raw = op.operationId();
        if (raw == null || raw.isBlank()) {
            String path = op.path().replaceAll("[^a-zA-Z0-9]", "_");
            raw = op.method().toLowerCase() + "_" + path;
        }
        String cleaned = raw.replaceAll("[^a-zA-Z0-9_]", "_");
        if (cleaned.isEmpty()) {
            cleaned = "api_" + op.method().toLowerCase();
        }
        if (!Character.isLetter(cleaned.charAt(0)) && cleaned.charAt(0) != '_') {
            cleaned = "t_" + cleaned;
        }
        return cleaned;
    }

    /** 文档内工具名去重（同文档重复 name 追加 _2/_3...；与库中已有名冲突由 create 幂等跳过） */
    private String uniqueName(String base, Set<String> usedNames) {
        String name = base;
        int suffix = 2;
        while (usedNames.contains(name)) {
            name = base + "_" + suffix++;
        }
        usedNames.add(name);
        return name;
    }

    /** 构造登记请求：接口地址 = server + path；参数/请求体归一为 JSON Schema */
    private ToolCreateRequest buildRequest(OpenApiOperation op, String name, String baseUrl) {
        String endpoint = op.path();
        if (baseUrl != null && !baseUrl.isBlank()) {
            endpoint = baseUrl.replaceAll("/+$", "") + op.path();
        }
        String description = op.summary();
        if ((description == null || description.isBlank()) && !op.description().isBlank()) {
            description = op.description();
        }
        // tool_definition.description 列 VARCHAR(500)：真实 API（如 eBay/Stripe）描述常超长，
        // 截断防导入失败（语义档案 semanticText 用完整描述，不受影响）
        description = truncate(description, 500);
        String schema = buildRequestSchema(op);
        return new ToolCreateRequest(
                name,
                description,
                endpoint,
                op.method(),
                schema,
                null,
                "none",
                null,
                null
        );
    }

    /** 截断到指定长度（DB 列宽容错；真实 API 描述常超长） */
    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 从 path/query/header 参数 + 请求体生成 JSON Schema（给 Agent 的参数说明） */
    private String buildRequestSchema(OpenApiOperation op) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");
        ObjectNode properties = root.putObject("properties");
        ArrayNode required = root.putArray("required");

        for (OpenApiOperation.ParameterSpec p : op.parameters()) {
            ObjectNode prop = properties.putObject(p.name());
            prop.put("type", p.type());
            if (p.description() != null && !p.description().isBlank()) {
                prop.put("description", p.description());
            }
            prop.put("in", p.in());
            if (p.required()) {
                required.add(p.name());
            }
        }
        // 请求体 schema 的 properties 并入（请求体对象）
        if (op.requestBodyJson() != null && !op.requestBodyJson().isBlank()) {
            try {
                var bodySchema = objectMapper.readTree(op.requestBodyJson());
                var bodyProps = bodySchema.path("properties");
                if (bodyProps.isObject()) {
                    bodyProps.fields().forEachRemaining(e -> properties.set(e.getKey(), e.getValue()));
                }
                var bodyRequired = bodySchema.path("required");
                if (bodyRequired.isArray()) {
                    bodyRequired.forEach(r -> required.add(r.asText()));
                }
            } catch (Exception e) {
                log.warn("请求体 schema 解析失败，跳过 body 参数: {}", e.getMessage());
            }
        }
        return root.toString();
    }

    /** 语义档案文本：结构化拼接 method/path/标签/概述/描述/参数（embedding 检索用） */
    private String buildSemanticText(OpenApiOperation op, ToolResponse created) {
        StringBuilder sb = new StringBuilder();
        sb.append(op.method()).append(' ').append(op.path()).append(' ');
        if (!op.summary().isBlank()) {
            sb.append("— ").append(op.summary());
        }
        sb.append('\n').append("接口名称: ").append(created.name());
        if (!op.tags().isEmpty()) {
            sb.append('\n').append("标签: ").append(String.join(", ", op.tags()));
        }
        if (!op.description().isBlank()) {
            sb.append('\n').append("描述: ").append(op.description());
        }
        if (!op.parameters().isEmpty()) {
            sb.append('\n').append("参数:");
            for (OpenApiOperation.ParameterSpec p : op.parameters()) {
                sb.append(' ').append(p.name()).append('(').append(p.in()).append(',')
                        .append(p.type()).append(p.required() ? ",必填" : ",可选").append(')');
                if (p.description() != null && !p.description().isBlank()) {
                    sb.append(':').append(p.description());
                }
                sb.append(';');
            }
        }
        return sb.toString();
    }

    /** 无语义档案时，从工具定义生成基础检索文本 */
    private String buildBaseText(ToolResponse tool) {
        StringBuilder sb = new StringBuilder();
        sb.append(tool.httpMethod()).append(' ').append(tool.endpointUrl()).append(' ');
        if (tool.description() != null && !tool.description().isBlank()) {
            sb.append("— ").append(tool.description());
        }
        sb.append('\n').append("接口名称: ").append(tool.name());
        return sb.toString();
    }

    /** 调用 LLM 生成接口语义增强：一句话业务用途 + 典型调用场景 + 常见用途关键词 */
    private String callLlmEnhance(ToolResponse tool, String baseText) {
        String system = """
                你是 API 语义化助手。给定一个接口的信息，用简洁中文生成三段内容，帮助用户用自然语言检索到这个接口：
                1. 一句话业务用途（不超过 30 字，说明这个接口在业务上干什么）；
                2. 典型调用场景（1-2 句，说明什么情况下会用到它）；
                3. 检索关键词（3-5 个逗号分隔的词，覆盖用户可能的问法）。
                直接输出三段内容，用换行分隔，不要任何解释或 Markdown 代码块。
                """;
        String user = "接口名称: " + tool.name() + "\n方法: " + tool.httpMethod() + "\n地址: "
                + tool.endpointUrl() + "\n说明: " + (tool.description() == null ? "" : tool.description())
                + "\n\n现有语义档案:\n" + (baseText == null ? "(无)" : baseText);
        try {
            AiModelGateway.ChatResult result = chatRouter.chat(system, user);
            String content = result.content() == null ? "" : result.content().trim();
            // 去可能的代码块包裹
            content = content.replaceAll("```[a-zA-Z]*\\n?", "").replaceAll("```", "").trim();
            if (content.isEmpty()) {
                throw new IllegalStateException("模型返回空内容");
            }
            return content;
        } catch (Exception e) {
            log.warn("LLM 语义增强失败: {}", e.getMessage());
            throw new ApiException(ErrorCode.MODEL_CALL_FAILED, "AI 语义增强失败: " + e.getMessage());
        }
    }
}

package com.devmind.capability;

import com.devmind.ai.AiModelGateway;
import com.devmind.ai.ChatRouter;
import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.tool.OpenApiImportService;
import com.devmind.tool.ToolSemanticRepository;
import com.devmind.user.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 缺失能力反推（接口能力治理，P1 深化）：用户用自然语言描述业务需求，
 * 平台先语义检索现有接口（已有能力），再让 LLM 把需求拆解为步骤并逐步骤判断
 * "现有接口能否覆盖"，输出能力盘点（覆盖 ✓ / 缺失 ❌）与结构化缺失能力清单。
 *
 * 价值：不止"用已有工具"，而是告诉用户"这个需求还缺什么接口、补什么文档就能完成"，
 * 形成 需求 → 能力盘点 → 补 OpenAPI → 重盘点 → 编排 的闭环（guide-57 缺失能力反推）。
 */
@Service
public class CapabilityGapService {

    private static final Logger log = LoggerFactory.getLogger(CapabilityGapService.class);

    /** 语义检索现有接口的数量（作为 LLM 的候选能力清单） */
    private static final int MATCH_LIMIT = 8;

    private final ChatRouter chatRouter;
    private final OpenApiImportService openApiImportService;
    private final UserService userService;
    private final ObjectMapper objectMapper;

    public CapabilityGapService(
            ChatRouter chatRouter,
            OpenApiImportService openApiImportService,
            UserService userService,
            ObjectMapper objectMapper
    ) {
        this.chatRouter = chatRouter;
        this.openApiImportService = openApiImportService;
        this.userService = userService;
        this.objectMapper = objectMapper;
    }

    /** 缺失能力建议（covered=false 的步骤） */
    public record Gap(String suggestedName, String method, String path, String description) {
    }

    /** 需求拆解后的单步覆盖分析 */
    public record AnalysisStep(
            String step,
            boolean covered,
            String interfaceName, // covered=true 时命中的接口名
            String note,
            Gap gap               // covered=false 时的缺失能力建议
    ) {
    }

    /** 能力盘点结果 */
    public record CapabilityAnalysis(
            String description,
            List<ToolSemanticRepository.SemanticHit> matchedInterfaces,
            List<AnalysisStep> steps,
            List<Gap> gaps,
            List<String> warnings
    ) {
    }

    /**
     * 能力盘点：语义检索命中接口 + LLM 拆解需求标注覆盖/缺口。
     * LLM 解析失败不中断——至少返回语义检索命中的现有接口，让用户看到已有能力。
     */
    public CapabilityAnalysis analyze(Long userId, String description) {
        if (description == null || description.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "请描述你的业务需求");
        }
        List<ToolSemanticRepository.SemanticHit> hits =
                openApiImportService.semanticSearch(description, userId, MATCH_LIMIT);
        List<String> warnings = new ArrayList<>();

        String systemPrompt = buildSystemPrompt(hits);
        AiModelGateway.ChatResult result = chatRouter.chat(systemPrompt, description.trim());
        if (result == null || result.content() == null || result.content().isBlank()) {
            throw new ApiException(ErrorCode.MODEL_CALL_FAILED, "模型未返回能力分析");
        }
        List<AnalysisStep> steps = parseSteps(result.content(), warnings);
        List<Gap> gaps = collectGaps(steps);

        log.info("能力盘点完成：命中 {} 个接口，拆解 {} 步，缺失能力 {} 个 (user={})",
                hits.size(), steps.size(), gaps.size(), userId);
        return new CapabilityAnalysis(description.trim(), hits, steps, gaps, warnings);
    }

    private String buildSystemPrompt(List<ToolSemanticRepository.SemanticHit> hits) {
        StringBuilder tools = new StringBuilder();
        if (hits.isEmpty()) {
            tools.append("（暂无接口。请全部步骤标记 covered=false，给出缺失能力建议）\n");
        } else {
            for (ToolSemanticRepository.SemanticHit hit : hits) {
                tools.append("- ").append(hit.name()).append(" (").append(hit.httpMethod())
                        .append(' ').append(hit.endpointUrl()).append(")：")
                        .append(hit.description() == null ? "" : hit.description()).append('\n');
            }
        }
        return """
                你是接口能力分析师。用户用自然语言描述一个业务需求，你要判断平台的现有接口能否完成它，并指出缺口。

                现有接口能力清单（只能使用这些接口名，不要编造）：
                %s
                分析要求：
                1. 把需求拆解为完成它需要的步骤（3-8 步，粒度适中，按执行顺序）；
                2. 每步判断现有接口能否覆盖：
                   - 能：covered=true，interface 填清单中存在的接口名，note 简述怎么用；
                   - 不能：covered=false，gap 给出缺失能力建议（suggestedName 用英文驼峰，
                     method 用 GET/POST/PUT/DELETE，path 用 /资源/{id} 风格，description 说明功能）；
                3. 不要编造清单外的接口名；无法覆盖的步骤一律标 covered=false 并给 gap。

                只输出一个 JSON 对象（不要代码块标记、不要解释文字）：
                {"steps":[{"step":"步骤描述","covered":true,"interface":"listOrders","note":"说明"},
                          {"step":"步骤描述","covered":false,"gap":{"suggestedName":"sendWechatMessage","method":"POST","path":"/wechat/messages","description":"向企业微信群发送消息"}}]}
                """.formatted(tools);
    }

    /** 容错解析 LLM 输出的 JSON 对象（支持 ```json 包裹/前后文字），失败不中断 */
    private List<AnalysisStep> parseSteps(String content, List<String> warnings) {
        String json = extractJsonObject(content);
        if (json == null) {
            warnings.add("模型未返回结构化能力分析，仅展示语义检索命中的接口");
            log.warn("能力盘点：未找到 JSON 对象");
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode steps = root.path("steps");
            if (!steps.isArray()) {
                warnings.add("模型返回格式缺少 steps 数组");
                return List.of();
            }
            List<AnalysisStep> list = new ArrayList<>();
            for (JsonNode node : steps) {
                boolean covered = node.path("covered").asBoolean(false);
                Gap gap = null;
                if (!covered) {
                    JsonNode g = node.path("gap");
                    if (g.isObject()) {
                        gap = new Gap(
                                g.path("suggestedName").asText(""),
                                g.path("method").asText(""),
                                g.path("path").asText(""),
                                g.path("description").asText("")
                        );
                    }
                }
                list.add(new AnalysisStep(
                        node.path("step").asText(""),
                        covered,
                        node.path("interface").asText(""),
                        node.path("note").asText(""),
                        gap
                ));
            }
            return list;
        } catch (Exception e) {
            warnings.add("能力分析解析失败：" + e.getMessage());
            log.warn("能力盘点 JSON 解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 汇总缺失能力（按建议名去重） */
    private List<Gap> collectGaps(List<AnalysisStep> steps) {
        Map<String, Gap> seen = new LinkedHashMap<>();
        for (AnalysisStep s : steps) {
            if (s.gap() == null) {
                continue;
            }
            String name = s.gap().suggestedName();
            if (name != null && !name.isBlank() && !seen.containsKey(name)) {
                seen.put(name, s.gap());
            }
        }
        return List.copyOf(seen.values());
    }

    private String extractJsonObject(String content) {
        if (content == null) {
            return null;
        }
        String text = content.trim();
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int end = text.lastIndexOf('}');
        if (end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }
}

package com.devmind.workflow;

import com.devmind.agent.AgentTool;
import com.devmind.agent.ToolRegistry;
import com.devmind.ai.AiModelGateway;
import com.devmind.ai.ChatRouter;
import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.tool.ToolAccessService;
import com.devmind.user.UserService;
import com.devmind.workflow.dto.WorkflowStepDraft;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 对话式生成工作流草案：业务人员用自然语言描述需求，
 * LLM 结合"已登记/已授权工具集"生成有序步骤（steps_json 草案），用户确认后保存。
 */
@Service
public class WorkflowGenerationService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowGenerationService.class);

    private final ChatRouter chatRouter;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final UserService userService;
    private final ToolAccessService toolAccessService;

    public WorkflowGenerationService(
            ChatRouter chatRouter,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            UserService userService,
            ToolAccessService toolAccessService
    ) {
        this.chatRouter = chatRouter;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.userService = userService;
        this.toolAccessService = toolAccessService;
    }

    /** 生成结果：步骤（展示用）+ stepsJson（可直接提交创建工作流） */
    public record GenerationResult(List<WorkflowStepDraft> steps, String stepsJson) {
    }

    /**
     * 把需求描述转为工作流步骤草案。
     *
     * @throws ApiException 模型不可用或生成结果无法通过校验
     */
    public GenerationResult generate(Long userId, String description) {
        if (description == null || description.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "请描述你的需求");
        }
        String systemPrompt = buildSystemPrompt(userId);
        AiModelGateway.ChatResult result = chatRouter.chat(systemPrompt, description.trim());
        if (result == null || result.content() == null || result.content().isBlank()) {
            throw new ApiException(ErrorCode.MODEL_CALL_FAILED, "模型未返回工作流草案");
        }
        Long tenantId = userService.tenantIdOf(userId);
        Set<String> accessible = toolAccessService.accessibleToolNames(tenantId, userId);
        List<WorkflowStepDraft> steps = parseAndValidate(result.content(), accessible);
        if (steps.isEmpty()) {
            throw new ApiException(ErrorCode.MODEL_CALL_FAILED, "未能从模型结果解析出有效步骤，请重试或换个说法");
        }
        log.info("对话式生成工作流草案成功，共 {} 步 (user={})", steps.size(), userId);
        return new GenerationResult(steps, toStepsJson(steps));
    }

    private String buildSystemPrompt(Long userId) {
        Long tenantId = userService.tenantIdOf(userId);
        Set<String> accessible = toolAccessService.accessibleToolNames(tenantId, userId);
        StringBuilder tools = new StringBuilder();
        for (AgentTool tool : toolRegistry.all()) {
            if (!accessible.contains(tool.name())) {
                continue; // 只列出当前用户可用的工具
            }
            tools.append("- ").append(tool.name()).append(": ").append(tool.description());
            String params = summarizeParams(tool.parametersJsonSchema());
            if (!params.isEmpty()) {
                tools.append("；参数: ").append(params);
            }
            tools.append('\n');
        }
        return """
                你是工作流设计助手。用户用自然语言描述业务需求，你把需求转换为"工作流步骤"，支持顺序、条件分支和并行。

                可用工具（只能使用这些）：
                %s
                输出要求（严格遵守）：
                1. 只输出一个 JSON 数组，不要输出任何解释文字或代码块标记；
                2. 普通步骤：{"tool": "工具名", "params": {参数对象}, "output_var": "输出变量名", "goal": "本步目标"}；
                3. 条件分支：{"if": "条件表达式", "then": [步骤数组], "else": [步骤数组]}，then 必填、else 可省略；
                   条件表达式用 {{变量}} 引用上一步输出，如 {{sales}} > 10000、{{status}} == 'success'；
                4. 并行组：{"parallel": [步骤数组]}（并行步骤互相独立，不要引用彼此的输出）；
                5. 步骤间传数据：上一步的 output_var 命名输出，下一步 params 中用 {{output_var}} 引用；
                6. 需要查询/取数时先调对应接口或检索工具，最后需要生成报告/总结时用 ai_generate（prompt 中引用前面变量）；
                7. params 中不要出现 {{...}} 以外的模板语法；
                8. 分支/并行只在你判断业务确实需要时才用，简单顺序任务直接列普通步骤。
                """.formatted(tools);
    }

    private String summarizeParams(String schemaJson) {
        try {
            JsonNode schema = objectMapper.readTree(schemaJson);
            JsonNode props = schema.path("properties");
            if (!props.isObject()) {
                return "";
            }
            List<String> parts = new ArrayList<>();
            props.fields().forEachRemaining(e -> {
                String type = e.getValue().path("type").asText("");
                parts.add(e.getKey() + "(" + (type.isEmpty() ? "any" : type) + ")");
            });
            return String.join(", ", parts);
        } catch (Exception ex) {
            return "";
        }
    }

    /** 容错解析模型输出（可能带 ```json 包裹/前后文字），并校验工具名合法且已授权（递归支持 if/parallel） */
    private List<WorkflowStepDraft> parseAndValidate(String content, Set<String> accessible) {
        String json = extractJsonArray(content);
        if (json == null) {
            log.warn("工作流草案解析失败，未找到 JSON 数组");
            return List.of();
        }
        try {
            JsonNode array = objectMapper.readTree(json);
            if (!array.isArray()) {
                return List.of();
            }
            List<WorkflowStepDraft> steps = new ArrayList<>();
            for (JsonNode node : array) {
                WorkflowStepDraft draft = parseNode(node, accessible);
                if (draft != null) {
                    steps.add(draft);
                }
            }
            return steps;
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("工作流草案 JSON 解析失败: {}", ex.getMessage());
            return List.of();
        }
    }

    /** 递归解析单个节点：if 分支 / parallel 并行组 / 普通步骤 */
    private WorkflowStepDraft parseNode(JsonNode node, Set<String> accessible) {
        // 条件分支：{"if": "{{x}} > 100", "then": [...], "else": [...]}
        String condition = node.path("if").asText("");
        if (!condition.isBlank()) {
            List<WorkflowStepDraft> thenSteps = parseBranch(node.path("then"), accessible);
            List<WorkflowStepDraft> elseSteps = parseBranch(node.path("else"), accessible);
            if (thenSteps.isEmpty() && elseSteps.isEmpty()) {
                log.warn("条件分支 then/else 均为空，跳过: {}", condition);
                return null;
            }
            return WorkflowStepDraft.ifNode(condition, thenSteps, elseSteps);
        }
        // 并行组：{"parallel": [步骤数组]}
        JsonNode parallel = node.path("parallel");
        if (parallel.isArray() && !parallel.isEmpty()) {
            List<WorkflowStepDraft> steps = new ArrayList<>();
            for (JsonNode item : parallel) {
                WorkflowStepDraft draft = parseNode(item, accessible);
                if (draft != null) {
                    steps.add(draft);
                }
            }
            return steps.isEmpty() ? null : WorkflowStepDraft.parallel(steps);
        }
        // 普通步骤
        return parseStepNode(node, accessible);
    }

    /** 解析分支内步骤数组（then/else） */
    private List<WorkflowStepDraft> parseBranch(JsonNode node, Set<String> accessible) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return List.of();
        }
        List<WorkflowStepDraft> steps = new ArrayList<>();
        for (JsonNode item : node) {
            WorkflowStepDraft draft = parseNode(item, accessible);
            if (draft != null) {
                steps.add(draft);
            }
        }
        return steps;
    }

    /** 解析普通步骤：校验工具名合法且已授权 */
    private WorkflowStepDraft parseStepNode(JsonNode node, Set<String> accessible) {
        String tool = node.path("tool").asText("");
        if (tool.isBlank()) {
            return null;
        }
        if (!toolRegistry.has(tool)) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "模型生成了未登记的工具: " + tool);
        }
        if (!accessible.contains(tool)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "模型生成了未授权的工具: " + tool);
        }
        JsonNode params = node.path("params");
        String paramsJson;
        try {
            paramsJson = (params == null || params.isMissingNode() || !params.isObject())
                    ? "{}" : objectMapper.writeValueAsString(params);
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "步骤参数解析失败: " + tool);
        }
        return WorkflowStepDraft.step(
                tool,
                paramsJson,
                node.path("output_var").asText(""),
                node.path("goal").asText("")
        );
    }

    /** 提取内容中最外层 JSON 数组（容忍 markdown 代码块与前后文字） */
    private String extractJsonArray(String content) {
        if (content == null) {
            return null;
        }
        String text = content.trim();
        int start = text.indexOf('[');
        if (start < 0) {
            return null;
        }
        int end = text.lastIndexOf(']');
        if (end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    /** 把步骤草案递归序列化为可直接提交创建工作流的 stepsJson（step/if/parallel） */
    private String toStepsJson(List<WorkflowStepDraft> steps) {
        try {
            ArrayNode array = objectMapper.createArrayNode();
            for (WorkflowStepDraft step : steps) {
                array.add(toNode(step));
            }
            return objectMapper.writeValueAsString(array);
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "工作流草案序列化失败");
        }
    }

    private JsonNode toNode(WorkflowStepDraft draft) {
        ObjectNode node = objectMapper.createObjectNode();
        if ("if".equals(draft.kind())) {
            node.put("if", draft.condition());
            ArrayNode thenArray = node.putArray("then");
            for (WorkflowStepDraft s : draft.thenBranch()) {
                thenArray.add(toNode(s));
            }
            if (draft.elseBranch() != null && !draft.elseBranch().isEmpty()) {
                ArrayNode elseArray = node.putArray("else");
                for (WorkflowStepDraft s : draft.elseBranch()) {
                    elseArray.add(toNode(s));
                }
            }
            return node;
        }
        if ("parallel".equals(draft.kind())) {
            ArrayNode parallelArray = node.putArray("parallel");
            for (WorkflowStepDraft s : draft.parallelSteps()) {
                parallelArray.add(toNode(s));
            }
            return node;
        }
        node.put("tool", draft.tool());
        if (draft.paramsJson() != null && !draft.paramsJson().isBlank()) {
            try {
                node.set("params", objectMapper.readTree(draft.paramsJson()));
            } catch (Exception ex) {
                node.putObject("params");
            }
        } else {
            node.putObject("params");
        }
        node.put("output_var", draft.outputVar());
        if (draft.goal() != null && !draft.goal().isBlank()) {
            node.put("goal", draft.goal());
        }
        return node;
    }
}

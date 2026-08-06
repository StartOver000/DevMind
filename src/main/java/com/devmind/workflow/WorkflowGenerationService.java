package com.devmind.workflow;

import com.devmind.agent.AgentTool;
import com.devmind.agent.ToolRegistry;
import com.devmind.ai.AiModelGateway;
import com.devmind.ai.ChatRouter;
import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
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

    public WorkflowGenerationService(
            ChatRouter chatRouter,
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper
    ) {
        this.chatRouter = chatRouter;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
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
        String systemPrompt = buildSystemPrompt();
        AiModelGateway.ChatResult result = chatRouter.chat(systemPrompt, description.trim());
        if (result == null || result.content() == null || result.content().isBlank()) {
            throw new ApiException(ErrorCode.MODEL_CALL_FAILED, "模型未返回工作流草案");
        }
        List<WorkflowStepDraft> steps = parseAndValidate(result.content());
        if (steps.isEmpty()) {
            throw new ApiException(ErrorCode.MODEL_CALL_FAILED, "未能从模型结果解析出有效步骤，请重试或换个说法");
        }
        log.info("对话式生成工作流草案成功，共 {} 步 (user={})", steps.size(), userId);
        return new GenerationResult(steps, toStepsJson(steps));
    }

    private String buildSystemPrompt() {
        StringBuilder tools = new StringBuilder();
        for (AgentTool tool : toolRegistry.all()) {
            tools.append("- ").append(tool.name()).append(": ").append(tool.description());
            String params = summarizeParams(tool.parametersJsonSchema());
            if (!params.isEmpty()) {
                tools.append("；参数: ").append(params);
            }
            tools.append('\n');
        }
        return """
                你是工作流设计助手。用户用自然语言描述业务需求，你把需求转换为"有序的工作流步骤"。

                可用工具（只能使用这些）：
                %s
                输出要求（严格遵守）：
                1. 只输出一个 JSON 数组，不要输出任何解释文字或代码块标记；
                2. 数组每个元素是一个步骤对象：{"tool": "工具名", "params": {参数对象}, "output_var": "输出变量名", "goal": "本步目标"};
                3. 步骤间传数据：上一步的 output_var 命名输出，下一步 params 中用 {{output_var}} 引用；
                4. 需要查询/取数时先调对应接口或检索工具，最后需要生成报告/总结时用 ai_generate（prompt 中引用前面变量）；
                5. params 中不要出现 {{...}} 以外的模板语法。
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

    /** 容错解析模型输出（可能带 ```json 包裹/前后文字），并校验工具名合法性 */
    private List<WorkflowStepDraft> parseAndValidate(String content) {
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
                String tool = node.path("tool").asText("");
                if (tool.isBlank()) {
                    continue;
                }
                if (!toolRegistry.has(tool)) {
                    throw new ApiException(ErrorCode.INVALID_ARGUMENT, "模型生成了未登记的工具: " + tool);
                }
                JsonNode params = node.path("params");
                String paramsJson = (params == null || params.isMissingNode() || !params.isObject())
                        ? "{}" : objectMapper.writeValueAsString(params);
                steps.add(new WorkflowStepDraft(
                        tool,
                        paramsJson,
                        node.path("output_var").asText(""),
                        node.path("goal").asText("")
                ));
            }
            return steps;
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("工作流草案 JSON 解析失败: {}", ex.getMessage());
            return List.of();
        }
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

    /** 把步骤草案序列化为可直接提交创建工作流的 stepsJson（tool/params/output_var/goal） */
    private String toStepsJson(List<WorkflowStepDraft> steps) {
        try {
            ArrayNode array = objectMapper.createArrayNode();
            for (WorkflowStepDraft step : steps) {
                ObjectNode node = array.addObject();
                node.put("tool", step.tool());
                if (step.paramsJson() != null && !step.paramsJson().isBlank()) {
                    node.set("params", objectMapper.readTree(step.paramsJson()));
                } else {
                    node.putObject("params");
                }
                node.put("output_var", step.outputVar());
                if (step.goal() != null && !step.goal().isBlank()) {
                    node.put("goal", step.goal());
                }
            }
            return objectMapper.writeValueAsString(array);
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "工作流草案序列化失败");
        }
    }
}

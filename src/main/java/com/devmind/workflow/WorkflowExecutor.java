package com.devmind.workflow;

import com.devmind.agent.ToolRegistry;
import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流轻量执行器：按 steps_json 顺序调度工具，支持 {{var}} 步骤间数据传递，
 * 复用 {@link ToolRegistry}（含动态接口工具）。防重叠执行（同一工作流 RUNNING 时拒绝）。
 */
@Component
public class WorkflowExecutor {

    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutor.class);

    private final ToolRegistry toolRegistry;
    private final WorkflowRunRepository runRepository;
    private final ObjectMapper objectMapper;

    public WorkflowExecutor(
            ToolRegistry toolRegistry,
            WorkflowRunRepository runRepository,
            ObjectMapper objectMapper
    ) {
        this.toolRegistry = toolRegistry;
        this.runRepository = runRepository;
        this.objectMapper = objectMapper;
    }

    /** 工作流中的一个步骤 */
    public record WorkflowStep(String tool, String paramsJson, String outputVar) {
    }

    /**
     * 执行工作流（同步）：逐步骤执行并记录；任一步骤失败则整次 FAILED 停止。
     *
     * @return 执行结果（含状态与错误）
     */
    public WorkflowRun execute(Workflow workflow, Long userId, String triggerType) {
        if (runRepository.hasRunning(workflow.tenantId(), workflow.id())) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "工作流正在执行中，请稍后再试");
        }
        List<WorkflowStep> steps = parseSteps(workflow.stepsJson());
        if (steps == null || steps.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "工作流步骤为空");
        }
        Long runId = runRepository.insertRun(workflow.id(), workflow.tenantId(), triggerType);
        Map<String, Object> vars = new LinkedHashMap<>();
        String failure = null;
        for (int i = 0; i < steps.size(); i++) {
            WorkflowStep step = steps.get(i);
            String input = fillTemplate(step.paramsJson(), vars);
            long start = System.currentTimeMillis();
            try {
                String output = toolRegistry.execute(step.tool(), input, userId);
                long costMs = System.currentTimeMillis() - start;
                runRepository.insertStep(runId, i, step.tool(), input, output, "SUCCESS", costMs, null);
                if (step.outputVar() != null && !step.outputVar().isBlank()) {
                    vars.put(step.outputVar(), output);
                }
                log.info("工作流 {} 步骤 {} 成功 (tool={}, cost={}ms)", workflow.name(), i + 1, step.tool(), costMs);
            } catch (Exception ex) {
                long costMs = System.currentTimeMillis() - start;
                String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                runRepository.insertStep(runId, i, step.tool(), input, null, "FAILED", costMs, message);
                log.warn("工作流 {} 步骤 {} 失败 (tool={}): {}", workflow.name(), i + 1, step.tool(), message);
                failure = message;
                break;
            }
        }
        runRepository.finishRun(runId, failure == null ? "SUCCESS" : "FAILED", failure);
        return runRepository.findRun(workflow.tenantId(), runId);
    }

    private List<WorkflowStep> parseSteps(String stepsJson) {
        try {
            JsonNode array = objectMapper.readTree(stepsJson);
            if (!array.isArray() || array.isEmpty()) {
                return null;
            }
            List<WorkflowStep> steps = new ArrayList<>();
            for (JsonNode node : array) {
                String tool = node.path("tool").asText("");
                if (tool.isBlank()) {
                    continue;
                }
                JsonNode params = node.path("params");
                String paramsJson = (params == null || params.isMissingNode() || params.isNull())
                        ? "{}" : objectMapper.writeValueAsString(params);
                steps.add(new WorkflowStep(tool, paramsJson, node.path("output_var").asText("")));
            }
            return steps.isEmpty() ? null : steps;
        } catch (Exception ex) {
            log.warn("工作流步骤解析失败: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * 模板填充：在 JSON 节点层面替换字符串字段中的 {{key}} 为上一步输出。
     * 使用 Jackson 序列化，变量值（可能是 JSON 文本）会被正确转义，保证结果 JSON 合法。
     * 变量作用域仅本次 run。
     */
    private String fillTemplate(String paramsJson, Map<String, Object> vars) {
        if (paramsJson == null || vars.isEmpty()) {
            return paramsJson;
        }
        try {
            JsonNode root = objectMapper.readTree(paramsJson);
            transform(root, vars);
            return objectMapper.writeValueAsString(root);
        } catch (Exception ex) {
            // 参数本身非法：退化为简单字符串替换（保底）
            log.warn("工作流模板填充回退到字符串替换: {}", ex.getMessage());
            String result = paramsJson;
            for (Map.Entry<String, Object> entry : vars.entrySet()) {
                result = result.replace("{{" + entry.getKey() + "}}", String.valueOf(entry.getValue()));
            }
            return result;
        }
    }

    private void transform(JsonNode node, Map<String, Object> vars) {
        if (node.isObject()) {
            List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                JsonNode child = node.get(key);
                if (child != null && child.isTextual()) {
                    String text = child.asText();
                    String replaced = text;
                    for (Map.Entry<String, Object> entry : vars.entrySet()) {
                        replaced = replaced.replace("{{" + entry.getKey() + "}}", String.valueOf(entry.getValue()));
                    }
                    if (!replaced.equals(text)) {
                        ((ObjectNode) node).put(key, replaced);
                    }
                } else if (child != null) {
                    transform(child, vars);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                transform(item, vars);
            }
        }
    }
}

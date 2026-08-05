package com.devmind.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Agent 工具调用可靠性：对模型返回的 tool_calls 做执行前校验——
 * 工具名存在性、参数 JSON 合法性（含容错修复），防止幻觉工具名 / 畸形参数
 * 进入执行环节；配合执行超时与 {@code devmind.agent.tool_invalid} 指标实现生产级容错。
 */
@Component
public class ToolCallValidator {

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ToolCallValidator(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /** 校验结果：error 非空表示不可执行（应回填错误而非执行） */
    public record Validation(String toolName, String argumentsJson, String error) {
        public boolean valid() {
            return error == null;
        }
    }

    /** 校验并规范化工具调用 */
    public Validation validate(String toolName, String argumentsJson) {
        if (toolName == null || toolName.isBlank()) {
            return new Validation("", argumentsJson, "工具名为空");
        }
        if (!toolRegistry.has(toolName)) {
            return new Validation(toolName, argumentsJson, "未知工具: " + toolName);
        }
        String repaired = repairJson(argumentsJson);
        if (repaired == null) {
            return new Validation(toolName, argumentsJson, "工具参数不是合法 JSON");
        }
        return new Validation(toolName, repaired, null);
    }

    /**
     * 容错解析：优先整体解析；失败则提取最外层 JSON 对象块（模型常输出带前后缀的 JSON）；
     * 仍失败返回 null（畸形参数）。
     */
    String repairJson(String raw) {
        if (raw == null) {
            return "{}";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return "{}";
        }
        if (isJsonObject(trimmed)) {
            return trimmed;
        }
        int start = trimmed.indexOf('{');
        if (start >= 0) {
            int depth = 0;
            for (int i = start; i < trimmed.length(); i++) {
                char c = trimmed.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        String candidate = trimmed.substring(start, i + 1);
                        if (isJsonObject(candidate)) {
                            return candidate;
                        }
                        break;
                    }
                }
            }
        }
        return null;
    }

    private boolean isJsonObject(String text) {
        try {
            JsonNode node = objectMapper.readTree(text);
            return node != null && node.isObject();
        } catch (Exception ex) {
            return false;
        }
    }
}

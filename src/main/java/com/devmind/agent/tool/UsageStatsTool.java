package com.devmind.agent.tool;

import com.devmind.agent.AgentTool;
import com.devmind.audit.ToolAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * usage_stats：查询当前用户近期的工具调用与工作流运行统计（C2）。
 * 与 usage_query（模型 Token/费用）互补：这里统计的是平台侧的工具调用与工作流运行情况。
 */
@Component
public class UsageStatsTool implements AgentTool {

    private final ToolAuditService auditService;
    private final ObjectMapper objectMapper;

    public UsageStatsTool(ToolAuditService auditService, ObjectMapper objectMapper) {
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "usage_stats";
    }

    @Override
    public String description() {
        return "查询当前用户近期的平台使用统计：按工具聚合的调用次数/成功率/平均耗时、按工作流聚合的运行次数/成功率。"
                + "当用户询问工具调用情况、工作流运行情况、最近使用了哪些能力、成功率如何时调用。"
                + "参数：days(可选，统计最近几天，默认7，最多90)。"
                + "（模型 Token 用量/费用请用 usage_query。）";
    }

    @Override
    public String parametersJsonSchema() {
        return """
                {"type":"object","properties":{
                  "days":{"type":"integer","description":"统计最近几天，默认7，最多90"}
                }}
                """;
    }

    @Override
    public String execute(String argumentsJson, Long userId) {
        try {
            int days = 7;
            if (argumentsJson != null && !argumentsJson.isBlank()) {
                days = objectMapper.readTree(argumentsJson).path("days").asInt(7);
            }
            int safeDays = Math.max(1, Math.min(days, 90));
            List<Map<String, Object>> toolStats = auditService.toolStats(userId, safeDays);
            List<Map<String, Object>> workflowStats = auditService.workflowStats(userId, safeDays);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("days", safeDays);
            result.put("toolStats", toolStats);
            result.put("workflowStats", workflowStats);
            return objectMapper.writeValueAsString(result);
        } catch (Exception ex) {
            return "{\"error\": \"usage_stats 执行失败: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()) + "\"}";
        }
    }
}

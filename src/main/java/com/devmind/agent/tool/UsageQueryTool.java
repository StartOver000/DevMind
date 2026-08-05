package com.devmind.agent.tool;

import com.devmind.agent.AgentTool;
import com.devmind.modelusage.ModelUsageService;
import com.devmind.modelusage.dto.ModelUsageSummaryResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * usage_query：查询当前用户的模型用量与估算费用。
 */
@Component
public class UsageQueryTool implements AgentTool {

    private final ModelUsageService modelUsageService;
    private final ObjectMapper objectMapper;

    public UsageQueryTool(ModelUsageService modelUsageService, ObjectMapper objectMapper) {
        this.modelUsageService = modelUsageService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "usage_query";
    }

    @Override
    public String description() {
        return "查询当前用户的模型用量与估算费用（调用次数、输入/输出 Token、估算费用）。当用户询问模型用量、费用、消耗、配额时调用。无参数。";
    }

    @Override
    public String parametersJsonSchema() {
        return "{\"type\":\"object\",\"properties\":{}}";
    }

    @Override
    public String execute(String argumentsJson, Long userId) {
        ModelUsageSummaryResponse summary = modelUsageService.summary(userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCalls", summary.totalCalls());
        result.put("promptTokens", summary.promptTokens());
        result.put("completionTokens", summary.completionTokens());
        result.put("estimatedCost", summary.estimatedCost());
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化用量结果失败", ex);
        }
    }
}

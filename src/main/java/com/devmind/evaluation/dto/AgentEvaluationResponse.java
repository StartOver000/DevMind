package com.devmind.evaluation.dto;

import java.util.List;

/** Agent 评估报告（含 Plan-Execute 统计） */
public record AgentEvaluationResponse(
        int total,
        int passed,
        double passRate,
        int planUsedCount,
        int replanCount,
        List<AgentEvalItem> items
) {
}

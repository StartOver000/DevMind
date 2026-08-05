package com.devmind.evaluation.dto;

import java.util.List;

/** Agent 评估报告 */
public record AgentEvaluationResponse(
        int total,
        int passed,
        double passRate,
        List<AgentEvalItem> items
) {
}

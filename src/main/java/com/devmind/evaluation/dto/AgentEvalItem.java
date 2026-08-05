package com.devmind.evaluation.dto;

import java.util.List;

/** Agent 评估单项结果 */
public record AgentEvalItem(
        String question,
        List<String> expectedTools,
        List<String> calledTools,
        boolean toolMatch,
        boolean toolsOk,
        int answerLength
) {
}

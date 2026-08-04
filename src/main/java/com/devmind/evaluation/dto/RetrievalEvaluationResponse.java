package com.devmind.evaluation.dto;

import java.util.List;

public record RetrievalEvaluationResponse(
        int total,
        int hits,
        double hitRate,
        List<EvaluationItem> items,
        List<EvaluationTopicResult> topics
) {
}

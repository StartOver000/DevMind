package com.devmind.evaluation.dto;

public record EvaluationTopicResult(
        String topic,
        int total,
        int hits,
        double hitRate
) {
}

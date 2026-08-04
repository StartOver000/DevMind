package com.devmind.evaluation.dto;

import java.util.List;

public record EvaluationItem(
        String question,
        String expectedKeyword,
        boolean hit,
        int retrieved,
        List<Long> chunkIds
) {
}

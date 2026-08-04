package com.devmind.performance.dto;

public record RetrievalBenchmarkResponse(
        Long knowledgeBaseId,
        String question,
        int iterations,
        long totalMs,
        double avgMs,
        int returned
) {
}

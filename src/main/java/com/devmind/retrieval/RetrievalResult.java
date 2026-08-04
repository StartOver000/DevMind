package com.devmind.retrieval;

import java.util.Map;

public record RetrievalResult(
        Long chunkId,
        Long documentId,
        String documentName,
        Integer chunkIndex,
        String content,
        Map<String, Object> metadata,
        double similarityScore
) {
}

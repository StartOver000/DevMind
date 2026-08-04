package com.devmind.retrieval;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Component
public class HeuristicReranker {

    public List<RetrievalResult> rerank(List<RetrievalResult> results, String question) {
        String lowerQuestion = question.toLowerCase(Locale.ROOT);
        return results.stream()
                .sorted(Comparator.comparingDouble(
                        (RetrievalResult result) -> result.similarityScore() + overlapBoost(result, lowerQuestion)
                ).reversed())
                .toList();
    }

    private double overlapBoost(RetrievalResult result, String lowerQuestion) {
        String content = result.content().toLowerCase(Locale.ROOT);
        List<String> keywords = KeywordExtractor.extract(lowerQuestion, 10);
        long matched = keywords.stream().filter(content::contains).count();
        return Math.min(0.1, matched * 0.02);
    }
}

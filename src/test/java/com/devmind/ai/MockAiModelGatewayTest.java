package com.devmind.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockAiModelGatewayTest {

    @Test
    void similarTextsScoreHigherThanUnrelatedTexts() {
        MockAiModelGateway gateway = new MockAiModelGateway(64);
        List<Double> first = gateway.embed(List.of("MySQL 深分页为什么慢")).get(0);
        List<Double> second = gateway.embed(List.of("MySQL 深分页查询变慢")).get(0);
        List<Double> unrelated = gateway.embed(List.of("Redis 缓存雪崩怎么办")).get(0);

        assertThat(cosine(first, second)).isGreaterThan(cosine(first, unrelated));
    }

    private double cosine(List<Double> a, List<Double> b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
            na += a.get(i) * a.get(i);
            nb += b.get(i) * b.get(i);
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}

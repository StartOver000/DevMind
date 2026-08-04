package com.devmind.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

public class MockAiModelGateway implements AiModelGateway {

    private final int dimensions;

    public MockAiModelGateway(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public List<List<Double>> embed(List<String> texts) {
        return texts.stream().map(this::embedOne).toList();
    }

    @Override
    public ChatResult chat(String systemPrompt, String userPrompt) {
        String excerpt = userPrompt.substring(0, Math.min(userPrompt.length(), 300));
        return new ChatResult(
                "模拟模型：已根据知识库上下文生成回答，请配置真实模型后查看最终答案。\n\n" + excerpt,
                "mock-chat",
                0,
                0
        );
    }

    private List<Double> embedOne(String text) {
        double[] vector = new double[dimensions];
        String normalized = text.toLowerCase();
        for (int i = 0; i + 3 <= normalized.length(); i++) {
            String gram = normalized.substring(i, i + 3);
            int hash = gram.hashCode() & 0x7fffffff;
            int index = hash % dimensions;
            vector[index] += (hash % 997 + 1) / 997.0;
        }
        if (isZero(vector)) {
            vector[0] = 1.0;
        }
        normalize(vector);
        return Arrays.stream(vector).boxed().toList();
    }

    private boolean isZero(double[] vector) {
        for (double value : vector) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private void normalize(double[] vector) {
        double sum = 0;
        for (double value : vector) {
            sum += value * value;
        }
        double norm = Math.sqrt(sum);
        if (norm == 0) {
            return;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= norm;
        }
    }
}

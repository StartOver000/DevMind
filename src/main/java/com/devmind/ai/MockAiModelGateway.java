package com.devmind.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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

    /**
     * 确定性工具调用（测试/演示用）：
     * - 问题含 sql/慢 关键字 → 返回 sql_diagnose 工具调用
     * - 问题含 检索/方案/优化/查 关键字 → 返回 kb_search 工具调用
     * - 已携带 tool 消息（工具结果已回填）→ 直接返回总结文本，不再调用工具
     */
    @Override
    public ChatResult chatWithTools(String systemPrompt, List<Map<String, Object>> messages, List<ToolSpec> tools) {
        boolean hasToolResult = messages.stream().anyMatch(m -> "tool".equals(m.get("role")));
        String userText = messages.stream()
                .filter(m -> "user".equals(m.get("role")))
                .map(m -> String.valueOf(m.get("content")))
                .reduce("", (a, b) -> a + " " + b);
        String lower = userText.toLowerCase();

        if (hasToolResult) {
            return new ChatResult(
                    "模拟模型：已基于工具结果完成分析。\n\n（mock 模式：工具已执行，配置真实模型后由模型总结）",
                    "mock-chat",
                    0,
                    0,
                    List.of()
            );
        }
        if (lower.contains("sql") || lower.contains("慢") || lower.contains("explain")) {
            return new ChatResult(
                    "",
                    "mock-chat",
                    0,
                    0,
                    List.of(new ToolCall("call_mock_sql", "sql_diagnose",
                            "{\"sql\":\"SELECT * FROM orders ORDER BY created_time LIMIT 100000, 20\"}"))
            );
        }
        if (lower.contains("检索") || lower.contains("方案") || lower.contains("优化")
                || lower.contains("知识库") || lower.contains("查")) {
            return new ChatResult(
                    "",
                    "mock-chat",
                    0,
                    0,
                    List.of(new ToolCall("call_mock_kb", "kb_search",
                            "{\"question\":\"深分页优化方案\"}"))
            );
        }
        return new ChatResult(
                "模拟模型：这是直接回答（无工具调用）。\n\n" + userText.substring(0, Math.min(userText.length(), 200)),
                "mock-chat",
                0,
                0,
                List.of()
        );
    }

    /**
     * 确定性伪向量（测试/演示用）：字符级 1-gram + 2-gram 的 hash 桶叠加。
     * 中文单字 + 相邻两字兼顾"短查询 vs 长文本"的语义重叠：共享"订单/库存/客户"
     * 等 2 字词（及单字）的文本相似度高，无关文本相似度≈0，可在 mock 模式下
     * 演示语义检索命中（余弦相似度区分度优于纯 3-gram，后者对中文短查询完全失配）。
     */
    private List<Double> embedOne(String text) {
        double[] vector = new double[dimensions];
        String normalized = text.toLowerCase();
        for (int n = 1; n <= 2; n++) {
            for (int i = 0; i + n <= normalized.length(); i++) {
                String gram = normalized.substring(i, i + n);
                int hash = gram.hashCode() & 0x7fffffff;
                int index = hash % dimensions;
                vector[index] += (hash % 997 + 1) / 997.0;
            }
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

package com.devmind.ai;

import com.devmind.config.DevMindProperties;
import com.devmind.security.SecretCipher;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class ZhipuRestModelGateway implements AiModelGateway {

    private static final Logger log = LoggerFactory.getLogger(ZhipuRestModelGateway.class);

    private final RestClient.Builder restClientBuilder;
    private final DevMindProperties properties;
    private final SecretCipher secretCipher;
    private final ObjectMapper objectMapper;

    public ZhipuRestModelGateway(
            RestClient.Builder restClientBuilder,
            DevMindProperties properties,
            SecretCipher secretCipher,
            ObjectMapper objectMapper
    ) {
        this.restClientBuilder = restClientBuilder;
        this.properties = properties;
        this.secretCipher = secretCipher;
        this.objectMapper = objectMapper;
    }

    private String apiKey() {
        return secretCipher.resolve(properties.zhipuApiKey());
    }

    /** 智谱 embedding-2 单请求 input 数组上限（code 1214） */
    private static final int EMBED_BATCH_SIZE = 24;

    @Override
    public List<List<Double>> embed(List<String> texts) {
        // 防御一：智谱 embedding 不接受 input 数组含 null（HTTP 400 code 1210），null 统一转空串
        List<String> cleaned = texts.stream().map(t -> t == null ? "" : t).toList();
        // 防御二：智谱 embedding-2 单请求 input 数组上限 24 条（code 1214），分批调用后合并
        List<List<Double>> all = new ArrayList<>();
        for (int from = 0; from < cleaned.size(); from += EMBED_BATCH_SIZE) {
            List<String> batch = cleaned.subList(from, Math.min(from + EMBED_BATCH_SIZE, cleaned.size()));
            List<String> batchCopy = new ArrayList<>(batch);
            EmbeddingResponse response = retry(() -> {
                RestClient client = client();
                return client.post()
                        .uri("/embeddings")
                        .header("Authorization", "Bearer " + apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("model", properties.zhipuEmbeddingModel(), "input", batchCopy))
                        .retrieve()
                        .body(EmbeddingResponse.class);
            });
            if (response == null || response.data() == null) {
                throw new IllegalStateException("智谱向量接口返回为空");
            }
            all.addAll(response.data().stream().map(EmbeddingResponse.Data::embedding).toList());
        }
        return all;
    }

    @Override
    public ChatResult chat(String systemPrompt, String userPrompt) {
        Map<String, Object> body = Map.of(
                "model", properties.zhipuChatModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                ),
                "max_tokens", properties.zhipuMaxTokens(),
                "temperature", 0.2,
                "thinking", Map.of("type", "enabled")
        );
        ChatCompletionResponse response = retry(() -> {
            RestClient client = client();
            return client.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(ChatCompletionResponse.class);
        });
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new IllegalStateException("智谱聊天接口返回为空");
        }
        ChatCompletionResponse.Choice choice = response.choices().get(0);
        return new ChatResult(
                choice.message() == null ? "" : choice.message().content(),
                properties.zhipuChatModel(),
                response.usage() == null ? null : response.usage().promptTokens(),
                response.usage() == null ? null : response.usage().completionTokens(),
                choice.message() == null ? null : choice.message().reasoning_content(),
                null
        );
    }

    @Override
    public ChatResult chatWithTools(
            String systemPrompt,
            List<Map<String, Object>> messages,
            List<ToolSpec> tools
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.zhipuChatModel());
        body.put("messages", messages);
        body.put("max_tokens", properties.zhipuMaxTokens());
        body.put("temperature", 0.2);
        body.put("thinking", Map.of("type", "enabled"));
        body.put("tools", tools.stream()
                .map(tool -> Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", tool.name(),
                                "description", tool.description(),
                                "parameters", parseJson(tool.parametersJson())
                        )
                ))
                .toList());
        ChatCompletionResponse response = retry(() -> {
            RestClient client = client();
            return client.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(ChatCompletionResponse.class);
        });
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new IllegalStateException("智谱聊天接口返回为空");
        }
        ChatCompletionResponse.Message message = response.choices().get(0).message();
        List<ToolCall> toolCalls = null;
        if (message.tool_calls() != null && !message.tool_calls().isEmpty()) {
            toolCalls = message.tool_calls().stream()
                    .map(tc -> new ToolCall(
                            tc.id() == null ? "" : tc.id(),
                            tc.function() == null ? "" : tc.function().name(),
                            tc.function() == null || tc.function().arguments() == null
                                    ? "{}"
                                    : tc.function().arguments()
                    ))
                    .toList();
        }
        return new ChatResult(
                message == null || message.content() == null ? "" : message.content(),
                properties.zhipuChatModel(),
                response.usage() == null ? null : response.usage().promptTokens(),
                response.usage() == null ? null : response.usage().completionTokens(),
                message == null ? null : message.reasoning_content(),
                toolCalls
        );
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            log.warn("工具参数 JSON 解析失败: {}", ex.getMessage());
            return Map.of();
        }
    }

    /**
     * 流式聊天（token 级）：复用公共 {@link SseChatStreamer}（OpenAI 兼容 SSE 流），
     * 保留 thinking/max_tokens 参数；失败抛异常交给 ChatRouter 降级。
     */
    @Override
    public void streamChat(String systemPrompt, String userPrompt, Consumer<String> onToken) {
        SseChatStreamer.streamChatCompletions(
                properties.zhipuBaseUrl(),
                apiKey(),
                properties.zhipuChatModel(),
                List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                ),
                Map.of(
                        "max_tokens", properties.zhipuMaxTokens(),
                        "temperature", 0.2,
                        "thinking", Map.of("type", "enabled")
                ),
                objectMapper,
                onToken
        );
    }

    private RestClient client() {
        return restClientBuilder.baseUrl(properties.zhipuBaseUrl()).build();
    }

    private <T> T retry(Supplier<T> action) {
        int attempts = 3;
        Exception last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                return action.get();
            } catch (Exception ex) {
                last = ex;
                log.warn("zhipu api call failed, attempt={}, error={}", i + 1, ex.getMessage());
                // 429 限流 / 超时是账户级持续状态，短时重试大概率仍失败：
                // 直接放弃，让上层（关键词检索降级 / ChatRouter 熔断 + 本地 RAG）立即兜底，避免每次请求吃满退避。
                String message = ex.getMessage() == null ? "" : ex.getMessage();
                if (message.contains("429") || message.contains("Too Many")
                        || message.contains("timed out") || message.contains("Timeout")) {
                    break;
                }
                long baseDelay = 2000L;
                try {
                    Thread.sleep(baseDelay * (i + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new IllegalStateException("智谱接口调用失败", last);
    }

    public record ChatCompletionResponse(
            List<Choice> choices,
            Usage usage
    ) {
        public record Choice(Message message) {
        }

        public record Message(String content, String reasoning_content, List<ToolCallData> tool_calls) {
            public Message(String content) {
                this(content, null, null);
            }
        }

        public record ToolCallData(String id, String type, FunctionCall function) {
        }

        public record FunctionCall(String name, String arguments) {
        }

        public record Usage(
                Integer prompt_tokens,
                Integer completion_tokens
        ) {
            public Integer promptTokens() {
                return prompt_tokens;
            }

            public Integer completionTokens() {
                return completion_tokens;
            }
        }
    }

    public record EmbeddingResponse(List<Data> data) {
        public record Data(List<Double> embedding) {
        }
    }
}

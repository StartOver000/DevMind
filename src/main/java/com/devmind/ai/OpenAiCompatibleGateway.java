package com.devmind.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用 OpenAI 兼容模型网关：任意 OpenAI 兼容的 /chat/completions 端点
 * （如 OpenRouter、硅基流动等），支持普通聊天与 Function Calling（tools）。
 * 作为多 Provider 路由中的备用 Provider 使用；embedding 不在本网关职责内（返回不支持）。
 */
@SuppressWarnings("null")
public class OpenAiCompatibleGateway implements AiModelGateway {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleGateway.class);

    private final RestClient client;
    private final String apiKey;
    private final String chatModel;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleGateway(
            RestClient.Builder restClientBuilder,
            String baseUrl,
            String apiKey,
            String chatModel,
            ObjectMapper objectMapper
    ) {
        this.client = restClientBuilder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<List<Double>> embed(List<String> texts) {
        throw new UnsupportedOperationException("OpenAiCompatibleGateway 不提供 embedding（请配置专用 embedding Provider）");
    }

    @Override
    public ChatResult chat(String systemPrompt, String userPrompt) {
        Map<String, Object> body = Map.of(
                "model", chatModel,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                ),
                "temperature", 0.2
        );
        ChatCompletionResponse response = post(body);
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new IllegalStateException("备用模型返回为空");
        }
        return new ChatResult(
                response.choices().get(0).message() == null ? "" : response.choices().get(0).message().content(),
                chatModel,
                null,
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
        body.put("model", chatModel);
        body.put("messages", messages);
        body.put("temperature", 0.2);
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
        ChatCompletionResponse response = post(body);
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new IllegalStateException("备用模型返回为空");
        }
        ChatCompletionResponse.Message message = response.choices().get(0).message();
        List<ToolCall> toolCalls = null;
        if (message != null && message.tool_calls() != null && !message.tool_calls().isEmpty()) {
            toolCalls = message.tool_calls().stream()
                    .map(tc -> new ToolCall(
                            tc.id() == null ? "" : tc.id(),
                            tc.function() == null ? "" : tc.function().name(),
                            tc.function() == null || tc.function().arguments() == null ? "{}" : tc.function().arguments()
                    ))
                    .toList();
        }
        return new ChatResult(
                message == null || message.content() == null ? "" : message.content(),
                chatModel,
                null,
                null,
                toolCalls
        );
    }

    private ChatCompletionResponse post(Map<String, Object> body) {
        return client.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(ChatCompletionResponse.class);
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

    public record ChatCompletionResponse(List<Choice> choices) {
        public record Choice(Message message) {
        }

        public record Message(String content, List<ToolCallData> tool_calls) {
            public Message(String content) {
                this(content, null);
            }
        }

        public record ToolCallData(String id, String type, FunctionCall function) {
        }

        public record FunctionCall(String name, String arguments) {
        }
    }
}

package com.devmind.ai;

import java.util.List;
import java.util.Map;

public interface AiModelGateway {

    List<List<Double>> embed(List<String> texts);

    ChatResult chat(String systemPrompt, String userPrompt);

    /**
     * Agent 工具调用（OpenAI 兼容 function calling）。
     * 默认不支持，由支持工具调用的网关实现覆盖。
     *
     * @param systemPrompt 系统提示
     * @param messages     协议消息列表（system/user/assistant/tool）
     * @param tools        工具定义（function calling schema）
     */
    default ChatResult chatWithTools(String systemPrompt, List<Map<String, Object>> messages, List<ToolSpec> tools) {
        throw new UnsupportedOperationException("当前模型网关不支持工具调用");
    }

    /**
     * 流式聊天（token 级）：逐块回调生成的文本。
     * 默认实现：非流式网关用完整回答拆块模拟（约 8 字符/块），保证降级链/测试网关无需各自实现；
     * 支持流式的网关（如 ZhipuRestModelGateway）覆写为真实 token 流。
     */
    default void streamChat(String systemPrompt, String userPrompt, java.util.function.Consumer<String> onToken) {
        ChatResult result = chat(systemPrompt, userPrompt);
        String content = result == null || result.content() == null ? "" : result.content();
        for (int i = 0; i < content.length(); i += 8) {
            onToken.accept(content.substring(i, Math.min(i + 8, content.length())));
        }
    }

    record ChatResult(
            String content,
            String model,
            Integer promptTokens,
            Integer completionTokens,
            String reasoning,
            List<ToolCall> toolCalls
    ) {
        public ChatResult(String content, String model, Integer promptTokens, Integer completionTokens) {
            this(content, model, promptTokens, completionTokens, null, null);
        }

        public ChatResult(String content, String model, Integer promptTokens, Integer completionTokens, List<ToolCall> toolCalls) {
            this(content, model, promptTokens, completionTokens, null, toolCalls);
        }
    }

    /** 工具定义（Function Calling schema） */
    record ToolSpec(String name, String description, String parametersJson) {
    }

    /** 模型发起的工具调用 */
    record ToolCall(String id, String name, String argumentsJson) {
    }
}

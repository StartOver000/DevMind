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

    record ChatResult(
            String content,
            String model,
            Integer promptTokens,
            Integer completionTokens,
            List<ToolCall> toolCalls
    ) {
        public ChatResult(String content, String model, Integer promptTokens, Integer completionTokens) {
            this(content, model, promptTokens, completionTokens, null);
        }
    }

    /** 工具定义（Function Calling schema） */
    record ToolSpec(String name, String description, String parametersJson) {
    }

    /** 模型发起的工具调用 */
    record ToolCall(String id, String name, String argumentsJson) {
    }
}

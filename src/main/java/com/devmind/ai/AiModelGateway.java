package com.devmind.ai;

import java.util.List;

public interface AiModelGateway {

    List<List<Double>> embed(List<String> texts);

    ChatResult chat(String systemPrompt, String userPrompt);

    record ChatResult(
            String content,
            String model,
            Integer promptTokens,
            Integer completionTokens
    ) {
    }
}

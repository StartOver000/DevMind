package com.devmind.ai;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.List;

public class SpringAiModelGateway implements AiModelGateway {

    private final EmbeddingModel embeddingModel;
    private final ChatModel chatModel;

    public SpringAiModelGateway(EmbeddingModel embeddingModel, ChatModel chatModel) {
        this.embeddingModel = embeddingModel;
        this.chatModel = chatModel;
    }

    @Override
    public List<List<Double>> embed(List<String> texts) {
        List<float[]> embeddings = embeddingModel.embed(texts);
        return embeddings.stream()
                .map(this::toDoubles)
                .toList();
    }

    @Override
    public ChatResult chat(String systemPrompt, String userPrompt) {
        Prompt prompt = new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt)));
        ChatResponse response = chatModel.call(prompt);
        String content = response.getResult().getOutput().getText();
        ChatResponseMetadata metadata = response.getMetadata();
        Integer promptTokens = metadata.getUsage() == null ? null : metadata.getUsage().getPromptTokens();
        Integer completionTokens = metadata.getUsage() == null ? null : metadata.getUsage().getCompletionTokens();
        return new ChatResult(content, metadata.getModel(), promptTokens, completionTokens);
    }

    private List<Double> toDoubles(float[] values) {
        List<Double> result = new ArrayList<>(values.length);
        for (float value : values) {
            result.add((double) value);
        }
        return result;
    }
}

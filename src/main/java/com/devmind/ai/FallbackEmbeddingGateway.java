package com.devmind.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Embedding 备用装饰器：主网关（如智谱 embedding）失败（429/超时/异常）时，
 * 自动切换到备用 Embedding 服务（OpenAI 兼容 /embeddings，如硅基流动 bge-m3）。
 * 维度要求与主模型一致（1024 维），保证 pgvector 索引兼容。
 * chat / chatWithTools 原样透传（聊天备用由 {@link ChatRouter} 负责）。
 */
@SuppressWarnings("null")
public class FallbackEmbeddingGateway implements AiModelGateway {

    private static final Logger log = LoggerFactory.getLogger(FallbackEmbeddingGateway.class);

    /** 备用 embedding 调用抽象（便于测试注入） */
    public interface FallbackEmbeddingCaller {
        List<List<Double>> call(List<String> texts);
    }

    private final AiModelGateway delegate;
    private final FallbackEmbeddingCaller fallbackCaller;

    /** 生产构造：内部用 RestClient 调备用 /embeddings */
    public FallbackEmbeddingGateway(
            AiModelGateway delegate,
            RestClient.Builder restClientBuilder,
            String baseUrl,
            String apiKey,
            String fallbackModel
    ) {
        this.delegate = delegate;
        this.fallbackCaller = texts -> {
            FallbackEmbeddingResponse response = restClientBuilder.baseUrl(baseUrl).build().post()
                    .uri("/embeddings")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", fallbackModel, "input", texts))
                    .retrieve()
                    .body(FallbackEmbeddingResponse.class);
            if (response == null || response.data() == null) {
                throw new IllegalStateException("备用 embedding 接口返回为空");
            }
            return response.data().stream().map(FallbackEmbeddingResponse.Data::embedding).toList();
        };
    }

    /** 测试构造：注入自定义调用器 */
    public FallbackEmbeddingGateway(AiModelGateway delegate, FallbackEmbeddingCaller fallbackCaller) {
        this.delegate = delegate;
        this.fallbackCaller = fallbackCaller;
    }

    @Override
    public List<List<Double>> embed(List<String> texts) {
        try {
            return delegate.embed(texts);
        } catch (Exception ex) {
            log.warn("主 embedding 调用失败（{}），切换到备用模型", ex.getMessage());
            return fallbackCaller.call(texts);
        }
    }

    @Override
    public ChatResult chat(String systemPrompt, String userPrompt) {
        return delegate.chat(systemPrompt, userPrompt);
    }

    @Override
    public ChatResult chatWithTools(
            String systemPrompt,
            List<Map<String, Object>> messages,
            List<ToolSpec> tools
    ) {
        return delegate.chatWithTools(systemPrompt, messages, tools);
    }

    public record FallbackEmbeddingResponse(List<Data> data) {
        public record Data(List<Double> embedding) {
        }
    }
}

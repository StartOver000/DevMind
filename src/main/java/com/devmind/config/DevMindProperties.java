package com.devmind.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "devmind")
public record DevMindProperties(
        @DefaultValue("openai") String modelMode,
        @DefaultValue("./data/files") String storagePath,
        @DefaultValue("20") long maxFileSizeMb,
        @DefaultValue("md,markdown,pdf") String allowedFileTypes,
        @DefaultValue("1500") int maxChunkChars,
        @DefaultValue("200") int overlapChars,
        @DefaultValue("boundary") String chunkerStrategy,
        @DefaultValue("1536") int embeddingDimensions,
        @DefaultValue("5") int retrievalTopK,
        @DefaultValue("10") int retrievalMaxTopK,
        @DefaultValue("0.35") double retrievalMinScore,
        @DefaultValue("4") int taskThreadPoolSize,
        @DefaultValue("3") int taskMaxRetries,
        @DefaultValue("5000") long taskRetryDelayMs,
        @DefaultValue("5") int taskTimeoutMinutes,
        @DefaultValue("60000") long taskScanIntervalMs,
        @DefaultValue("60000") long taskScanInitialDelayMs,
        @DefaultValue("0.7") double retrievalVectorWeight,
        @DefaultValue("0.3") double retrievalKeywordWeight,
        @DefaultValue("true") boolean retrievalHybridEnabled,
        @DefaultValue("mock") String sqlDiagnosisMode,
        @DefaultValue("mysql") String sqlDiagnosisDatasourceType,
        @DefaultValue("") String sqlDiagnosisJdbcUrl,
        @DefaultValue("") String sqlDiagnosisUsername,
        @DefaultValue("") String sqlDiagnosisPassword,
        @DefaultValue("2000") int sqlDiagnosisMaxSqlLength,
        @DefaultValue("heuristic") String rerankMode,
        @DefaultValue("5") int evaluationTopK,
        @DefaultValue("0.00015") double costPromptPer1k,
        @DefaultValue("0.0006") double costCompletionPer1k,
        @DefaultValue("") String modelFallbackBaseUrl,
        @DefaultValue("") String modelFallbackApiKey,
        @DefaultValue("") String modelFallbackChatModel,
        @DefaultValue("") String zhipuBaseUrl,
        @DefaultValue("") String zhipuApiKey,
        @DefaultValue("glm-4.7-flash") String zhipuChatModel,
        @DefaultValue("embedding-2") String zhipuEmbeddingModel,
        @DefaultValue("2000") int zhipuMaxTokens,
        @DefaultValue("false") boolean authEnabled,
        @DefaultValue("true") boolean localRagFallback
) {
}

package com.devmind.retrieval;

import java.util.List;

/**
 * 本地 RAG 兜底回答生成器。
 *
 * 当大模型（智谱/备用模型）不可用时，不直接报错，而是基于已检索到的
 * 知识库片段用本地规则拼装回答。质量低于大模型润色，但保证：
 * 1. 用户始终能拿到基于知识库的回答（不 502）；
 * 2. 回答明确标注「本地降级模式」，避免用户误以为是模型结论。
 */
public final class LocalRagAnswerer {

    private static final String DEGRADED_NOTICE =
            "（本地降级模式：大模型暂不可用，以下为知识库原文摘要，未经过模型润色）\n";

    private LocalRagAnswerer() {
    }

    /**
     * 基于检索结果生成本地回答。
     *
     * @param question 用户问题
     * @param results  已排序的检索结果（按相似度降序）
     * @return 降级回答文本
     */
    public static String answer(String question, List<RetrievalResult> results) {
        if (results == null || results.isEmpty()) {
            return "知识库中没有找到足够相关内容。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(DEGRADED_NOTICE);
        sb.append("关于「").append(question.trim()).append("」，知识库中检索到以下相关内容：\n\n");
        for (int i = 0; i < results.size(); i++) {
            RetrievalResult result = results.get(i);
            sb.append('[').append(i + 1).append("] 来源：").append(result.documentName());
            Object heading = result.metadata() == null ? null : result.metadata().get("heading");
            if (heading != null) {
                sb.append("#").append(heading);
            }
            sb.append("（相似度 ").append(String.format("%.4f", result.similarityScore())).append("）\n");
            String content = result.content() == null ? "" : result.content().strip();
            if (content.length() > 500) {
                content = content.substring(0, 500) + "…";
            }
            sb.append(content).append("\n\n");
        }
        sb.append("提示：以上内容直接摘录自知识库原文。大模型恢复后重新提问可获得更完整的回答。");
        return sb.toString();
    }
}

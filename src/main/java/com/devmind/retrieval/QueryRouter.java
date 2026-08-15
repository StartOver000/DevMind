package com.devmind.retrieval;

/**
 * 查询路由：选择混合检索的权重策略。
 * <p>
 * 2026-08-15 修正：统一向量主导（0.9/0.1）。旧实现按问题特征切"keyword-first"
 * （含 RAG/LLM/API 等术语 → 0.4/0.6），该策略是向量检索质量不足时用关键词兜底的
 * 旧设计；bge-m3 向量空间修复后纯向量检索已高度准确（KB19/KB20 在 0.9 权重下
 * 评估全绿），而 keyword-first 的 0.6 关键词权重会让"仅关键词命中的噪声 chunk"
 * （如只含 LLM 一词的语音文档，0.6×0.5=0.30）压过向量高度相关的文档
 * （如 LLM 网关 0.4×0.68=0.27），把真相关文档挤出 top-K，导致 chat 答错
 * （"知识库未提及 LLM 网关"）。统一向量主导后该问题消失。
 */
public final class QueryRouter {

    public record Route(double vectorWeight, double keywordWeight, String mode) {
    }

    private QueryRouter() {
    }

    public static Route route(String question) {
        return new Route(0.9, 0.1, "hybrid");
    }
}

package com.devmind.retrieval;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 查询路由：按问题特征选择混合检索的权重策略。
 * - 含代码/术语/大写缩写（如 EXPLAIN、LIMIT、RAG、Nginx）-> 关键词优先（关键词 0.6）；
 * - 其他口语/长问句 -> 向量优先（默认 0.7/0.3）。
 */
public final class QueryRouter {

    private static final Pattern CODE_TERM = Pattern.compile(
            "(?i)\\b(EXPLAIN|LIMIT|OFFSET|JOIN|INDEX|Nginx|RAG|SQL|URL|HTTPS|JSON|API|BM25|POSTGRES|MYSQL|VECTOR|HNSW)\\b"
    );

    private static final Pattern CAPS_ACRONYM = Pattern.compile("\\b[A-Z]{2,}\\b");

    public record Route(double vectorWeight, double keywordWeight, String mode) {
    }

    private QueryRouter() {
    }

    public static Route route(String question) {
        if (question == null || question.isBlank()) {
            return new Route(0.7, 0.3, "hybrid");
        }
        boolean codeOrTerm = CODE_TERM.matcher(question).find()
                || CAPS_ACRONYM.matcher(question).find()
                || containsCodeLike(question);
        if (codeOrTerm) {
            return new Route(0.4, 0.6, "keyword-first");
        }
        return new Route(0.7, 0.3, "hybrid");
    }

    private static boolean containsCodeLike(String question) {
        String lower = question.toLowerCase(Locale.ROOT);
        return lower.contains("select ") || lower.contains("where ")
                || lower.contains("order by") || lower.contains("group by")
                || lower.contains("type=") || lower.contains(" filesort")
                || lower.contains(" temporary") || lower.contains("offset ");
    }
}

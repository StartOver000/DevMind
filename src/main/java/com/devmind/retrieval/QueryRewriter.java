package com.devmind.retrieval;

import java.util.List;

/**
 * 查询改写（启发式，无模型依赖）：
 * 结合多轮对话历史，把含指代词的追问改写成完整的检索查询。
 * 例如：历史「深分页怎么优化」，追问「它为什么慢」-> 「深分页 为什么慢」。
 */
public final class QueryRewriter {

    private static final List<String> PRONOUNS = List.of(
            "它", "它们", "这个", "这些", "那个", "那些", "上述", "上面", "该", "其"
    );

    private static final List<String> QUESTION_WORDS = List.of(
            "为什么", "怎么", "如何", "怎样", "是什么", "有哪些", "怎么办", "哪些", "什么",
            "解决", "处理", "优化", "排查", "定位", "提升",
            "场景", "问题", "好处", "原理", "方法", "步骤", "情况"
    );

    private QueryRewriter() {
    }

    public static String rewrite(String question, List<String> historyQuestions) {
        if (question == null || question.isBlank()) {
            return question;
        }
        String trimmed = question.trim();
        boolean hasPronoun = PRONOUNS.stream().anyMatch(trimmed::contains);
        if (!hasPronoun || historyQuestions == null || historyQuestions.isEmpty()) {
            return trimmed;
        }
        String lastHistory = historyQuestions.get(historyQuestions.size() - 1);
        String topic = stripQuestionWords(lastHistory);
        if (topic.isBlank()) {
            return trimmed;
        }
        String remainder = trimmed;
        for (String pronoun : PRONOUNS) {
            remainder = remainder.replace(pronoun, "");
        }
        remainder = remainder.replace("?", "").replace("？", "").trim();
        if (remainder.isBlank()) {
            return topic;
        }
        return topic + " " + remainder;
    }

    static String stripQuestionWords(String text) {
        String result = text;
        for (String word : QUESTION_WORDS) {
            result = result.replace(word, " ");
        }
        return result.replaceAll("\\s+", " ").trim();
    }
}

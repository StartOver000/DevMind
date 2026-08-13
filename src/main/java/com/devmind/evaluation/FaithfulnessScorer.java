package com.devmind.evaluation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 生成质量——Faithfulness 打分（P2-5，八股反推）。
 *
 * Faithfulness 衡量"答案是否忠于检索片段"（不编造）。本项目采用**规则近似**（纯函数、可单测、
 * mock 模式可用）：抽取答案中的"事实性 token"（数字、英文单词），检查是否都出现在检索片段文本中。
 * 命中比例即 faithfulness（0~1）。
 *
 * 说明：生产级可用 LLM 判断，但依赖外部模型且不可离线复现；规则近似作为第一版护栏足够，
 * 且能纳入离线评估报告 + 基线对比（与检索质量护栏同模式）。
 */
public final class FaithfulnessScorer {

    /** 数字（含小数/负数/百分比/逗号分隔） */
    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?%?");
    /** 英文单词（长度 >= 4，过滤常见虚词） */
    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z]{3,}");
    private static final Set<String> STOPWORDS = Set.of(
            "that", "this", "with", "from", "have", "your", "will", "what", "when",
            "which", "there", "their", "about", "would", "could", "should", "into",
            "over", "than", "them", "then", "these", "those", "where", "while"
    );

    private FaithfulnessScorer() {
    }

    /**
     * 计算答案相对检索片段的忠实度（0~1）。
     *
     * @param answer    生成的答案
     * @param contexts  检索命中的片段内容（拼接后作为事实依据）
     * @return 0~1 的 faithfulness 分数（无事实性 token 时视为 1——无断言即无不忠）
     */
    public static double score(String answer, List<String> contexts) {
        if (answer == null || answer.isBlank()) {
            return 1.0;
        }
        String evidence = contexts == null ? "" : String.join("\n", contexts);
        String evidenceLower = evidence.toLowerCase();

        Set<String> claims = extractFacts(answer.toLowerCase());
        if (claims.isEmpty()) {
            return 1.0;
        }
        int supported = 0;
        for (String fact : claims) {
            if (evidenceLower.contains(fact)) {
                supported++;
            }
        }
        return (double) supported / claims.size();
    }

    /** 抽取答案中的事实性 token：数字 + 有意义的英文单词 */
    static Set<String> extractFacts(String lowerText) {
        Set<String> facts = new HashSet<>();
        Matcher num = NUMBER.matcher(lowerText);
        while (num.find()) {
            facts.add(num.group());
        }
        Matcher word = WORD.matcher(lowerText);
        while (word.find()) {
            String w = word.group();
            if (!STOPWORDS.contains(w)) {
                facts.add(w);
            }
        }
        return facts;
    }
}

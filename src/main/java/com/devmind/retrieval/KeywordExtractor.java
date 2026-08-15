package com.devmind.retrieval;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class KeywordExtractor {

    private static final Pattern ENGLISH = Pattern.compile("[a-zA-Z0-9]{2,}");

    /**
     * 中文高频疑问/虚词 2-gram 停用词：这类词几乎出现在所有中文句子里，
     * 若不过滤会把整个知识库的中文 chunk 都拉进关键词候选（关键词检索失去区分度）。
     */
    private static final Set<String> STOP_WORDS = Set.of(
            "什么", "怎么", "为什", "如何", "哪些", "怎样", "是否", "可以", "请问",
            "为啥", "为何", "多少", "哪个", "几种", "有哪", "哪几", "是不", "可不",
            "一个", "一种", "不是", "没有", "应该", "需要", "这个", "那个", "这样",
            "就是", "只是", "还是", "或者", "比如", "例如", "其中", "以及", "但是",
            "如果", "因为", "所以", "那么", "自己", "用来", "用于", "的是", "的话",
            "对吧", "能否", "能不能"
    );

    private KeywordExtractor() {
    }

    public static List<String> extract(String text, int limit) {
        Set<String> terms = new LinkedHashSet<>();
        Matcher matcher = ENGLISH.matcher(text);
        while (matcher.find()) {
            terms.add(matcher.group().toLowerCase(Locale.ROOT));
        }

        StringBuilder chinese = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c >= 0x4e00 && c <= 0x9fff) {
                chinese.append(c);
            }
        }
        if (chinese.length() >= 2) {
            for (int i = 0; i + 2 <= chinese.length(); i++) {
                String gram = chinese.substring(i, i + 2);
                if (!STOP_WORDS.contains(gram)) {
                    terms.add(gram);
                }
            }
        }
        return terms.stream().limit(limit).toList();
    }
}

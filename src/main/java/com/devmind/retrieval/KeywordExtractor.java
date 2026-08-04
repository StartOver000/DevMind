package com.devmind.retrieval;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class KeywordExtractor {

    private static final Pattern ENGLISH = Pattern.compile("[a-zA-Z0-9]{2,}");

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
                terms.add(chinese.substring(i, i + 2));
            }
        }
        return terms.stream().limit(limit).toList();
    }
}

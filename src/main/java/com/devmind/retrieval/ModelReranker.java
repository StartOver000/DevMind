package com.devmind.retrieval;

import com.devmind.ai.ChatRouter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ModelReranker {

    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private final ChatRouter chatRouter;

    public ModelReranker(ChatRouter chatRouter) {
        this.chatRouter = chatRouter;
    }

    public List<RetrievalResult> rerank(String question, List<RetrievalResult> results, int topK) {
        if (results.isEmpty()) {
            return results;
        }
        String system = """
                你是一个检索结果排序器。只根据问题相关度对给定片段排序。
                只输出片段编号，按相关度从高到低，用逗号分隔，不要输出其他内容。
                """;
        StringBuilder user = new StringBuilder("问题：").append(question).append("\n\n片段：\n");
        for (int i = 0; i < results.size(); i++) {
            RetrievalResult result = results.get(i);
            String content = result.content().length() > 600
                    ? result.content().substring(0, 600)
                    : result.content();
            user.append(i + 1).append(". ").append(result.documentName()).append(": ").append(content).append('\n');
        }
        String output = chatRouter.chat(system, user.toString()).content();
        return reorder(results, output, topK);
    }

    private List<RetrievalResult> reorder(List<RetrievalResult> results, String output, int topK) {
        Matcher matcher = NUMBER.matcher(output == null ? "" : output);
        List<Integer> order = new ArrayList<>();
        while (matcher.find() && order.size() < results.size()) {
            int index = Integer.parseInt(matcher.group());
            if (index >= 1 && index <= results.size() && !order.contains(index)) {
                order.add(index);
            }
        }
        if (order.isEmpty()) {
            throw new IllegalArgumentException("模型未返回有效排序");
        }
        List<RetrievalResult> reordered = new ArrayList<>();
        for (int index : order) {
            reordered.add(results.get(index - 1));
        }
        for (int i = 0; i < results.size() && reordered.size() < results.size(); i++) {
            if (!order.contains(i + 1)) {
                reordered.add(results.get(i));
            }
        }
        return reordered.stream().limit(topK).toList();
    }
}

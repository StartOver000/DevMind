package com.devmind.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 公共 SSE 流式解析逻辑回归：delta.content 提取、[DONE] 结束、心跳/空 content/reasoning 跳过。
 * 主/备网关共用 {@link SseChatStreamer}，此解析错误会导致真流式失效（token 不推送或流不结束）。
 */
class SseChatStreamerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void extractsDeltaContent() {
        List<String> tokens = new ArrayList<>();
        boolean cont = SseChatStreamer.acceptLine(
                "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}", objectMapper, tokens::add);

        assertThat(cont).isTrue();
        assertThat(tokens).containsExactly("你好");
    }

    @Test
    void stopsOnDone() {
        boolean cont = SseChatStreamer.acceptLine("data: [DONE]", objectMapper, s -> {
        });
        assertThat(cont).isFalse();
    }

    @Test
    void ignoresHeartbeatEmptyContentAndReasoning() {
        List<String> tokens = new ArrayList<>();
        assertThat(SseChatStreamer.acceptLine("", objectMapper, tokens::add)).isTrue();
        // reasoning（思考过程）不属于 content，不推送
        assertThat(SseChatStreamer.acceptLine(
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"思考中\"}}]}", objectMapper, tokens::add)).isTrue();
        // 空 delta
        assertThat(SseChatStreamer.acceptLine(
                "data: {\"choices\":[{\"delta\":{}}]}", objectMapper, tokens::add)).isTrue();
        // 非 data 前缀行（SSE 注释/空行）
        assertThat(SseChatStreamer.acceptLine(": keep-alive", objectMapper, tokens::add)).isTrue();
        assertThat(tokens).isEmpty();
    }

    @Test
    void accumulatesMultiTokenStream() {
        List<String> tokens = new ArrayList<>();
        SseChatStreamer.acceptLine("data: {\"choices\":[{\"delta\":{\"content\":\"RAG\"}}]}", objectMapper, tokens::add);
        SseChatStreamer.acceptLine("data: {\"choices\":[{\"delta\":{\"content\":\" 是\"}}]}", objectMapper, tokens::add);
        SseChatStreamer.acceptLine("data: [DONE]", objectMapper, tokens::add);

        assertThat(tokens).containsExactly("RAG", " 是");
    }
}

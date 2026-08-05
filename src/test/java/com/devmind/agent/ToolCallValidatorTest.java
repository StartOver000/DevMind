package com.devmind.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolCallValidatorTest {

    private ToolCallValidator validator;

    @BeforeEach
    void setUp() {
        AgentTool tool = mock(AgentTool.class);
        when(tool.name()).thenReturn("kb_search");
        ToolRegistry registry = new ToolRegistry(List.of(tool));
        validator = new ToolCallValidator(registry);
    }

    @Test
    void acceptsValidToolAndJson() {
        ToolCallValidator.Validation v = validator.validate("kb_search", "{\"question\":\"什么是 RAG\"}");
        assertThat(v.valid()).isTrue();
        assertThat(v.argumentsJson()).isEqualTo("{\"question\":\"什么是 RAG\"}");
    }

    @Test
    void rejectsUnknownTool() {
        ToolCallValidator.Validation v = validator.validate("not_exist_tool", "{}");
        assertThat(v.valid()).isFalse();
        assertThat(v.error()).contains("未知工具");
    }

    @Test
    void rejectsMalformedJson() {
        ToolCallValidator.Validation v = validator.validate("kb_search", "not-json{{{");
        assertThat(v.valid()).isFalse();
        assertThat(v.error()).contains("不是合法 JSON");
    }

    @Test
    void repairsJsonWithPrefixAndSuffix() {
        // 模型常在 JSON 前后输出自然语言
        ToolCallValidator.Validation v = validator.validate(
                "kb_search",
                "好的，我来检索：{\"question\":\"什么是 RAG\"}（以上是检索结果）"
        );
        assertThat(v.valid()).isTrue();
        assertThat(v.argumentsJson()).isEqualTo("{\"question\":\"什么是 RAG\"}");
    }

    @Test
    void treatsNullAndEmptyArgsAsEmptyObject() {
        assertThat(validator.validate("kb_search", null).valid()).isTrue();
        assertThat(validator.validate("kb_search", "").valid()).isTrue();
        assertThat(validator.validate("kb_search", "   ").valid()).isTrue();
    }

    @Test
    void rejectsBlankToolName() {
        ToolCallValidator.Validation v = validator.validate("", "{}");
        assertThat(v.valid()).isFalse();
    }
}

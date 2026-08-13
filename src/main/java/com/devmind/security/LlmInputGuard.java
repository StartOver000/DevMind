package com.devmind.security;

import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * LLM 输入统一防护层（八股反推 P1-1）。
 *
 * 统一在"用户自然语言进入 LLM 之前"做 Prompt 注入检测，避免每个入口各自
 * 手贴 {@code detector.inspect()} 调用。内部组合 {@link PromptInjectionDetector}，
 * 命中注入模式即抛 {@link ApiException}（INVALID_ARGUMENT），阻断内容进入模型。
 *
 * 覆盖入口：Agent 对话、工作流生成、能力盘点、webhook（迁移复用）等。
 */
@Component
public class LlmInputGuard {

    private static final Logger log = LoggerFactory.getLogger(LlmInputGuard.class);

    private final PromptInjectionDetector detector;

    public LlmInputGuard(PromptInjectionDetector detector) {
        this.detector = detector;
    }

    /**
     * 校验输入，命中注入模式即抛异常。
     *
     * @param input 用户输入（String / Map / List 任意嵌套），null 或空视为通过
     * @throws ApiException 命中注入模式时抛出，message 含命中的片段
     */
    public void check(Object input) {
        if (input == null) {
            return;
        }
        PromptInjectionDetector.Detection detection = detector.inspect(input);
        if (detection.hit()) {
            log.warn("Prompt 注入检测命中，已拒绝进入 LLM: {}", detection.matches());
            throw new ApiException(ErrorCode.INVALID_ARGUMENT,
                    "检测到疑似 Prompt 注入内容，已拒绝。命中片段: " + String.join(" / ", detection.matches()));
        }
    }

    /**
     * 校验并返回规范化文本（便捷方法：String 输入）。
     * 命中注入模式即抛异常。
     */
    public String checkText(String text) {
        if (text == null) {
            return null;
        }
        check(text);
        return text;
    }
}

package com.devmind.skill.dto;

import java.util.List;

/** 从对话沉淀技能请求（P2）：携带一次对话的问题、工具调用轨迹与最终回答 */
public record ChatDraftRequest(
        String question,
        List<ToolCallItem> toolTrace,
        String answer
) {
    /** 工具调用轨迹项 */
    public record ToolCallItem(String tool, String args, Boolean ok, Long costMs) {
    }
}

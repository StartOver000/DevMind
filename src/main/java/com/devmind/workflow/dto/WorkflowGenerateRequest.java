package com.devmind.workflow.dto;

import jakarta.validation.constraints.NotBlank;

/** 对话式生成工作流草案请求 */
public record WorkflowGenerateRequest(
        @NotBlank(message = "请描述你的需求") String description
) {
}

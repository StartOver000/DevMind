package com.devmind.workflow.dto;

import jakarta.validation.constraints.NotBlank;

/** 创建工作流请求 */
public record WorkflowCreateRequest(
        @NotBlank(message = "工作流名称不能为空") String name,
        String description,
        @NotBlank(message = "工作流步骤不能为空") String stepsJson,
        String triggerType, // manual | cron | webhook
        String cronExpr,
        String scope,       // private | team
        String status       // ENABLED | DISABLED
) {
}

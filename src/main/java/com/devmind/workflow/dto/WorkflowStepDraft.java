package com.devmind.workflow.dto;

/** 对话式生成的工作流步骤草案（展示/确认用） */
public record WorkflowStepDraft(
        String tool,
        String paramsJson,
        String outputVar,
        String goal
) {
}

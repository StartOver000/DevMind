package com.devmind.agent.dto;

/** 工具调用轨迹（前端可展示 Agent 做了什么） */
public record ToolTraceItem(
        String tool,
        String args,
        boolean ok,
        long costMs
) {
}

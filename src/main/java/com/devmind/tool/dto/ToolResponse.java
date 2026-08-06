package com.devmind.tool.dto;

import com.devmind.tool.ToolDefinition;

/** 接口工具返回 DTO（不含鉴权密文） */
public record ToolResponse(
        Long id,
        String name,
        String description,
        String toolType,
        String endpointUrl,
        String httpMethod,
        String requestSchemaJson,
        String responseDesc,
        String authType,
        String maskFieldsJson,
        String status,
        Long createdBy
) {
    public static ToolResponse from(ToolDefinition d) {
        return new ToolResponse(
                d.id(), d.name(), d.description(), d.toolType(),
                d.endpointUrl(), d.httpMethod(), d.requestSchemaJson(), d.responseDesc(),
                d.authType(), d.maskFieldsJson(), d.status(), d.createdBy()
        );
    }
}

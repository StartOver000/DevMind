package com.devmind.tool.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 登记接口（生成动态工具）的请求 */
public record ToolCreateRequest(
        @NotBlank(message = "工具名不能为空")
        @Pattern(regexp = "^[a-zA-Z_][a-zA-Z0-9_]*$", message = "工具名只能包含字母/数字/下划线，且以字母或下划线开头")
        String name,
        String description,
        @NotBlank(message = "接口地址不能为空")
        String endpointUrl,
        String httpMethod,
        String requestSchemaJson,
        String responseDesc,
        String authType,
        String authConfig,
        String maskFieldsJson
) {
}

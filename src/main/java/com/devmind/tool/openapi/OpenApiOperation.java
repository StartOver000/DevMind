package com.devmind.tool.openapi;

import java.util.List;

/**
 * 从 OpenAPI 3.0 文档解析出的单个接口（operation）规格。
 * 仅提取登记动态接口工具所需的子集：method/path/操作ID/说明/标签/参数/请求体。
 */
public record OpenApiOperation(
        String method,        // GET | POST | PUT | DELETE（其余方法跳过）
        String path,          // 如 /api/users/{id}
        String operationId,   // 如 getUserById（可能为空，由导入器自动生成）
        String summary,       // 一句话概述
        String description,   // 详细描述
        List<String> tags,    // 分组标签
        List<ParameterSpec> parameters, // path/query/header 参数
        String requestBodyJson // 请求体 JSON Schema（可能为空）
) {

    /** 单个参数规格（name/in/required/type/description） */
    public record ParameterSpec(
            String name,
            String in,        // path | query | header
            boolean required,
            String type,      // string | integer | number | boolean | array | object
            String description
    ) {
    }
}

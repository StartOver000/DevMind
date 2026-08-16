package com.devmind.knowledge.dto;

/**
 * 示例知识库创建结果。
 *
 * @param id           知识库 id（已存在时返回已有库）
 * @param name         知识库名称
 * @param documentId   内置示例文档 id（重复创建时可能为 null）
 * @param documentName 示例文档文件名
 * @param duplicate    是否命中已存在的示例知识库（幂等）
 */
public record DemoKnowledgeBaseResponse(
        Long id,
        String name,
        Long documentId,
        String documentName,
        boolean duplicate
) {
}

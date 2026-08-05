package com.devmind.agent;

/**
 * Agent 可调用工具。
 * 由 Spring 自动收集到 {@link ToolRegistry}，供大模型通过 function calling 调用。
 */
public interface AgentTool {

    /** 工具名（模型调用时使用，如 kb_search） */
    String name();

    /** 给模型的自然语言说明（模型据此判断何时调用） */
    String description();

    /** 参数 JSON Schema（模型据此生成参数） */
    String parametersJsonSchema();

    /**
     * 执行工具并返回结果（文本或 JSON）。
     *
     * @param argumentsJson 模型生成的参数（JSON）
     * @param userId        当前用户
     * @return 工具结果，回填给模型
     */
    String execute(String argumentsJson, Long userId);
}

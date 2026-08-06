package com.devmind.agent.tool;

import com.devmind.agent.AgentTool;
import com.devmind.ai.AiModelGateway;
import com.devmind.ai.ChatRouter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * ai_generate：工作流中的"AI 生成/汇总"步骤。
 * 把上一步输出（经 {{var}} 模板注入后的 prompt）喂给大模型生成文本。
 */
@Component
public class AiGenerateTool implements AgentTool {

    private final ChatRouter chatRouter;
    private final ObjectMapper objectMapper;

    public AiGenerateTool(ChatRouter chatRouter, ObjectMapper objectMapper) {
        this.chatRouter = chatRouter;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "ai_generate";
    }

    @Override
    public String description() {
        return "根据提示词调用大模型生成文本（用于工作流中的 AI 汇总/报告生成步骤）。"
                + "参数：prompt(必填，生成提示词，可用 {{上一步输出变量}} 引用前面步骤的结果), "
                + "system(可选，系统提示)";
    }

    @Override
    public String parametersJsonSchema() {
        return """
                {"type":"object","properties":{
                  "prompt":{"type":"string","description":"生成文本的提示词"},
                  "system":{"type":"string","description":"可选系统提示"}
                },"required":["prompt"]}
                """;
    }

    @Override
    public String execute(String argumentsJson, Long userId) {
        Map<String, Object> args = parseArgs(argumentsJson);
        String prompt = args.get("prompt") == null ? "" : String.valueOf(args.get("prompt")).trim();
        if (prompt.isEmpty()) {
            throw new IllegalArgumentException("ai_generate 缺少 prompt 参数");
        }
        String system = args.get("system") == null ? "" : String.valueOf(args.get("system"));
        AiModelGateway.ChatResult result = chatRouter.chat(system, prompt);
        if (result == null || result.content() == null) {
            throw new IllegalStateException("模型返回为空");
        }
        return result.content();
    }

    private Map<String, Object> parseArgs(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            throw new IllegalArgumentException("参数解析失败: " + ex.getMessage());
        }
    }
}

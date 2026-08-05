package com.devmind.agent;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 工具注册表：Spring 自动收集所有 {@link AgentTool} Bean。
 */
@Component
public class ToolRegistry {

    private final Map<String, AgentTool> tools;

    public ToolRegistry(List<AgentTool> toolList) {
        this.tools = toolList.stream()
                .collect(Collectors.toMap(AgentTool::name, Function.identity()));
    }

    public List<AgentTool> all() {
        return tools.values().stream().toList();
    }

    public AgentTool get(String name) {
        return tools.get(name);
    }

    public boolean has(String name) {
        return tools.containsKey(name);
    }

    public String execute(String name, String argumentsJson, Long userId) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("未知工具: " + name);
        }
        return tool.execute(argumentsJson, userId);
    }
}

package com.devmind.agent;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表：内置工具（Spring 收集的 {@link AgentTool} Bean）+ 动态工具
 * （接口登记 / MCP 工具，运行期注册）。
 */
@Component
public class ToolRegistry {

    private final Map<String, AgentTool> tools = new ConcurrentHashMap<>();

    public ToolRegistry(List<AgentTool> toolList) {
        for (AgentTool tool : toolList) {
            tools.put(tool.name(), tool);
        }
    }

    /** 动态注册一个工具（如接口登记后包装的 adapter） */
    public void register(AgentTool tool) {
        if (tool != null && tool.name() != null) {
            tools.put(tool.name(), tool);
        }
    }

    /** 注销一个动态工具（如接口删除/禁用） */
    public void unregister(String name) {
        if (name != null) {
            tools.remove(name);
        }
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

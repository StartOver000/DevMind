package com.devmind.agent;

import com.devmind.audit.ToolCallLogRepository;
import com.devmind.audit.ToolCallLogRepository.ToolCallLog;
import com.devmind.tool.ToolDefinitionRepository;
import com.devmind.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表：内置工具（Spring 收集的 {@link AgentTool} Bean）+ 动态工具
 * （接口登记 / MCP 工具，运行期注册）。
 *
 * 统一审计：所有工具调用（Agent / 工作流）经 {@link #execute} 记录到
 * tool_call_log（M2-3）。审计写入失败只告警，不影响主流程。
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, AgentTool> tools = new ConcurrentHashMap<>();
    private final ToolCallLogRepository auditRepository;          // 可空（测试/旧构造）
    private final ToolDefinitionRepository toolDefinitionRepository;
    private final UserService userService;

    /** 测试/兼容构造：不启用审计 */
    public ToolRegistry(List<AgentTool> toolList) {
        this(toolList, null, null, null);
    }

    @Autowired
    public ToolRegistry(
            List<AgentTool> toolList,
            ToolCallLogRepository auditRepository,
            ToolDefinitionRepository toolDefinitionRepository,
            UserService userService
    ) {
        this.auditRepository = auditRepository;
        this.toolDefinitionRepository = toolDefinitionRepository;
        this.userService = userService;
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

    /** Agent 调用的工具（默认 source=agent） */
    public String execute(String name, String argumentsJson, Long userId) {
        return execute(name, argumentsJson, userId, "agent", null);
    }

    /** 统一工具执行入口（含审计）。source: agent | workflow */
    public String execute(String name, String argumentsJson, Long userId, String source, Long workflowRunId) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("未知工具: " + name);
        }
        long start = System.currentTimeMillis();
        try {
            String result = tool.execute(argumentsJson, userId);
            audit(name, userId, source, workflowRunId, "success", System.currentTimeMillis() - start, null);
            return result;
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            audit(name, userId, source, workflowRunId, "fail", System.currentTimeMillis() - start, message);
            throw ex;
        }
    }

    private void audit(String name, Long userId, String source, Long workflowRunId,
                       String status, long costMs, String error) {
        if (auditRepository == null || userService == null) {
            return;
        }
        try {
            Long tenantId = userService.tenantIdOf(userId);
            boolean isInterface = toolDefinitionRepository != null
                    && toolDefinitionRepository.findByName(name) != null;
            auditRepository.insert(new ToolCallLog(
                    tenantId, userId, name, isInterface ? "interface" : "builtin",
                    source, workflowRunId, status, costMs, error
            ));
        } catch (Exception ex) {
            log.warn("工具调用审计写入失败 (tool={}): {}", name, ex.getMessage());
        }
    }
}

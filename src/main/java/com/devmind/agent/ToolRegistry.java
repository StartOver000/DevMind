package com.devmind.agent;

import com.devmind.audit.ToolCallLogRepository;
import com.devmind.audit.ToolCallLogRepository.ToolCallLog;
import com.devmind.guard.GuardDecision;
import com.devmind.guard.GuardRequest;
import com.devmind.guard.TaskMode;
import com.devmind.guard.ToolAccessDeniedException;
import com.devmind.guard.ToolGuard;
import com.devmind.tool.ToolDefinitionRepository;
import com.devmind.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表：内置工具（Spring 收集的 {@link AgentTool} Bean）+ 动态工具
 * （接口登记 / MCP 工具，运行期注册）。
 *
 * 统一审计：所有工具调用（Agent / 工作流）经 {@link #execute} 记录到
 * tool_call_log（M2-3）。审计写入失败只告警，不影响主流程。
 *
 * 执行期授权（G1）：{@link #execute} 是唯一收口，真正调用工具前先问
 * {@link ToolGuard}；拒绝时工具不执行、审计落 {@code denied} 并带策略号。
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /** 审计落库的默认租户（与 tool_call_log.tenant_id 的 DDL 默认值一致） */
    private static final long DEFAULT_TENANT_ID = 1L;

    private final Map<String, AgentTool> tools = new ConcurrentHashMap<>();
    private final ToolCallLogRepository auditRepository;          // 可空（测试/旧构造）
    private final ToolDefinitionRepository toolDefinitionRepository;
    private final UserService userService;
    private final ToolGuard toolGuard;                            // 可空（测试/旧构造）

    /** 测试/兼容构造：不启用审计与授权 */
    public ToolRegistry(List<AgentTool> toolList) {
        this(toolList, null, null, null, null);
    }

    /** 兼容构造：启用审计、不启用授权 */
    public ToolRegistry(List<AgentTool> toolList,
                        ToolCallLogRepository auditRepository,
                        ToolDefinitionRepository toolDefinitionRepository,
                        UserService userService) {
        this(toolList, auditRepository, toolDefinitionRepository, userService, null);
    }

    @Autowired
    public ToolRegistry(
            List<AgentTool> toolList,
            ToolCallLogRepository auditRepository,
            ToolDefinitionRepository toolDefinitionRepository,
            UserService userService,
            ToolGuard toolGuard
    ) {
        this.auditRepository = auditRepository;
        this.toolDefinitionRepository = toolDefinitionRepository;
        this.userService = userService;
        this.toolGuard = toolGuard;
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

    /** Agent 调用的工具（默认 source=agent，任务模式 WRITE） */
    public String execute(String name, String argumentsJson, Long userId) {
        return execute(name, argumentsJson, userId, "agent", null, TaskMode.WRITE);
    }

    /** 统一工具执行入口（含审计，任务模式 WRITE） */
    public String execute(String name, String argumentsJson, Long userId, String source, Long workflowRunId) {
        return execute(name, argumentsJson, userId, source, workflowRunId, TaskMode.WRITE);
    }

    /**
     * 统一工具执行入口（含执行期授权 + 审计）。
     *
     * @param taskMode 本次任务允许的操作范围；只读任务下写工具会被拒绝
     */
    public String execute(String name, String argumentsJson, Long userId, String source,
                          Long workflowRunId, TaskMode taskMode) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("未知工具: " + name);
        }
        long start = System.currentTimeMillis();
        GuardDecision decision = decide(name, tool, userId, taskMode);
        if (!decision.allowed()) {
            audit(name, tool, userId, source, workflowRunId, "denied",
                    System.currentTimeMillis() - start, decision.reason(), decision, argumentsJson);
            throw new ToolAccessDeniedException(name, decision);
        }
        try {
            String result = tool.execute(argumentsJson, userId);
            audit(name, tool, userId, source, workflowRunId, "success",
                    System.currentTimeMillis() - start, null, decision, argumentsJson);
            return result;
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            audit(name, tool, userId, source, workflowRunId, "fail",
                    System.currentTimeMillis() - start, message, decision, argumentsJson);
            throw ex;
        }
    }

    /** 问授权策略；未配置 guard 时按放行处理（兼容旧装配） */
    private GuardDecision decide(String name, AgentTool tool, Long userId, TaskMode taskMode) {
        if (toolGuard == null) {
            return GuardDecision.allow("GUARD_ABSENT");
        }
        boolean write = tool.writeOperation();
        String role = null;
        if (write && userService != null) {
            try {
                role = userService.isAdmin(userId) ? "ADMIN" : "USER";
            } catch (Exception ex) {
                // 判定本身失败也要 fail-closed 且留痕：按拒绝处理，策略号 GUARD_ERROR
                String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                log.warn("授权判定的角色解析失败，按拒绝处理 (tool={}): {}", name, message);
                return GuardDecision.deny("GUARD_ERROR", "授权判定失败，按拒绝处理: " + message);
            }
        }
        return toolGuard.decide(new GuardRequest(name, write, taskMode, role));
    }

    /**
     * 记录一次「未经过 registry.execute 的拒绝」（如 Agent 内部特判工具在只读任务下被拦截）。
     * 只落审计，不执行工具；审计关闭时静默返回。
     */
    public void recordDenied(String name, Long userId, String source, String policyId, String reason,
                             String argumentsJson) {
        audit(name, null, userId, source, null, "denied", 0L, reason,
                GuardDecision.deny(policyId, reason), argumentsJson);
    }

    private String toolType(String name) {
        return toolDefinitionRepository != null && toolDefinitionRepository.findByName(name) != null
                ? "interface" : "builtin";
    }

    private void audit(String name, AgentTool tool, Long userId, String source, Long workflowRunId,
                       String status, long costMs, String error, GuardDecision decision, String argumentsJson) {
        if (auditRepository == null || userService == null) {
            return;
        }
        Long tenantId;
        try {
            tenantId = userService.tenantIdOf(userId);
        } catch (Exception ex) {
            // 用户不存在/租户解析失败时仍要留痕：回落到默认租户，避免「被拒绝却没有任何审计行」
            tenantId = DEFAULT_TENANT_ID;
            log.warn("工具调用审计的租户解析失败，回落到默认租户 (user={}): {}", userId, ex.getMessage());
        }
        try {
            auditRepository.insert(new ToolCallLog(
                    tenantId, userId, name, toolType(name),
                    source, workflowRunId, status, costMs, error,
                    hashArgs(argumentsJson),
                    decision.decision().name(),
                    decision.policyId()
            ));
        } catch (Exception ex) {
            log.warn("工具调用审计写入失败 (tool={}): {}", name, ex.getMessage());
        }
    }

    /** 参数摘要：审计只留哈希不留原文 */
    static String hashArgs(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(argumentsJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            return null;
        }
    }
}

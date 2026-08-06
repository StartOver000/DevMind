package com.devmind.tool;

import com.devmind.agent.AgentTool;
import com.devmind.agent.ToolRegistry;
import com.devmind.user.UserService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 工具访问控制：决定某用户可见/可用的工具集合。
 * 规则：
 * - 内置工具（知识检索/ai_generate 等）：所有同租户用户默认可用；
 * - 动态接口工具：管理员可用全部；普通成员仅可用被授权的（个人或所属团队）；
 * - 租户隔离：只考虑本租户的工具。
 */
@Service
public class ToolAccessService {

    private final ToolDefinitionRepository toolRepository;
    private final ToolGrantRepository grantRepository;
    private final ToolRegistry toolRegistry;
    private final UserService userService;

    public ToolAccessService(
            ToolDefinitionRepository toolRepository,
            ToolGrantRepository grantRepository,
            ToolRegistry toolRegistry,
            UserService userService
    ) {
        this.toolRepository = toolRepository;
        this.grantRepository = grantRepository;
        this.toolRegistry = toolRegistry;
        this.userService = userService;
    }

    /** 用户可用工具名集合（供 Agent 工具列表 / 工作流生成 / 工作流校验） */
    public Set<String> accessibleToolNames(Long tenantId, Long userId) {
        List<ToolDefinition> dynamicTools = toolRepository.listEnabled(tenantId);
        Set<String> dynamicNames = new LinkedHashSet<>();
        for (ToolDefinition def : dynamicTools) {
            dynamicNames.add(def.name());
        }
        // 内置工具 = 注册表中不在动态集合里的
        Set<String> builtinNames = new LinkedHashSet<>();
        for (AgentTool tool : toolRegistry.all()) {
            if (!dynamicNames.contains(tool.name())) {
                builtinNames.add(tool.name());
            }
        }
        if (userService.isAdmin(userId)) {
            builtinNames.addAll(dynamicNames);
            return builtinNames;
        }
        // 普通成员：内置全部 + 被授权的动态工具
        Set<Long> grantedIds = grantRepository.findToolIdsForUser(tenantId, userId);
        for (ToolDefinition def : dynamicTools) {
            if (grantedIds.contains(def.id())) {
                builtinNames.add(def.name());
            }
        }
        return builtinNames;
    }

    /** 用户可见的动态接口工具列表（接口管理页用） */
    public List<ToolDefinition> accessibleDynamicTools(Long tenantId, Long userId) {
        List<ToolDefinition> all = toolRepository.listEnabled(tenantId);
        if (userService.isAdmin(userId)) {
            return all;
        }
        Set<Long> grantedIds = grantRepository.findToolIdsForUser(tenantId, userId);
        return all.stream().filter(d -> grantedIds.contains(d.id())).toList();
    }

    /** 是否可调用某工具（按名称） */
    public boolean canUse(Long tenantId, Long userId, String toolName) {
        return accessibleToolNames(tenantId, userId).contains(toolName);
    }
}

package com.devmind.tool;

import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.user.UserService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/**
 * 工具授权管理（仅管理员）：
 * - 查看某用户已授权的工具
 * - 给成员/团队授权/撤销某个接口工具
 * 普通成员的可见性由 {@link ToolAccessService} 统一计算。
 */
@RestController
@RequestMapping("/api/admin/tools")
public class ToolGrantController {

    private final ToolGrantRepository grantRepository;
    private final ToolDefinitionRepository toolRepository;
    private final UserService userService;

    public ToolGrantController(
            ToolGrantRepository grantRepository,
            ToolDefinitionRepository toolRepository,
            UserService userService
    ) {
        this.grantRepository = grantRepository;
        this.toolRepository = toolRepository;
        this.userService = userService;
    }

    /** 目标用户已授权的工具 id 集合 */
    @GetMapping("/grants/{targetUserId}")
    public Map<String, Object> grantsOf(
            @PathVariable Long targetUserId,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long operatorId
    ) {
        requireAdmin(operatorId);
        Long tenantId = userService.tenantIdOf(operatorId);
        Set<Long> toolIds = grantRepository.findToolIdsForUser(tenantId, targetUserId);
        return Map.of("toolIds", toolIds.stream().sorted().toList());
    }

    /** 授权：subjectType = user | team */
    @PostMapping("/{toolId}/grants")
    public Map<String, Object> grant(
            @PathVariable Long toolId,
            @RequestBody GrantRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long operatorId
    ) {
        requireAdmin(operatorId);
        Long tenantId = userService.tenantIdOf(operatorId);
        requireToolOfTenant(tenantId, toolId);
        if (request.subjectType() == null || request.subjectId() == null) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "subjectType 和 subjectId 必填");
        }
        if (!"user".equals(request.subjectType()) && !"team".equals(request.subjectType())) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "subjectType 只能是 user 或 team");
        }
        grantRepository.grant(tenantId, request.subjectType(), request.subjectId(), toolId, operatorId);
        return Map.of("granted", true);
    }

    /** 撤销授权 */
    @DeleteMapping("/{toolId}/grants")
    public Map<String, Object> revoke(
            @PathVariable Long toolId,
            @RequestParam String subjectType,
            @RequestParam Long subjectId,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long operatorId
    ) {
        requireAdmin(operatorId);
        Long tenantId = userService.tenantIdOf(operatorId);
        requireToolOfTenant(tenantId, toolId);
        grantRepository.revoke(tenantId, subjectType, subjectId, toolId);
        return Map.of("revoked", true);
    }

    public record GrantRequest(String subjectType, Long subjectId) {
    }

    private void requireToolOfTenant(Long tenantId, Long toolId) {
        if (toolRepository.findById(tenantId, toolId) == null) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "工具不存在: " + toolId);
        }
    }

    private void requireAdmin(Long userId) {
        if (!userService.isAdmin(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "仅管理员可执行此操作");
        }
    }
}

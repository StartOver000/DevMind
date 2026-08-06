package com.devmind.mcp;

import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.mcp.dto.McpServerRequest;
import com.devmind.user.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务器管理（仅管理员）：
 * 登记外部 MCP 服务器 → connect 拉取其 tools 注册为平台工具 → Agent/工作流可调用。
 */
@RestController
@RequestMapping("/api/admin/mcp/servers")
public class McpServerController {

    private final McpServerRepository repository;
    private final McpToolService toolService;
    private final UserService userService;
    private final ObjectMapper objectMapper;

    public McpServerController(
            McpServerRepository repository,
            McpToolService toolService,
            UserService userService,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.toolService = toolService;
        this.userService = userService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long operatorId
    ) {
        requireAdmin(operatorId);
        Long tenantId = userService.tenantIdOf(operatorId);
        return repository.listAll(tenantId).stream().map(def -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", def.id());
            item.put("name", def.name());
            item.put("transportType", def.transportType());
            item.put("command", def.command());
            item.put("args", def.argsJson());
            item.put("url", def.url());
            item.put("status", def.status());
            item.put("connected", toolService.isConnected(def.id()));
            item.put("loadedTools", toolService.loadedToolCount(def.id()));
            return item;
        }).toList();
    }

    @PostMapping
    public Map<String, Object> create(
            @RequestBody McpServerRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long operatorId
    ) {
        requireAdmin(operatorId);
        Long tenantId = userService.tenantIdOf(operatorId);
        if (request.name() == null || !request.name().matches("[a-zA-Z0-9_]+")) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "名称只能包含字母/数字/下划线");
        }
        String transport = request.transportType() == null ? "stdio" : request.transportType();
        if (!"stdio".equals(transport) && !"http".equals(transport)) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "transportType 只能是 stdio 或 http");
        }
        if ("stdio".equals(transport) && (request.command() == null || request.command().isBlank())) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "stdio 传输需要 command");
        }
        if ("http".equals(transport) && (request.url() == null || request.url().isBlank())) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "http 传输需要 url");
        }
        String argsJson = null;
        if (request.args() != null && !request.args().isEmpty()) {
            try {
                argsJson = objectMapper.writeValueAsString(request.args());
            } catch (Exception ex) {
                throw new ApiException(ErrorCode.INVALID_ARGUMENT, "args 序列化失败");
            }
        }
        McpServerDefinition def = McpServerDefinition.forInsert(
                tenantId, request.name(), transport, request.command(), argsJson, request.url(), operatorId
        );
        Long id = repository.insert(def);
        return Map.of("id", id, "name", def.name(), "created", true);
    }

    /** 连接 MCP 服务器并加载其工具 */
    @PostMapping("/{id}/connect")
    public Map<String, Object> connect(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long operatorId
    ) {
        requireAdmin(operatorId);
        McpServerDefinition def = requireServer(operatorId, id);
        if (toolService.isConnected(id)) {
            toolService.disconnect(id);
        }
        try {
            int count = toolService.connect(def);
            return Map.of("connected", true, "name", def.name(), "loadedTools", count);
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "连接 MCP 服务器失败: " + ex.getMessage());
        }
    }

    @PostMapping("/{id}/disconnect")
    public Map<String, Object> disconnect(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long operatorId
    ) {
        requireAdmin(operatorId);
        requireServer(operatorId, id);
        toolService.disconnect(id);
        return Map.of("disconnected", true);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long operatorId
    ) {
        requireAdmin(operatorId);
        McpServerDefinition def = requireServer(operatorId, id);
        toolService.disconnect(id);
        repository.softDelete(userService.tenantIdOf(operatorId), id);
        return Map.of("deleted", true, "name", def.name());
    }

    private McpServerDefinition requireServer(Long operatorId, Long id) {
        Long tenantId = userService.tenantIdOf(operatorId);
        McpServerDefinition def = repository.findById(tenantId, id);
        if (def == null) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "MCP 服务器不存在: " + id);
        }
        return def;
    }

    private void requireAdmin(Long userId) {
        if (!userService.isAdmin(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "仅管理员可操作");
        }
    }
}

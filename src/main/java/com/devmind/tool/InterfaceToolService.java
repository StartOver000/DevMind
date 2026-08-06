package com.devmind.tool;

import com.devmind.agent.ToolRegistry;
import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.security.SecretCipher;
import com.devmind.tool.dto.ToolCreateRequest;
import com.devmind.tool.dto.ToolResponse;
import com.devmind.user.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 接口工具管理：登记/更新/删除内部接口，并把其包装的动态工具与
 * {@link ToolRegistry} 保持同步（启动时加载已启用工具）。
 * 依赖 {@code DatabaseInitializer} 先建表，故顺序在其后。
 */
@Service
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class InterfaceToolService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InterfaceToolService.class);
    private static final Set<String> ALLOWED_METHODS = Set.of("GET", "POST", "PUT", "DELETE");

    private final ToolDefinitionRepository repository;
    private final ToolRegistry toolRegistry;
    private final RestClient.Builder restClientBuilder;
    private final SecretCipher secretCipher;
    private final ObjectMapper objectMapper;
    private final UserService userService;
    private final ToolAccessService toolAccessService;

    public InterfaceToolService(
            ToolDefinitionRepository repository,
            ToolRegistry toolRegistry,
            RestClient.Builder restClientBuilder,
            SecretCipher secretCipher,
            ObjectMapper objectMapper,
            UserService userService,
            ToolAccessService toolAccessService
    ) {
        this.repository = repository;
        this.toolRegistry = toolRegistry;
        this.restClientBuilder = restClientBuilder;
        this.secretCipher = secretCipher;
        this.objectMapper = objectMapper;
        this.userService = userService;
        this.toolAccessService = toolAccessService;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<ToolDefinition> enabled = repository.listEnabledAll();
        for (ToolDefinition def : enabled) {
            registerAdapter(def);
        }
        if (!enabled.isEmpty()) {
            log.info("已加载 {} 个已登记的接口工具", enabled.size());
        }
    }

    /** 用户可见的接口工具（管理员=全部；成员=被授权） */
    public List<ToolResponse> list(Long userId) {
        Long tenantId = userService.tenantIdOf(userId);
        return toolAccessService.accessibleDynamicTools(tenantId, userId)
                .stream().map(ToolResponse::from).toList();
    }

    public ToolResponse get(Long id, Long userId) {
        Long tenantId = userService.tenantIdOf(userId);
        requireAccessible(tenantId, userId, id);
        return ToolResponse.from(requireTool(tenantId, id));
    }

    @Transactional
    public ToolResponse create(ToolCreateRequest req, Long userId) {
        requireAdmin(userId);
        Long tenantId = userService.tenantIdOf(userId);
        validate(req);
        if (repository.findByName(req.name()) != null) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "工具名已存在: " + req.name());
        }
        if (toolRegistry.has(req.name())) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "工具名与系统已有工具冲突: " + req.name());
        }
        String authEnc = encryptAuth(req.authType(), req.authConfig());
        ToolDefinition def = ToolDefinition.forInsert(
                tenantId, req.name(), req.description(), "interface",
                req.endpointUrl(), normalizeMethod(req.httpMethod()), req.requestSchemaJson(), req.responseDesc(),
                normalizeAuthType(req.authType()), authEnc, req.maskFieldsJson(), "READY", userId
        );
        Long id = repository.insert(def);
        ToolDefinition saved = requireTool(tenantId, id);
        registerAdapter(saved);
        log.info("登记接口工具 {} (id={}, tenant={}, by user={})", saved.name(), id, tenantId, userId);
        return ToolResponse.from(saved);
    }

    @Transactional
    public ToolResponse update(Long id, ToolCreateRequest req, Long userId) {
        requireAdmin(userId);
        Long tenantId = userService.tenantIdOf(userId);
        ToolDefinition existing = requireTool(tenantId, id);
        validate(req);
        if (!existing.name().equals(req.name())) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "工具名不可修改，请删除后重建");
        }
        String authEnc = (req.authConfig() == null || req.authConfig().isBlank())
                ? existing.authConfigEncrypted()  // 未传鉴权则保留原值
                : encryptAuth(req.authType(), req.authConfig());
        ToolDefinition updated = new ToolDefinition(
                id, tenantId, existing.name(), req.description(), "interface",
                req.endpointUrl(), normalizeMethod(req.httpMethod()), req.requestSchemaJson(), req.responseDesc(),
                normalizeAuthType(req.authType()), authEnc, req.maskFieldsJson(), existing.status(), existing.createdBy(),
                existing.createdTime()
        );
        repository.update(tenantId, updated);
        toolRegistry.unregister(existing.name());
        ToolDefinition saved = requireTool(tenantId, id);
        registerAdapter(saved);
        log.info("更新接口工具 {} (id={}, by user={})", saved.name(), id, userId);
        return ToolResponse.from(saved);
    }

    public void delete(Long id, Long userId) {
        requireAdmin(userId);
        Long tenantId = userService.tenantIdOf(userId);
        ToolDefinition existing = requireTool(tenantId, id);
        repository.softDelete(tenantId, id);
        toolRegistry.unregister(existing.name());
        log.info("删除接口工具 {} (id={}, by user={})", existing.name(), id, userId);
    }

    /** 连通性测试：按定义实际调用一次（空参数），返回是否可连通 */
    public boolean test(Long id, Long userId) {
        Long tenantId = userService.tenantIdOf(userId);
        requireAccessible(tenantId, userId, id);
        ToolDefinition existing = requireTool(tenantId, id);
        InterfaceToolAdapter adapter = new InterfaceToolAdapter(existing, restClientBuilder, secretCipher, objectMapper);
        String result = adapter.execute("{} ", null);
        return !result.startsWith("{\"error\"");
    }

    private ToolDefinition requireTool(Long tenantId, Long id) {
        ToolDefinition def = repository.findById(tenantId, id);
        if (def == null) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "工具不存在: " + id);
        }
        return def;
    }

    private void requireAccessible(Long tenantId, Long userId, Long toolId) {
        boolean accessible = toolAccessService.accessibleDynamicTools(tenantId, userId)
                .stream().anyMatch(d -> d.id().equals(toolId));
        if (!accessible) {
            throw new ApiException(ErrorCode.FORBIDDEN, "无权访问该工具");
        }
    }

    private void requireAdmin(Long userId) {
        if (!userService.isAdmin(userId)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "仅管理员可执行此操作");
        }
    }

    private void validate(ToolCreateRequest req) {
        String url = req.endpointUrl() == null ? "" : req.endpointUrl().trim().toLowerCase(Locale.ROOT);
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "接口地址仅支持 http/https");
        }
        String method = normalizeMethod(req.httpMethod());
        if (!ALLOWED_METHODS.contains(method)) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "不支持的 HTTP 方法: " + method);
        }
    }

    private String normalizeMethod(String method) {
        String m = method == null ? "GET" : method.trim().toUpperCase(Locale.ROOT);
        return m.isBlank() ? "GET" : m;
    }

    private String normalizeAuthType(String authType) {
        String t = authType == null ? "none" : authType.trim().toLowerCase(Locale.ROOT);
        if (t.isBlank()) {
            return "none";
        }
        if (!Set.of("none", "api_key", "basic").contains(t)) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "不支持的鉴权类型: " + t);
        }
        return t;
    }

    private String encryptAuth(String authType, String authConfig) {
        if (authConfig == null || authConfig.isBlank()) {
            return null;
        }
        return secretCipher.encrypt(authConfig);
    }

    private void registerAdapter(ToolDefinition def) {
        toolRegistry.register(new InterfaceToolAdapter(def, restClientBuilder, secretCipher, objectMapper));
    }
}

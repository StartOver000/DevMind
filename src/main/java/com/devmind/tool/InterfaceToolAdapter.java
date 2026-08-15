package com.devmind.tool;

import com.devmind.agent.AgentTool;
import com.devmind.security.SecretCipher;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.InetAddress;
import java.net.URI;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把登记的接口（{@link ToolDefinition}）包装为 {@link AgentTool}：
 * 参数解析 → 鉴权注入 → HTTP 调用（全局 15s 超时）→ 响应脱敏 → 返回给模型。
 *
 * 安全约束（M1 基线）：仅允许 http/https；响应大小上限；敏感字段脱敏。
 * SSRF 精细化白名单（P1，文档 5.4）。
 */
@SuppressWarnings("null")
public class InterfaceToolAdapter implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(InterfaceToolAdapter.class);
    private static final int MAX_RESPONSE_CHARS = 200_000; // ~200KB

    private final ToolDefinition def;
    private final RestClient client;
    private final SecretCipher secretCipher;
    private final ObjectMapper objectMapper;
    /** SSRF 防护开关与白名单（可配置，本地仿真/开发可放行 host.docker.internal） */
    private final boolean ssrfEnabled;
    private final java.util.Set<String> allowedHosts;
    /** OAuth2 client credentials 换取的 token 缓存（按工具名隔离；过期自动重换）。用 name 而非 id——forInsert 场景 id 可能为 null */
    private final Map<String, OauthToken> oauthTokenCache = new ConcurrentHashMap<>();

    /** 缓存的 OAuth2 token 与过期时间（提前 20% 过期，防边界竞态） */
    private record OauthToken(String token, long expiresAtEpochMs) {
    }

    public InterfaceToolAdapter(
            ToolDefinition def,
            RestClient.Builder restClientBuilder,
            SecretCipher secretCipher,
            ObjectMapper objectMapper,
            boolean ssrfEnabled,
            String ssrfAllowedHosts
    ) {
        this.def = def;
        this.client = restClientBuilder.build();
        this.secretCipher = secretCipher;
        this.objectMapper = objectMapper;
        this.ssrfEnabled = ssrfEnabled;
        java.util.Set<String> hosts = new java.util.HashSet<>();
        if (ssrfAllowedHosts != null) {
            for (String h : ssrfAllowedHosts.split(",")) {
                String t = h.trim().toLowerCase(java.util.Locale.ROOT);
                if (!t.isEmpty()) {
                    hosts.add(t);
                }
            }
        }
        this.allowedHosts = hosts;
    }

    @Override
    public String name() {
        return def.name();
    }

    @Override
    public String description() {
        return def.description() == null || def.description().isBlank()
                ? "调用内部接口 " + def.name()
                : def.description();
    }

    @Override
    public String parametersJsonSchema() {
        if (def.requestSchemaJson() != null && !def.requestSchemaJson().isBlank()) {
            return def.requestSchemaJson();
        }
        return "{\"type\":\"object\",\"properties\":{}}";
    }

    @Override
    public String execute(String argumentsJson, Long userId) {
        try {
            return doExecute(argumentsJson);
        } catch (Exception ex) {
            log.warn("接口工具 {} 调用失败: {}", def.name(), ex.getMessage());
            return "{\"error\": \"接口调用失败: " + sanitize(ex.getMessage()) + "\"}";
        }
    }

    private String doExecute(String argumentsJson) {
        String endpoint = def.endpointUrl();
        if (endpoint == null || endpoint.isBlank()) {
            return "{\"error\": \"接口工具未配置 endpointUrl\"}";
        }

        HttpMethod method = parseMethod();
        Map<String, Object> args = parseArgs(argumentsJson);
        // 路径占位符替换：endpoint 形如 /v1/invoices/{invoice}，用对应参数值填充。
        // 必须先于 URI 解析执行——{param} 在 URI.create 里是非法字符（Illegal character in path），
        // 修复前替换逻辑在 URI.create 之后，含路径参数的工具一调用就抛异常、替换永不生效。
        String resolvedEndpoint = endpoint;
        java.util.Set<String> pathParamKeys = new java.util.HashSet<>();
        for (Map.Entry<String, Object> e : args.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            String ph = "{" + e.getKey() + "}";
            if (resolvedEndpoint.contains(ph)) {
                resolvedEndpoint = resolvedEndpoint.replace(ph, String.valueOf(e.getValue()));
                pathParamKeys.add(e.getKey());
            }
        }
        String lowerEndpoint = resolvedEndpoint.toLowerCase();
        if (!lowerEndpoint.startsWith("http://") && !lowerEndpoint.startsWith("https://")) {
            return "{\"error\": \"接口地址仅支持 http/https\"}";
        }
        // SSRF 防护：拦截内网/私有/保留地址与敏感主机名（在路径替换之后、URI 解析之前校验）
        String ssrfError = validateEndpoint(resolvedEndpoint);
        if (ssrfError != null) {
            return ssrfError;
        }
        // 鉴权（解密后注入）
        Map<String, String> authHeaders = new LinkedHashMap<>();
        Map<String, String> authQuery = new LinkedHashMap<>();
        applyAuth(authHeaders, authQuery);

        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(resolvedEndpoint);
        authQuery.forEach(uriBuilder::queryParam);

        RestClient.RequestBodySpec spec = client.method(method)
                .uri(uriBuilder.build().toUri())
                .headers(h -> authHeaders.forEach(h::add));

        if (method == HttpMethod.GET || method == HttpMethod.DELETE) {
            for (Map.Entry<String, Object> e : args.entrySet()) {
                if (e.getValue() != null && !pathParamKeys.contains(e.getKey())) {
                    uriBuilder.queryParam(e.getKey(), String.valueOf(e.getValue()));
                }
            }
            // 重新设置 uri（含参数）
            spec = client.method(method)
                    .uri(uriBuilder.build().toUri())
                    .headers(h -> authHeaders.forEach(h::add));
        } else {
            // 路径参数不进 body
            Map<String, Object> bodyArgs = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : args.entrySet()) {
                if (!pathParamKeys.contains(e.getKey())) {
                    bodyArgs.put(e.getKey(), e.getValue());
                }
            }
            try {
                String json = objectMapper.writeValueAsString(bodyArgs);
                spec.contentType(MediaType.APPLICATION_JSON).body(json);
            } catch (Exception ex) {
                return "{\"error\": \"请求参数序列化失败: " + sanitize(ex.getMessage()) + "\"}";
            }
        }

        String response;
        try {
            response = spec.retrieve().body(String.class);
        } catch (Exception ex) {
            // 4xx/5xx 等：返回错误给模型，由 Agent 说明原因（US-06）
            String message = ex.getMessage();
            if (message == null || message.isBlank()) {
                message = ex.getClass().getSimpleName();
            }
            return "{\"error\": \"HTTP 调用失败: " + sanitize(message) + "\"}";
        }
        if (response == null) {
            response = "";
        }
        if (response.length() > MAX_RESPONSE_CHARS) {
            response = response.substring(0, MAX_RESPONSE_CHARS) + "\n...（响应过长已截断）";
        }
        return mask(response);
    }

    private HttpMethod parseMethod() {
        try {
            return HttpMethod.valueOf(def.httpMethod() == null ? "GET" : def.httpMethod().toUpperCase());
        } catch (Exception ex) {
            return HttpMethod.GET;
        }
    }

    private Map<String, Object> parseArgs(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            log.warn("接口工具 {} 参数解析失败: {}", def.name(), ex.getMessage());
            return Map.of();
        }
    }

    /**
     * SSRF 防护：解析 host 并拦截
     * 1) 敏感主机名字面（localhost / *.localhost / host.docker.internal / kubernetes.docker.internal）；
     * 2) 解析后 IP 落在回环/站点本地/链路本地/任意地址（覆盖 127.x、10.x、172.16-31.x、
     *    192.168.x、169.254.x 含 169.254.169.254 metadata、::1、fc00::/7、fe80::/10）。
     * 配置白名单的主机（allowedHosts，如本地仿真 host.docker.internal）放行。
     */
    private String validateEndpoint(String endpoint) {
        if (!ssrfEnabled) {
            return null;
        }
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return "{\"error\": \"接口地址缺少主机名\"}";
            }
            String lower = host.toLowerCase(java.util.Locale.ROOT);
            boolean allowed = false;
            for (String h : allowedHosts) {
                if (h.equals(lower) || lower.endsWith("." + h)) {
                    allowed = true;
                    break;
                }
            }
            if (allowed) {
                return null;
            }
            if ("localhost".equals(lower) || lower.endsWith(".localhost")
                    || "host.docker.internal".equals(lower)
                    || "kubernetes.docker.internal".equals(lower)) {
                return "{\"error\": \"接口地址指向内网/本机主机名，已按 SSRF 防护拦截: " + host + "\"}";
            }
            InetAddress[] addrs = InetAddress.getAllByName(host);
            for (InetAddress addr : addrs) {
                if (addr.isAnyLocalAddress() || addr.isLoopbackAddress()
                        || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()) {
                    return "{\"error\": \"接口地址指向内网/保留地址，已按 SSRF 防护拦截: " + host
                            + " (" + addr.getHostAddress() + ")\"}";
                }
            }
        } catch (Exception ex) {
            return "{\"error\": \"接口地址解析失败: " + sanitize(ex.getMessage()) + "\"}";
        }
        return null;
    }

    /** 鉴权配置解析（解密后注入 header/query）；支持 none/api_key/basic/oauth2 */
    private void applyAuth(Map<String, String> headers, Map<String, String> query) {
        String authType = def.authType() == null ? "none" : def.authType();
        if ("none".equals(authType) || def.authConfigEncrypted() == null || def.authConfigEncrypted().isBlank()) {
            return;
        }
        try {
            String plain = secretCipher.resolve(def.authConfigEncrypted());
            JsonNode cfg = objectMapper.readTree(plain);
            switch (authType) {
                case "api_key" -> {
                    String location = cfg.path("location").asText("header");
                    String key = cfg.path("key").asText("Authorization");
                    String value = cfg.path("value").asText("");
                    if (location.equals("query")) {
                        query.put(key, value);
                    } else {
                        headers.put(key, value);
                    }
                }
                case "basic" -> {
                    String username = cfg.path("username").asText("");
                    String password = cfg.path("password").asText("");
                    String encoded = Base64.getEncoder()
                            .encodeToString((username + ":" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    headers.put("Authorization", "Basic " + encoded);
                }
                case "oauth2" -> {
                    String token = obtainOauthToken(cfg);
                    if (token != null && !token.isBlank()) {
                        headers.put("Authorization", "Bearer " + token);
                    }
                }
                default -> log.warn("接口工具 {} 不支持的鉴权类型: {}", def.name(), authType);
            }
        } catch (Exception ex) {
            log.warn("接口工具 {} 鉴权配置解析失败: {}", def.name(), ex.getMessage());
        }
    }

    /**
     * OAuth2 client credentials：缓存有效 token → 缺失/过期时 POST token_url 换新 token → 缓存并返回。
     * auth_config JSON：token_url/client_id/client_secret/scope/token_field(默认 access_token)/expires_field(默认 expires_in)。
     * token_url 同样过 SSRF 校验（防把内网端点当认证中心被滥用）。
     */
    private String obtainOauthToken(JsonNode cfg) {
        String cacheKey = def.name() == null ? "oauth" : def.name();
        OauthToken cached = oauthTokenCache.get(cacheKey);
        if (cached != null && System.currentTimeMillis() < cached.expiresAtEpochMs()) {
            return cached.token();
        }
        String tokenUrl = cfg.path("token_url").asText("");
        if (tokenUrl.isBlank()) {
            log.warn("接口工具 {} oauth2 缺少 token_url", def.name());
            return null;
        }
        if (ssrfEnabled && validateEndpoint(tokenUrl) != null) {
            log.warn("接口工具 {} oauth2 token_url 未通过 SSRF 校验: {}", def.name(), tokenUrl);
            return null;
        }
        try {
            org.springframework.util.LinkedMultiValueMap<String, String> form =
                    new org.springframework.util.LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", cfg.path("client_id").asText(""));
            form.add("client_secret", cfg.path("client_secret").asText(""));
            String scope = cfg.path("scope").asText("");
            if (!scope.isBlank()) {
                form.add("scope", scope);
            }
            String raw = client.post()
                    .uri(tokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            JsonNode resp = objectMapper.readTree(raw == null ? "{}" : raw);
            String tokenField = cfg.path("token_field").asText("access_token");
            String expiresField = cfg.path("expires_field").asText("expires_in");
            String token = resp.path(tokenField).asText("");
            if (token.isBlank()) {
                log.warn("接口工具 {} oauth2 换 token 失败: {}", def.name(), raw);
                return null;
            }
            long expiresIn = expiresField.isBlank() ? 3600L : resp.path(expiresField).asLong(3600L);
            // 提前 20% 过期（默认 1 小时），避免边界竞态导致偶发 401
            long expiresAt = System.currentTimeMillis() + Math.max(1, (long) (expiresIn * 800));
            oauthTokenCache.put(cacheKey, new OauthToken(token, expiresAt));
            return token;
        } catch (Exception ex) {
            log.warn("接口工具 {} oauth2 换 token 异常: {}", def.name(), ex.getMessage());
            return null;
        }
    }

    /** 对响应中声明的敏感字段打码（JSON 场景）；非 JSON 响应原样返回 */
    private String mask(String response) {
        if (def.maskFieldsJson() == null || def.maskFieldsJson().isBlank()) {
            return response;
        }
        try {
            List<String> fields = objectMapper.readValue(def.maskFieldsJson(), new TypeReference<List<String>>() {
            });
            if (fields.isEmpty()) {
                return response;
            }
            JsonNode node = objectMapper.readTree(response);
            if (node == null || !node.isContainerNode()) {
                return response;
            }
            maskNode(node, fields);
            return objectMapper.writeValueAsString(node);
        } catch (Exception ex) {
            // 非 JSON 或解析失败：原样返回，不因脱敏破坏结果
            return response;
        }
    }

    private void maskNode(JsonNode node, List<String> fields) {
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                if (fields.contains(e.getKey()) && e.getValue().isValueNode()) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) node).put(e.getKey(), "***");
                } else {
                    maskNode(e.getValue(), fields);
                }
            });
        } else if (node.isArray()) {
            node.forEach(item -> maskNode(item, fields));
        }
    }

    private String sanitize(String message) {
        if (message == null) {
            return "";
        }
        // 去掉可能含密钥/URL 细节的过长堆栈，只保留第一行
        String firstLine = message.split("\\n")[0];
        return firstLine.length() > 200 ? firstLine.substring(0, 200) : firstLine;
    }
}

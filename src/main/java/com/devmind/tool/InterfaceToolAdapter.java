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

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public InterfaceToolAdapter(
            ToolDefinition def,
            RestClient.Builder restClientBuilder,
            SecretCipher secretCipher,
            ObjectMapper objectMapper
    ) {
        this.def = def;
        this.client = restClientBuilder.build();
        this.secretCipher = secretCipher;
        this.objectMapper = objectMapper;
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

    /** 解析鉴权配置（解密后）并注入 header/query；仅支持 none/api_key/basic */
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
                default -> log.warn("接口工具 {} 不支持的鉴权类型: {}", def.name(), authType);
            }
        } catch (Exception ex) {
            log.warn("接口工具 {} 鉴权配置解析失败: {}", def.name(), ex.getMessage());
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

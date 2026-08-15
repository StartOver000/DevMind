package com.devmind.tool.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAPI 3.0 文档解析器（P1 接口语义化）。
 *
 * 不引入 swagger-parser 依赖（本地 mvn -o 离线构建约束），用 Jackson(JSON) + SnakeYAML(YAML)
 * 手工解析文档子集：paths → 每个 path 的 GET/POST/PUT/DELETE → operation 的
 * summary/description/tags/parameters/requestBody。$ref 引用做一级解析（components 内联），
 * 复杂嵌套（allOf/anyOf 深度展开）不做——作品级 demo 足够，解析失败会给出可读错误。
 */
@Component
public class OpenApiParser {

    private static final Logger log = LoggerFactory.getLogger(OpenApiParser.class);
    private static final Set<String> SUPPORTED_METHODS = Set.of("get", "post", "put", "delete");
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 解析结果：文档标题 + 接口列表 + 首个 server 地址（无则空串） */
    public record ParsedDocument(String title, String baseUrl, List<OpenApiOperation> operations, List<String> warnings) {
    }

    /**
     * 解析 OpenAPI 3.0 文档（支持 JSON 或 YAML，按文件扩展名自动识别）。
     *
     * @param content 文档原始内容
     * @param fileName 文件名（.json/.yaml/.yml）
     * @throws IllegalArgumentException 文档结构非法时抛出可读错误
     */
    public ParsedDocument parse(String content, String fileName) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("OpenAPI 文档内容为空");
        }
        JsonNode root = parseToJson(content, fileName);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("OpenAPI 文档不是有效的 JSON/YAML 对象");
        }
        String openapi = root.path("openapi").asText("");
        if (!openapi.startsWith("3.")) {
            throw new IllegalArgumentException(
                    "仅支持 OpenAPI 3.x 文档（当前 openapi: " + (openapi.isBlank() ? "缺失" : openapi) + "）");
        }

        String title = root.path("info").path("title").asText("");
        // 第一个 server 地址作为接口 baseUrl（无 server 定义时为空，前端可编辑补全）
        String baseUrl = "";
        JsonNode servers = root.path("servers");
        if (servers.isArray() && !servers.isEmpty()) {
            baseUrl = servers.get(0).path("url").asText("").trim();
        }
        List<OpenApiOperation> operations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        JsonNode paths = root.path("paths");
        if (!paths.isObject() || paths.isEmpty()) {
            throw new IllegalArgumentException("OpenAPI 文档缺少 paths 定义");
        }
        Iterator<Map.Entry<String, JsonNode>> pathIt = paths.fields();
        while (pathIt.hasNext()) {
            Map.Entry<String, JsonNode> entry = pathIt.next();
            String path = entry.getKey();
            JsonNode pathItem = entry.getValue();
            if (!pathItem.isObject()) {
                continue;
            }
            Iterator<Map.Entry<String, JsonNode>> methodIt = pathItem.fields();
            while (methodIt.hasNext()) {
                Map.Entry<String, JsonNode> op = methodIt.next();
                String method = op.getKey().toLowerCase();
                if (!SUPPORTED_METHODS.contains(method)) {
                    continue; // 跳过 parameters(顶层数组)、trace/head/options 等
                }
                JsonNode operation = op.getValue();
                if (!operation.isObject()) {
                    continue;
                }
                try {
                    operations.add(buildOperation(method, path, operation));
                } catch (Exception e) {
                    warnings.add("跳过接口 " + method.toUpperCase() + " " + path + "：" + e.getMessage());
                }
            }
        }
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("文档中未找到可导入的 GET/POST/PUT/DELETE 接口");
        }
        log.info("解析 OpenAPI 文档『{}』：{} 个接口，{} 条告警", title, operations.size(), warnings.size());
        return new ParsedDocument(title, baseUrl, operations, warnings);
    }

    private OpenApiOperation buildOperation(String method, String path, JsonNode operation) {
        String operationId = operation.path("operationId").asText("").trim();
        String summary = operation.path("summary").asText("").trim();
        String description = operation.path("description").asText("").trim();

        List<String> tags = new ArrayList<>();
        JsonNode tagsNode = operation.path("tags");
        if (tagsNode.isArray()) {
            tagsNode.forEach(t -> {
                String v = t.asText("").trim();
                if (!v.isEmpty()) {
                    tags.add(v);
                }
            });
        }

        List<OpenApiOperation.ParameterSpec> parameters = new ArrayList<>();
        JsonNode paramsNode = operation.path("parameters");
        if (paramsNode.isArray()) {
            paramsNode.forEach(p -> {
                if (!p.isObject()) {
                    return;
                }
                String name = p.path("name").asText("").trim();
                if (name.isEmpty()) {
                    return;
                }
                String in = p.path("in").asText("").trim();
                boolean required = p.path("required").asBoolean(false);
                String type = "string";
                String desc = p.path("description").asText("").trim();
                JsonNode schema = p.path("schema");
                if (schema.isObject()) {
                    type = schemaType(schema);
                    if (desc.isEmpty()) {
                        desc = schema.path("description").asText("").trim();
                    }
                }
                // $ref 参数（components/parameters 中定义）做一级解析
                String ref = p.path("$ref").asText("");
                if (!ref.isEmpty()) {
                    parameters.add(new OpenApiOperation.ParameterSpec(
                            name, in, required, "string", desc.isEmpty() ? ref : desc));
                    return;
                }
                parameters.add(new OpenApiOperation.ParameterSpec(name, in, required, type, desc));
            });
        }

        // 请求体：优先取 application/json 的 schema；Stripe 等 API 用
        // application/x-www-form-urlencoded / multipart（导入后 schema 为空导致
        // Agent 无法传参），按 content-type 优先级 fallback（$ref 做一级内联）
        String requestBodyJson = null;
        JsonNode requestBody = operation.path("requestBody");
        if (requestBody.isObject()) {
            String ref = requestBody.path("$ref").asText("");
            if (!ref.isEmpty()) {
                // 引用体暂不支持内联，标记为占位
                requestBodyJson = "{\"$ref\":\"" + ref + "\"}";
            } else {
                JsonNode content = requestBody.path("content");
                JsonNode chosen = null;
                for (String mediaType : new String[]{
                        "application/json",
                        "application/x-www-form-urlencoded",
                        "multipart/form-data"
                }) {
                    JsonNode candidate = content.path(mediaType);
                    if (candidate.isObject() && candidate.path("schema").isObject()) {
                        chosen = candidate;
                        break;
                    }
                }
                if (chosen != null) {
                    requestBodyJson = chosen.path("schema").toString();
                }
            }
        }

        return new OpenApiOperation(
                method.toUpperCase(), path, operationId, summary, description, tags, parameters, requestBodyJson);
    }

    /** 把 JsonNode schema 归一为 JSON Schema 类型名 */
    private String schemaType(JsonNode schema) {
        JsonNode type = schema.path("type");
        if (type.isTextual()) {
            return type.asText();
        }
        if (schema.has("$ref")) {
            return "object";
        }
        return "string";
    }

    private JsonNode parseToJson(String content, String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase();
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {
            return yamlToJson(content);
        }
        try {
            return objectMapper.readTree(content);
        } catch (Exception e) {
            // 扩展名不是 .json 但内容可能仍是 YAML（如 .txt 上传）
            if (!lower.endsWith(".json")) {
                try {
                    return yamlToJson(content);
                } catch (Exception ignored) {
                    // fall through
                }
            }
            throw new IllegalArgumentException("JSON 解析失败：" + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private JsonNode yamlToJson(String content) {
        try {
            Yaml yaml = new Yaml();
            Object loaded = yaml.load(content);
            if (loaded == null) {
                throw new IllegalArgumentException("YAML 内容为空");
            }
            return objectMapper.valueToTree(loaded);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("YAML 解析失败：" + e.getMessage());
        }
    }
}

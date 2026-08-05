package com.devmind.agent.tool;

import com.devmind.agent.AgentTool;
import com.devmind.sqldiagnosis.SqlDiagnosisService;
import com.devmind.sqldiagnosis.dto.SqlDiagnosisRequest;
import com.devmind.sqldiagnosis.dto.SqlDiagnosisResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * sql_diagnose：SQL 执行计划诊断工具。
 * 解析 EXPLAIN + 规则初筛 + AI 建议，供模型引用诊断结论。
 */
@Component
public class SqlDiagnoseTool implements AgentTool {

    private final SqlDiagnosisService sqlDiagnosisService;
    private final ObjectMapper objectMapper;

    public SqlDiagnoseTool(SqlDiagnosisService sqlDiagnosisService, ObjectMapper objectMapper) {
        this.sqlDiagnosisService = sqlDiagnosisService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "sql_diagnose";
    }

    @Override
    public String description() {
        return "分析 SQL 执行计划，识别性能风险（全表扫描、深分页、索引缺失等）并给出优化建议。"
                + "当用户问题涉及 SQL 慢查询、执行计划、性能优化时调用。参数：sql(必填，待诊断的 SQL 语句), "
                + "knowledgeBaseId(可选，知识库ID用于检索相关文档)";
    }

    @Override
    public String parametersJsonSchema() {
        return """
                {"type":"object","properties":{
                  "sql":{"type":"string","description":"待诊断的 SQL 语句"},
                  "knowledgeBaseId":{"type":"integer","description":"知识库ID，可选"}
                },"required":["sql"]}
                """;
    }

    @Override
    public String execute(String argumentsJson, Long userId) {
        Map<String, Object> args = parseArgs(argumentsJson);
        String sql = args.get("sql") == null ? "" : String.valueOf(args.get("sql")).trim();
        if (sql.isEmpty()) {
            throw new IllegalArgumentException("sql_diagnose 缺少 sql 参数");
        }
        Long kbId = args.get("knowledgeBaseId") == null ? null : Long.valueOf(String.valueOf(args.get("knowledgeBaseId")));
        SqlDiagnosisResponse response = sqlDiagnosisService.diagnose(
                new SqlDiagnosisRequest(sql, null, kbId),
                userId
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("riskLevel", response.riskLevel());
        result.put("risks", response.risks());
        result.put("advice", response.advice());
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化诊断结果失败", ex);
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
            throw new IllegalArgumentException("工具参数解析失败: " + ex.getMessage());
        }
    }
}

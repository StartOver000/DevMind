package com.devmind.sqldiagnosis;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SqlRuleEngine {

    public List<SqlRisk> analyze(List<SqlExplainRow> plan, String sql) {
        Map<String, SqlRisk> risks = new LinkedHashMap<>();
        String lower = sql.toLowerCase();

        if (lower.matches("(?s).*limit\\s+\\d+\\s*,\\s*\\d+.*")
                || lower.matches("(?s).*limit\\s+\\d+\\s+offset\\s+\\d+.*")) {
            risks.put("DEEP_PAGINATION", new SqlRisk(
                    "DEEP_PAGINATION", "HIGH", "深分页会导致越翻越慢", "SQL 使用 LIMIT OFFSET 或 LIMIT 起始, 数量"
            ));
        }
        if (lower.matches("(?s).*select\\s+\\*.*")) {
            risks.put("SELECT_STAR", new SqlRisk(
                    "SELECT_STAR", "LOW", "SELECT * 会读取多余列", "SQL 使用 SELECT *"
            ));
        }
        if (lower.contains("like '%")) {
            risks.put("LEFT_FUZZY", new SqlRisk(
                    "LEFT_FUZZY", "MEDIUM", "左模糊查询无法走索引", "SQL 使用 LIKE '%...'"
            ));
        }

        for (SqlExplainRow row : plan) {
            String table = row.table() == null ? "" : row.table();
            if ("ALL".equalsIgnoreCase(row.type())) {
                risks.putIfAbsent("FULL_TABLE_SCAN", new SqlRisk(
                        "FULL_TABLE_SCAN", "HIGH", "全表扫描", table + " type=ALL"
                ));
            }
            if (contains(row.extra(), "Using filesort")) {
                risks.putIfAbsent("FILESORT", new SqlRisk(
                        "FILESORT", "MEDIUM", "排序未走索引", table + " " + row.extra()
                ));
            }
            if (contains(row.extra(), "Using temporary")) {
                risks.putIfAbsent("TEMPORARY_TABLE", new SqlRisk(
                        "TEMPORARY_TABLE", "MEDIUM", "使用了临时表", table + " " + row.extra()
                ));
            }
            if (blank(row.key()) && parseRows(row.rows()) > 10000) {
                risks.putIfAbsent("NO_INDEX", new SqlRisk(
                        "NO_INDEX", "MEDIUM", "未使用索引且扫描行数较多", table + " rows=" + row.rows()
                ));
            }
        }
        return new ArrayList<>(risks.values());
    }

    public String maxLevel(List<SqlRisk> risks) {
        for (SqlRisk risk : risks) {
            if ("HIGH".equals(risk.level())) {
                return "HIGH";
            }
        }
        for (SqlRisk risk : risks) {
            if ("MEDIUM".equals(risk.level())) {
                return "MEDIUM";
            }
        }
        return risks.isEmpty() ? "LOW" : "LOW";
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.contains(keyword);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private long parseRows(String rows) {
        try {
            return rows == null ? 0 : Long.parseLong(rows.replace(",", ""));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}

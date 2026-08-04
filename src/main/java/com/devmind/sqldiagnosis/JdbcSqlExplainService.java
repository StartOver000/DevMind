package com.devmind.sqldiagnosis;

import com.devmind.common.ApiException;
import com.devmind.common.ErrorCode;
import com.devmind.config.DevMindProperties;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

@Component
public class JdbcSqlExplainService implements SqlExplainService {

    private final DevMindProperties properties;

    public JdbcSqlExplainService(DevMindProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<SqlExplainRow> explain(String sql, String dataSource) {
        if (properties.sqlDiagnosisJdbcUrl().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "未配置 SQL 诊断 JDBC 地址");
        }
        try (Connection connection = DriverManager.getConnection(
                properties.sqlDiagnosisJdbcUrl(),
                properties.sqlDiagnosisUsername(),
                properties.sqlDiagnosisPassword()
        ); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("EXPLAIN " + sql)) {
            List<SqlExplainRow> rows = new ArrayList<>();
            int index = 1;
            while (rs.next()) {
                rows.add(new SqlExplainRow(
                        String.valueOf(index++),
                        getString(rs, "select_type"),
                        getString(rs, "table"),
                        getString(rs, "type"),
                        getString(rs, "possible_keys"),
                        getString(rs, "key"),
                        getString(rs, "rows"),
                        getString(rs, "Extra")
                ));
            }
            return rows;
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "EXPLAIN 执行失败: " + ex.getMessage());
        }
    }

    private String getString(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (Exception ex) {
            return null;
        }
    }
}

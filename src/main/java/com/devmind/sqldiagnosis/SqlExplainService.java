package com.devmind.sqldiagnosis;

import java.util.List;

public interface SqlExplainService {

    List<SqlExplainRow> explain(String sql, String dataSource);
}

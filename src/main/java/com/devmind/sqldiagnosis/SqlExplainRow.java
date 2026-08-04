package com.devmind.sqldiagnosis;

public record SqlExplainRow(
        String id,
        String selectType,
        String table,
        String type,
        String possibleKeys,
        String key,
        String rows,
        String extra
) {
}

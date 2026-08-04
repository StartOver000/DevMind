package com.devmind.sqldiagnosis;

import com.devmind.sqldiagnosis.dto.SqlDiagnosisListResponse;
import com.devmind.sqldiagnosis.dto.SqlDiagnosisRequest;
import com.devmind.sqldiagnosis.dto.SqlDiagnosisResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sql-diagnosis")
public class SqlDiagnosisController {

    private final SqlDiagnosisService service;

    public SqlDiagnosisController(SqlDiagnosisService service) {
        this.service = service;
    }

    @PostMapping
    public SqlDiagnosisResponse diagnose(
            @Valid @RequestBody SqlDiagnosisRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return service.diagnose(request, userId);
    }

    @GetMapping("/{id}")
    public SqlDiagnosisResponse get(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return service.getRecord(id, userId);
    }

    @GetMapping
    public SqlDiagnosisListResponse list(
            @RequestParam(defaultValue = "20") int limit,
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return service.list(userId, limit);
    }
}

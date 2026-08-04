package com.devmind.audit;

import com.devmind.audit.dto.AuditLogListResponse;
import com.devmind.audit.dto.AuditLogResponse;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AuditLogService {

    private final AuditLogRepository repository;

    public AuditLogService(AuditLogRepository repository) {
        this.repository = repository;
    }

    public void log(Long userId, String action, String targetType, Long targetId, String detail) {
        repository.log(userId, action, targetType, targetId, detail);
    }

    public void log(Long userId, String action, String targetType, Long targetId, String detail, Long teamId) {
        repository.log(userId, action, targetType, targetId, detail, teamId);
    }

    public AuditLogListResponse listByUser(Long userId, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        List<AuditLogResponse> items = repository.listByUser(userId, safeLimit).stream()
                .map(this::toResponse)
                .toList();
        return new AuditLogListResponse(items);
    }

    private AuditLogResponse toResponse(AuditLog log) {
        return new AuditLogResponse(
                log.id(),
                log.userId(),
                log.action(),
                log.targetType(),
                log.targetId(),
                log.detail(),
                log.teamId(),
                log.createdTime()
        );
    }
}

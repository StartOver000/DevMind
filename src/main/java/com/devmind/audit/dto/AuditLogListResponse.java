package com.devmind.audit.dto;

import java.util.List;

public record AuditLogListResponse(List<AuditLogResponse> items) {
}

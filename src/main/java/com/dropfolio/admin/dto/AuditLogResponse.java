package com.dropfolio.admin.dto;

import java.time.Instant;

/** Locked response shape — API_CONTRACT.md §11 GET /admin/audit-logs. */
public record AuditLogResponse(
        Long id,
        Long actorUserId,
        String action,
        String entityType,
        Long entityId,
        String metadata,
        String ipAddress,
        Instant createdAt
) {
}

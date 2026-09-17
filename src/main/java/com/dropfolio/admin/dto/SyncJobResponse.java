package com.dropfolio.admin.dto;

import java.time.Instant;

/** Response body for {@code GET /admin/sync-jobs/{id}} and {@code GET /admin/sync-jobs} — explicit DTO, never the {@code SyncJob} entity directly. */
public record SyncJobResponse(
        Long id,
        String jobType,
        String status,
        String triggeredBy,
        Long triggeredByUserId,
        Integer itemsProcessed,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt
) {
}

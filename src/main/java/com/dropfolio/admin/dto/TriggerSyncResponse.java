package com.dropfolio.admin.dto;

/** Response body for {@code 202 Accepted} on {@code POST /admin/sync-jobs/price-sync}. */
public record TriggerSyncResponse(Long syncJobId, String status) {
}

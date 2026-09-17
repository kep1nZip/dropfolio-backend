package com.dropfolio.admin.dto;

import java.time.Instant;

/** Nested inside {@link DashboardResponse} — API_CONTRACT.md §11 GET /admin/dashboard "lastPriceSync". */
public record LastSyncRef(String status, Instant finishedAt, Integer itemsProcessed) {
}

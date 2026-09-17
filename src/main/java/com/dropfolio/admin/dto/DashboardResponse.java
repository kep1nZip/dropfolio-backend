package com.dropfolio.admin.dto;

/** Locked response shape — API_CONTRACT.md §11 GET /admin/dashboard. */
public record DashboardResponse(
        long totalUsers,
        long activeUsers,
        long totalTrackedItems,
        LastSyncRef lastPriceSync,
        long failedSyncCount24h,
        String priceProviderHealth
) {
}

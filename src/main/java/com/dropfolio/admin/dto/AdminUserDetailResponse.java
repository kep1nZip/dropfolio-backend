package com.dropfolio.admin.dto;

import java.time.Instant;

/**
 * API_CONTRACT.md §11 GET /admin/users/{id} — detail + summary ({@code totalDrops},
 * {@code totalAlerts}, {@code steamIntegration}).
 *
 * {@code steamIntegration} is ALWAYS {@code null} — M9 Implementation Authorization §4 (LOCKED
 * PM DECISION): this field is stale/inconsistent with the product's Steam boundary (no Steam
 * login/OAuth/account-linking/inventory anywhere in this product). Returning {@code null}
 * represents "no integration" without building, faking, or implying any real Steam
 * integration exists. See {@code AdminUserMapper} for where this is set.
 */
public record AdminUserDetailResponse(
        Long id,
        String email,
        String displayName,
        String status,
        Instant createdAt,
        Instant updatedAt,
        long totalDrops,
        long totalAlerts,
        Boolean steamIntegration
) {
}

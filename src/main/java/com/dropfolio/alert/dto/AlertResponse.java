package com.dropfolio.alert.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** Locked response shape — API_CONTRACT.md §9, used for list rows, single-alert detail, and the 201-created body alike. */
public record AlertResponse(
        Long id,
        AlertItemRef item,
        BigDecimal targetPriceUsd,
        Boolean notifyEmail,
        Boolean notifyInApp,
        String status,
        Instant createdAt,
        Instant triggeredAt
) {
}

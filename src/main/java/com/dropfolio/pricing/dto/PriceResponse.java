package com.dropfolio.pricing.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Locked response shape — API_CONTRACT.md §8 / TECHNICAL_SPEC.md §3.4:
 * {@code itemId, priceUsd: BigDecimal?, priceAvailable: boolean, provider: String, fetchedAt: Instant}.
 *
 * Do not add fields (e.g. {@code stale}) — TECHNICAL_SPEC.md §6.3 explicitly rejects adding
 * {@code stale} to this shape without a contract revision.
 */
public record PriceResponse(
        Long itemId,
        BigDecimal priceUsd,
        boolean priceAvailable,
        String provider,
        Instant fetchedAt
) {
}

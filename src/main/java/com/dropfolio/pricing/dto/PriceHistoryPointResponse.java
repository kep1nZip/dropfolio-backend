package com.dropfolio.pricing.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One chart point of {@code GET /prices/{itemId}/history}. Deliberately only what a chart needs
 * ({@code priceUsd}, {@code fetchedAt}) — no {@code id}/{@code createdAt}. {@code priceUsd} is
 * never null and never a placeholder zero: rows with {@code price_available = false} are not
 * emitted as points at all.
 */
public record PriceHistoryPointResponse(
        BigDecimal priceUsd,
        Instant fetchedAt
) {
}

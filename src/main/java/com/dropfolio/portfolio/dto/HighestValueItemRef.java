package com.dropfolio.portfolio.dto;

import java.math.BigDecimal;

/**
 * Nested ref inside {@link PortfolioSummaryResponse} — API_CONTRACT.md §7. Note the key is
 * {@code itemId}, not {@code id} (TECHNICAL_SPEC.md §3.12 — deliberately its own shape, never
 * shared with {@code DropItemRef}/{@code AlertItemRef}).
 *
 * PM Decision (Milestone 6): ties on {@code valueUsd} are broken by lowest {@code itemId}.
 */
public record HighestValueItemRef(
        Long itemId,
        String name,
        BigDecimal valueUsd
) {
}

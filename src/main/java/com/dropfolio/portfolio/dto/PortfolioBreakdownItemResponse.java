package com.dropfolio.portfolio.dto;

import java.math.BigDecimal;

/**
 * Locked response shape — API_CONTRACT.md §7 GET /portfolio/breakdown. One row per DISTINCT
 * item — {@code totalQuantity} is the sum of {@code quantity} across every {@code drops} row
 * the user has for that item (API_CONTRACT.md §7: "digabung per-item"). {@code currentPriceUsd}/
 * {@code totalValueUsd} are {@code null} when {@code priceAvailable=false} (PRD §45).
 */
public record PortfolioBreakdownItemResponse(
        Long itemId,
        String name,
        String type,
        int totalQuantity,
        BigDecimal currentPriceUsd,
        boolean priceAvailable,
        BigDecimal totalValueUsd
) {
}

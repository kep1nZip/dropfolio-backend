package com.dropfolio.portfolio.dto;

import java.math.BigDecimal;

/**
 * Locked response shape — API_CONTRACT.md §7 GET /portfolio/summary.
 *
 * PM Decisions (Milestone 6):
 * <ul>
 *   <li>{@code totalItems} = total physical quantity owned across all active (non-deleted)
 *       holdings — i.e. {@code Σ quantity}, NOT distinct item count and NOT drop-row count.</li>
 *   <li>{@code itemsWithUnavailablePrice} = count of DISTINCT {@code itemId}s whose current
 *       price is unavailable (pricing is an item-level concept, not a per-drop one) — 3 drops
 *       all referencing one unpriceable item counts as {@code 1}, not {@code 3}.</li>
 *   <li>{@code totalValueUsd} = Σ(quantity × current price) over items WITH an available price
 *       only; unpriceable items contribute nothing to this sum (tracked separately via
 *       {@code itemsWithUnavailablePrice} instead of being shown as {@code 0}, per PRD §45).</li>
 * </ul>
 * {@code highestValueItem}/{@code latestDrop} are {@code null} when the user has no holdings at
 * all (empty portfolio), consistently with {@code priceAvailable=false → value=null} elsewhere.
 */
public record PortfolioSummaryResponse(
        BigDecimal totalValueUsd,
        int totalItems,
        HighestValueItemRef highestValueItem,
        LatestDropRef latestDrop,
        WeeklyDropStats weeklyDrop,
        int itemsWithUnavailablePrice
) {
}

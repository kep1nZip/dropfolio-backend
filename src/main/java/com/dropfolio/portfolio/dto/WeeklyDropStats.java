package com.dropfolio.portfolio.dto;

import java.math.BigDecimal;

/**
 * Nested stats inside {@link PortfolioSummaryResponse} — API_CONTRACT.md §7.
 *
 * PM Decision (Milestone 6):
 * <ul>
 *   <li>The week window is a fixed period: every Wednesday 01:00 UTC to the next Wednesday
 *       01:00 UTC — NOT a rolling 7-day window, and always computed in UTC regardless of server
 *       timezone.</li>
 *   <li>{@code caseCount}/{@code skinOrGraffitiCount} count physical {@code quantity}, not the
 *       number of drop/acquisition rows (a single drop with {@code quantity=5} contributes
 *       {@code 5}, not {@code 1}).</li>
 *   <li>{@code estimatedValueUsd} = {@code quantity × currentPriceUsd} (current market price,
 *       never {@code acquisitionValueUsd}) for drops falling in the window; drops whose item has
 *       no available current price are excluded entirely from this sum.</li>
 * </ul>
 */
public record WeeklyDropStats(
        int caseCount,
        int skinOrGraffitiCount,
        BigDecimal estimatedValueUsd
) {
}

package com.dropfolio.portfolio.dto;

import java.time.LocalDate;

/**
 * Nested ref inside {@link PortfolioSummaryResponse} — API_CONTRACT.md §7.
 * {@code acquiredAt} is the drop's {@code acquisitionDate} (a calendar date, not a timestamp).
 *
 * PM Decision (Milestone 6): the "latest" drop is picked by latest {@code acquisitionDate},
 * ties broken by latest {@code createdAt}.
 */
public record LatestDropRef(
        Long itemId,
        String name,
        LocalDate acquiredAt
) {
}

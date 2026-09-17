package com.dropfolio.drop.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Locked response shape — API_CONTRACT.md §5, used for list rows, single-drop detail, and the
 * 201-created body alike (same convention as {@code ItemResponse}).
 *
 * {@code currentValueUsd} is the per-unit current market price (same scale as {@code
 * acquisitionValueUsd}) — NOT {@code quantity * price}. Multiplying by quantity into a total
 * holding value is a {@code portfolio/} concern (a later milestone), not this endpoint's.
 * {@code currentValueUsd} is {@code null} when {@code priceAvailable} is {@code false}
 * (PRD §45: never show {@code 0} as a price).
 */
public record DropResponse(
        Long id,
        DropItemRef item,
        String source,
        int quantity,
        LocalDate acquisitionDate,
        BigDecimal acquisitionValueUsd,
        BigDecimal currentValueUsd,
        boolean priceAvailable
) {
}

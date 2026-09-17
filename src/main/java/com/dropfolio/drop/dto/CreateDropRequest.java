package com.dropfolio.drop.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * API_CONTRACT.md §5 POST /drops. {@code itemId} existence + {@code isActive=true} is checked
 * in the service (DB-backed check, same reasoning as {@code CreateItemRequest.marketHashName}
 * uniqueness) — not expressible as a Bean Validation annotation. {@code source} is deliberately
 * not a field here at all: it's always {@code MANUAL} and never client-supplied
 * (API_CONTRACT.md §5: "source otomatis di-set MANUAL dan tidak bisa di-override oleh client").
 */
public record CreateDropRequest(
        @NotNull Long itemId,
        @NotNull @Positive Integer quantity,
        @NotNull @PastOrPresent LocalDate acquisitionDate,
        @Positive BigDecimal acquisitionValueUsd
) {
}

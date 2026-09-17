package com.dropfolio.drop.dto;

import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * API_CONTRACT.md §5 PATCH /drops/{id} — partial, only {@code quantity, acquisitionDate,
 * acquisitionValueUsd} allowed. {@code itemId} is NOT here, so it's immutable via PATCH (same
 * pattern as {@code type}/{@code marketHashName} on {@code UpdateItemRequest}, Milestone 3).
 *
 * PM Decision (Milestone 5): {@code null} = leave unchanged (same established convention as
 * {@code UpdateItemRequest}). There is no mechanism to clear {@code acquisitionValueUsd} back
 * to {@code null} once set through this endpoint — no sentinel value, no separate "clear" flag.
 * Jakarta Bean Validation constraints below only apply when a value is actually sent (a
 * {@code null} field is valid per {@code @Positive}/{@code @PastOrPresent} semantics; only a
 * non-null-but-invalid value fails validation).
 */
public record UpdateDropRequest(
        @Positive Integer quantity,
        @PastOrPresent LocalDate acquisitionDate,
        @Positive BigDecimal acquisitionValueUsd
) {
}

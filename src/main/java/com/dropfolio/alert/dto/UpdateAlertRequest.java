package com.dropfolio.alert.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * API_CONTRACT.md §9 PATCH /alerts/{id} — partial, {@code null} = leave unchanged (same
 * established convention as {@code UpdateDropRequest}/{@code UpdateItemRequest}). {@code
 * itemId} is NOT here — immutable via PATCH. {@code status}, when present, must be
 * {@code "ACTIVE"} or {@code "DISABLED"} (validated in {@code AlertService}, not here) —
 * {@code "TRIGGERED"} can never be set by the client, only by {@code AlertEvaluationService}.
 *
 * M8 gap fix (Postman testing round, 2 fixes):
 * <ol>
 *   <li>{@code @Digits(integer = 14, fraction = 4)} on {@code targetPriceUsd} — same DB-scale
 *       fix as {@code CreateAlertRequest}, applies here too since PATCH can also set this field.</li>
 *   <li>The "at least one of notifyEmail/notifyInApp must remain true" rule — previously
 *       disclosed here as NOT enforced on PATCH, unlike CREATE. Testing confirmed this is a
 *       genuine inconsistency (an ACTIVE alert with both channels off is functionally useless),
 *       so {@code AlertService.update()} now re-validates the MERGED state (existing values
 *       overridden by whatever this request provides) after applying a partial update, not
 *       just the raw request body — this record itself carries no cross-field validation,
 *       since it can't see the alert's existing values.</li>
 * </ol>
 */
public record UpdateAlertRequest(
        @Positive @Digits(integer = 14, fraction = 4) BigDecimal targetPriceUsd,
        Boolean notifyEmail,
        Boolean notifyInApp,
        String status
) {
}

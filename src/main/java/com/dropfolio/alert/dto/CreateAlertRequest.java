package com.dropfolio.alert.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * API_CONTRACT.md §9 POST /alerts. {@code itemId} existence + {@code isActive=true} is checked
 * in the service (DB-backed check, same pattern as {@code CreateDropRequest.itemId}).
 * {@code notifyEmail}/{@code notifyInApp} default to {@code true} when omitted (matching the
 * {@code price_alerts} schema defaults) — the "at least one must be true" rule is enforced in
 * {@code AlertService} after defaulting, not expressible as a single-field Bean Validation
 * annotation.
 *
 * M8 gap fix (Postman testing round): {@code @Digits(integer = 14, fraction = 4)} on
 * {@code targetPriceUsd} — matches {@code price_alerts.target_price_usd DECIMAL(18,4)} exactly
 * (18 total digits, 4 after the decimal point, so 14 before it). Without this, a value with
 * more than 4 fractional digits or more than 14 integer digits reached the database layer
 * un-rejected and surfaced as an uncontrolled {@code 500} on scale/precision overflow instead
 * of a clean {@code 422} — this closes that gap at the DTO boundary, before persistence.
 */
public record CreateAlertRequest(
        @NotNull Long itemId,
        @NotNull @Positive @Digits(integer = 14, fraction = 4) BigDecimal targetPriceUsd,
        Boolean notifyEmail,
        Boolean notifyInApp
) {
}

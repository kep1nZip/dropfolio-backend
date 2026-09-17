package com.dropfolio.pricing.provider;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Normalized result of a single {@link MarketPriceProvider#fetchPrice(String)} call, before
 * it is written into the internal {@code ItemPrice} entity — SYSTEM_ARCHITECTURE.md §6:
 * "Setiap hasil dari provider di-normalize ke bentuk internal (ProviderPriceResult ->
 * ItemPrice entity) sebelum disimpan".
 *
 * The exact field set for this type is not spelled out verbatim in any locked document — only
 * the interface signature ({@code ProviderPriceResult fetchPrice(String marketHashName)}) is
 * locked (SYSTEM_ARCHITECTURE.md §6). This shape is the minimum needed to normalize into the
 * {@code item_prices} columns that a provider (not the pricing read path) is responsible for
 * populating: whether a price was available, the price itself when available, and when it was
 * fetched. {@code providerName()} on the interface itself supplies the {@code provider} column
 * separately, so it is not duplicated here.
 *
 * No concrete {@link MarketPriceProvider} implementation exists yet (Milestone 4 scope freeze —
 * provider selection is a future PM decision), so this type is currently unused outside the
 * interface signature it supports. Flagged as an interpretation note in the completion report
 * rather than escalated, since it only affects compilability of an already-locked interface and
 * does not touch any locked response contract, DB schema, or endpoint behavior.
 */
public record ProviderPriceResult(
        boolean priceAvailable,
        BigDecimal priceUsd,
        Instant fetchedAt
) {
}

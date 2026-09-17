package com.dropfolio.pricing.provider;

/**
 * Market-data provider abstraction — SYSTEM_ARCHITECTURE.md §6 (PRD §44, "keputusan
 * arsitektural kritis"). {@code PricingService} depends only on this interface, never on a
 * concrete implementation.
 *
 * <p><b>Milestone 4 scope:</b> interface only. No concrete implementation (e.g. Steam Market,
 * PriceEmpire) is provided here — that decision is explicitly deferred to whenever
 * {@code PriceSyncJob} is built (PM approval, Milestone 4). Nothing in {@code pricing/} calls
 * this interface yet: {@code GET /prices/{itemId}} reads exclusively from Redis/Azure SQL
 * (cache-aside), consistent with API_CONTRACT.md §0.11 / TECHNICAL_SPEC.md §6.2 — "provider
 * hanya pernah dipanggil dari PriceSyncJob (background) — tidak pernah dari request path baca
 * manapun". This type exists now only so the abstraction boundary is in place before
 * {@code PriceSyncJob} lands in a later milestone.
 */
public interface MarketPriceProvider {

    ProviderPriceResult fetchPrice(String marketHashName);

    /** Health-check hook for circuit breaker / provider selection logic. */
    boolean isAvailable();

    /** Matches {@code item_prices.provider} — ERD.md §2.6 (e.g. {@code STEAM_MARKET}). */
    String providerName();
}

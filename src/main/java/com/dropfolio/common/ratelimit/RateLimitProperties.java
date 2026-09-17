package com.dropfolio.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds ratelimit.* from application.yml.
 *
 * {@code windowSeconds}/{@code maxRequests} = generic fallback bucket (kept for future
 * per-endpoint buckets beyond auth). {@code login}/{@code register} are the two scopes
 * TECHNICAL_SPEC.md §13 mandates wiring at the filter level (public, IP-based, checked before
 * authentication) — limits sourced from API_CONTRACT.md §0.13 baseline defaults, overridable
 * via env var without touching the contract.
 */
@ConfigurationProperties(prefix = "ratelimit")
public record RateLimitProperties(
        int windowSeconds,
        int maxRequests,
        Scope login,
        Scope register
) {
    public record Scope(int windowSeconds, int maxRequests) {
    }
}

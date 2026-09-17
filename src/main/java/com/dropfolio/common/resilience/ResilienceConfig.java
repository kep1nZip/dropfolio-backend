package com.dropfolio.common.resilience;

/**
 * Placeholder marker for the 4 named Resilience4j circuit-breaker instances required by
 * TECHNICAL_SPEC.md §7: steamApiClient, priceProvider, emailSmtp, redisCache.
 *
 * Actual sliding-window-size / failure-rate-threshold / wait-duration-in-open-state values
 * are configured declaratively in application.yml under `resilience4j.circuitbreaker.instances`
 * (see application.yml) rather than in Java config, so Resilience4jAutoConfiguration wires
 * them automatically. This class exists only as a documentation anchor; providers (steam/,
 * pricing/, notification/) apply @CircuitBreaker(name = "...", fallbackMethod = "...") on
 * their client methods once those modules are implemented — not invented here.
 */
public final class ResilienceConfig {
    private ResilienceConfig() {
    }
}

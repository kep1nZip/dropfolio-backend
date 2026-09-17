package com.dropfolio.scheduler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code scheduler.*} from application.yml. {@code priceSync().fixedDelayMs()} is also
 * referenced directly as a property placeholder in {@code @Scheduled} (Spring's
 * {@code @Scheduled} needs a SpEL/property-placeholder string, not a bean value) — this record
 * exists so the same value is available as a typed property for anything else that needs it
 * (currently nothing does; kept for symmetry with {@code lock().ttlSeconds()}, which IS consumed
 * as a typed value by {@code SchedulerLockService}).
 */
@ConfigurationProperties(prefix = "scheduler")
public record SchedulerProperties(PriceSync priceSync, Lock lock) {

    public record PriceSync(long fixedDelayMs) {
    }

    /** TTL for {@code lock:scheduler:price-sync} — CLAUDE_CONTEXT.md §8.1: "TTL 10 menit". */
    public record Lock(long ttlSeconds) {
    }
}

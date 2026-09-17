package com.dropfolio.pricing.exception;

/**
 * Marks a per-item price sync failure as recoverable at the JOB level — M7 Implementation
 * Authorization §6/§7: a single item's provider failure (timeout, malformed response, retry
 * exhaustion, circuit breaker open) must not fail the whole {@code PriceSyncJob} run.
 *
 * {@code PriceSyncJob} catches ONLY this type per item and continues the batch; any other
 * exception thrown out of {@code PriceSyncService.syncItem(...)} (e.g. a persistence/database
 * failure) is deliberately NOT this type, so it propagates and is treated as a fatal,
 * job-level failure (§7) instead of being silently swallowed as "one item failed".
 */
public class ItemPriceSyncException extends RuntimeException {

    public ItemPriceSyncException(String message, Throwable cause) {
        super(message, cause);
    }

    public ItemPriceSyncException(String message) {
        super(message);
    }
}

package com.dropfolio.pricing.provider;

/**
 * Signals a transient/technical failure from a {@link MarketPriceProvider} implementation —
 * timeout, connection failure, non-2xx response, or a malformed/unparseable body. Deliberately
 * NOT thrown for legitimate "no price available" business outcomes (e.g. Steam's own
 * {@code success:false}, or a response with no price field) — those are normal, retry-free
 * outcomes represented as {@code ProviderPriceResult(priceAvailable=false, ...)}.
 *
 * This distinction matters: {@code PriceSyncService} wraps provider calls in retry + circuit
 * breaker, and retrying a legitimate "this item isn't currently priced" response would be
 * pointless (it would just get the same answer 3 more times) — only this exception type
 * should trigger a retry attempt.
 */
public class MarketPriceProviderException extends RuntimeException {

    public MarketPriceProviderException(String message, Throwable cause) {
        super(message, cause);
    }

    public MarketPriceProviderException(String message) {
        super(message);
    }
}

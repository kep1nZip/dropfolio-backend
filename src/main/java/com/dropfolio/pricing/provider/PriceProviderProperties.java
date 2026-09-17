package com.dropfolio.pricing.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code pricing.provider.*} from application.yml. Timeout values are locked at
 * TECHNICAL_SPEC.md §7 (3s connect / 5s read for the Steam Market price source) — exposed as
 * config (not hardcoded) so they can be tuned operationally without a code change, per the
 * project's existing convention for every other external-dependency timeout.
 *
 * Steam's public {@code priceoverview} endpoint requires no API key/credential — M7
 * Implementation Authorization §10/§20 explicitly forbids reviving {@code steam.api-key} for
 * this purpose, and none was needed: this is the same public, unauthenticated endpoint the
 * Steam Community Market website itself calls to render a listing's price.
 */
@ConfigurationProperties(prefix = "pricing.provider")
public record PriceProviderProperties(
        String baseUrl,
        int appId,
        int currency,
        int connectTimeoutMs,
        int readTimeoutMs
) {
}

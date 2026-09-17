package com.dropfolio.pricing.provider;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Concrete {@link MarketPriceProvider} backed by Steam Community Market's public,
 * unauthenticated {@code priceoverview} endpoint — M7 Implementation Authorization §10.
 * Steam is used ONLY as a market-price source here: no login, no OAuth, no inventory API, no
 * account linking (§10 explicit prohibition; §26 stop condition if any of those turned out to
 * be required — they are not, for this endpoint).
 *
 * The rest of the application depends only on {@link MarketPriceProvider} (never on this
 * class directly) — {@code PriceSyncService} is the sole caller, consistent with
 * CLAUDE_CONTEXT.md §3: "pricing adalah satu-satunya modul yang memanggil market-price provider".
 */
@Component
public class SteamMarketPriceProvider implements MarketPriceProvider {

    private static final String PROVIDER_NAME = "STEAM_MARKET";
    /** Strips currency symbols/whitespace, keeps digits + one decimal separator. */
    private static final Pattern NON_NUMERIC = Pattern.compile("[^0-9.,]");

    private final RestClient restClient;
    private final PriceProviderProperties properties;
    private final Clock clock;

    public SteamMarketPriceProvider(RestClient steamMarketRestClient, PriceProviderProperties properties, Clock clock) {
        this.restClient = steamMarketRestClient;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public ProviderPriceResult fetchPrice(String marketHashName) {
        SteamPriceOverviewResponse body;
        try {
            body = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/market/priceoverview/")
                            .queryParam("appid", properties.appId())
                            .queryParam("currency", properties.currency())
                            .queryParam("market_hash_name", marketHashName)
                            .build())
                    .retrieve()
                    .body(SteamPriceOverviewResponse.class);
        } catch (RestClientException e) {
            // Covers timeout, connection failure, and non-2xx status — all "provider failure",
            // all eligible for retry (M7 Implementation Authorization §10).
            throw new MarketPriceProviderException("Steam Market request failed for " + marketHashName, e);
        }

        if (body == null) {
            throw new MarketPriceProviderException("Steam Market returned an empty body for " + marketHashName);
        }

        if (!body.success()) {
            // Legitimate business outcome — item not found / not currently marketable on Steam.
            // Not an error, not retried.
            return new ProviderPriceResult(false, null, clock.instant());
        }

        String rawPrice = body.lowestPrice() != null ? body.lowestPrice() : body.medianPrice();
        BigDecimal price = parsePrice(rawPrice, marketHashName);
        Instant fetchedAt = clock.instant();
        return price != null
                ? new ProviderPriceResult(true, price, fetchedAt)
                : new ProviderPriceResult(false, null, fetchedAt);
    }

    /**
     * Steam formats prices with a currency symbol/suffix and a thousands separator that varies
     * by requested currency (e.g. {@code "$0.03"}, {@code "0,03€"}). A field being present but
     * genuinely unparseable is treated as malformed (retry-eligible) — a missing field entirely
     * (null/blank) is treated as "no price available" (not malformed, not retried), matching
     * how Steam represents low-volume items that have no current listing.
     */
    private BigDecimal parsePrice(String raw, String marketHashName) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher matcher = NON_NUMERIC.matcher(raw);
        String cleaned = matcher.replaceAll("");
        // Normalize "1.234,56" / "1,234.56" style separators down to a single '.' decimal point.
        if (cleaned.contains(",") && cleaned.contains(".")) {
            // Whichever separator appears last is the decimal separator.
            cleaned = cleaned.lastIndexOf(',') > cleaned.lastIndexOf('.')
                    ? cleaned.replace(".", "").replace(",", ".")
                    : cleaned.replace(",", "");
        } else if (cleaned.contains(",")) {
            cleaned = cleaned.replace(",", ".");
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            throw new MarketPriceProviderException(
                    "Malformed price value \"" + raw + "\" from Steam Market for " + marketHashName, e);
        }
    }

    @Override
    public boolean isAvailable() {
        // No dedicated Steam Market health-check endpoint is documented/locked anywhere in the
        // approved specs; provider health for this milestone is derived entirely from the
        // `priceProvider` circuit breaker's own state (see PricingService/PriceSyncService),
        // which already reflects real call outcomes. This flag exists only to satisfy the
        // MarketPriceProvider interface contract and is not consulted by any M7 code path —
        // flagged here as an interpretation note rather than a silent assumption.
        return true;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }
}

package com.dropfolio.pricing.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Raw JSON shape of Steam Community Market's public
 * {@code /market/priceoverview/} endpoint, e.g.:
 * <pre>{"success":true,"lowest_price":"$0.03","volume":"1,234","median_price":"$0.03"}</pre>
 * or, for an unrecognized/non-marketable {@code market_hash_name}: {@code {"success":false}}.
 *
 * This is a third-party wire format, not part of any locked internal contract — fields are
 * nullable/optional by design since Steam omits {@code lowest_price}/{@code median_price}/
 * {@code volume} entirely on failure.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SteamPriceOverviewResponse(
        boolean success,
        @JsonProperty("lowest_price") String lowestPrice,
        @JsonProperty("median_price") String medianPrice,
        String volume
) {
}

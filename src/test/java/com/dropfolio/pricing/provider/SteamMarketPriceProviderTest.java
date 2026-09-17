package com.dropfolio.pricing.provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SteamMarketPriceProvider} — M7 Implementation Authorization §10/§22.
 * {@link RestClient}'s fluent builder chain is mocked with {@code RETURNS_DEEP_STUBS} (no real
 * HTTP call) — this exercises response-mapping/parsing logic only, not real network behavior
 * against Steam. Deep stubs are used (rather than mocking each intermediate fluent-interface
 * type individually) because {@code RequestHeadersUriSpec}/{@code RequestHeadersSpec} are
 * self-referential generic interfaces that are awkward to mock precisely without introducing
 * unchecked-cast noise unrelated to what these tests actually verify.
 */
class SteamMarketPriceProviderTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-10T00:00:00Z");
    private static final PriceProviderProperties PROPERTIES =
            new PriceProviderProperties("https://steamcommunity.com", 730, 1, 3000, 5000);

    private RestClient restClient;
    private Clock clock;
    private SteamMarketPriceProvider provider;

    @BeforeEach
    void setUp() {
        restClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        provider = new SteamMarketPriceProvider(restClient, PROPERTIES, clock);
    }

    @SuppressWarnings("unchecked")
    private void stubResponse(SteamPriceOverviewResponse body) {
        when(restClient.get().uri(any(java.util.function.Function.class)).retrieve()
                .body(SteamPriceOverviewResponse.class)).thenReturn(body);
    }

    @Test
    void fetchPrice_successfulResponse_mapsLowestPrice() {
        stubResponse(new SteamPriceOverviewResponse(true, "$0.03", "$0.04", "1,234"));

        ProviderPriceResult result = provider.fetchPrice("AK-47 | Redline (Field-Tested)");

        assertThat(result.priceAvailable()).isTrue();
        assertThat(result.priceUsd()).isEqualByComparingTo(new BigDecimal("0.03"));
        assertThat(result.fetchedAt()).isEqualTo(FIXED_INSTANT);
    }

    @Test
    void fetchPrice_fallsBackToMedianPrice_whenLowestPriceMissing() {
        stubResponse(new SteamPriceOverviewResponse(true, null, "$1.50", "10"));

        ProviderPriceResult result = provider.fetchPrice("Some Item");

        assertThat(result.priceAvailable()).isTrue();
        assertThat(result.priceUsd()).isEqualByComparingTo(new BigDecimal("1.50"));
    }

    @Test
    void fetchPrice_unavailableItem_successFalse_returnsPriceUnavailable_doesNotThrow() {
        stubResponse(new SteamPriceOverviewResponse(false, null, null, null));

        ProviderPriceResult result = provider.fetchPrice("Nonexistent Item");

        assertThat(result.priceAvailable()).isFalse();
        assertThat(result.priceUsd()).isNull();
    }

    @Test
    void fetchPrice_successTrueButNoPriceFields_returnsPriceUnavailable_doesNotThrow() {
        stubResponse(new SteamPriceOverviewResponse(true, null, null, null));

        ProviderPriceResult result = provider.fetchPrice("Low Volume Item");

        assertThat(result.priceAvailable()).isFalse();
    }

    @Test
    void fetchPrice_malformedPriceValue_throwsMarketPriceProviderException() {
        stubResponse(new SteamPriceOverviewResponse(true, "not-a-price", null, null));

        assertThatThrownBy(() -> provider.fetchPrice("Weird Item"))
                .isInstanceOf(MarketPriceProviderException.class);
    }

    @Test
    void fetchPrice_emptyBody_throwsMarketPriceProviderException() {
        stubResponse(null);

        assertThatThrownBy(() -> provider.fetchPrice("Any Item"))
                .isInstanceOf(MarketPriceProviderException.class);
    }

    @SuppressWarnings("unchecked")
    @Test
    void fetchPrice_restClientThrows_timeoutOrConnectionFailure_wrapsAsMarketPriceProviderException() {
        when(restClient.get().uri(any(java.util.function.Function.class)).retrieve())
                .thenThrow(new RestClientException("connection timed out"));

        assertThatThrownBy(() -> provider.fetchPrice("Any Item"))
                .isInstanceOf(MarketPriceProviderException.class)
                .hasCauseInstanceOf(RestClientException.class);
    }

    @Test
    void providerName_isSteamMarket() {
        assertThat(provider.providerName()).isEqualTo("STEAM_MARKET");
    }

    @Test
    void isAvailable_alwaysTrue_noDedicatedHealthCheckEndpointLocked() {
        assertThat(provider.isAvailable()).isTrue();
    }
}

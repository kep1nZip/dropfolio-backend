package com.dropfolio.pricing.config;

import com.dropfolio.pricing.provider.PriceProviderProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * {@link RestClient} for {@code SteamMarketPriceProvider} — connect/read timeouts locked at
 * TECHNICAL_SPEC.md §7 (3s / 5s), sourced from {@link PriceProviderProperties} rather than
 * hardcoded. Kept in {@code pricing/config} (not {@code common/}) since only {@code pricing/}
 * is allowed to call the market-price provider (CLAUDE_CONTEXT.md §3).
 */
@Configuration
public class PriceProviderClientConfig {

    @Bean
    public RestClient steamMarketRestClient(PriceProviderProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeoutMs());
        factory.setReadTimeout(properties.readTimeoutMs());

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }
}

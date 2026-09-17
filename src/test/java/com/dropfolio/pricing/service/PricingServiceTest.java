package com.dropfolio.pricing.service;

import com.dropfolio.common.cache.CacheKeys;
import com.dropfolio.common.exception.ResourceNotFoundException;
import com.dropfolio.item.repository.ItemRepository;
import com.dropfolio.pricing.dto.PriceResponse;
import com.dropfolio.pricing.entity.ItemPrice;
import com.dropfolio.pricing.repository.ItemPriceRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for PricingService — cache-aside read flow, TECHNICAL_SPEC.md §6.2/§6.3.
 * Redis and repositories mocked; a real {@link CircuitBreakerRegistry} is used so the
 * {@code redisCache} circuit-breaker OPEN scenario can be tested by forcing its state directly,
 * matching the same "real registry, mocked collaborators" pattern used for provider abstraction
 * tests elsewhere in this codebase.
 */
@ExtendWith(MockitoExtension.class)
class PricingServiceTest {

    @Mock private ItemPriceRepository itemPriceRepository;
    @Mock private ItemRepository itemRepository;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> valueOperations;

    private CircuitBreakerRegistry circuitBreakerRegistry;
    private PricingService pricingService;

    @BeforeEach
    void setUp() {
        circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
        pricingService = new PricingService(itemPriceRepository, itemRepository, redisTemplate, circuitBreakerRegistry);
    }

    @Test
    void getPrice_itemNotInCatalog_throwsResourceNotFound() {
        when(itemRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> pricingService.getPrice(99L))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoRedisOrSqlInteraction();
    }

    @Test
    void getPrice_redisCacheHit_returnsCachedValue_neverTouchesSql() {
        when(itemRepository.existsById(5L)).thenReturn(true);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        PriceResponse cached = new PriceResponse(5L, new BigDecimal("0.52"), true, "STEAM_MARKET", Instant.parse("2026-08-31T09:55:00Z"));
        when(valueOperations.get(CacheKeys.priceItem(5L))).thenReturn(cached);

        PriceResponse result = pricingService.getPrice(5L);

        assertThat(result).isEqualTo(cached);
        verify(itemPriceRepository, never()).findTopByItemIdOrderByFetchedAtDesc(any());
    }

    @Test
    void getPrice_redisMiss_sqlHit_buildsResponseAndRepopulatesCache() {
        when(itemRepository.existsById(5L)).thenReturn(true);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CacheKeys.priceItem(5L))).thenReturn(null);

        ItemPrice row = ItemPrice.builder()
                .id(1L).itemId(5L).provider("STEAM_MARKET")
                .priceUsd(new BigDecimal("0.52")).priceAvailable(true)
                .fetchedAt(Instant.parse("2026-08-31T09:55:00Z")).createdAt(Instant.now())
                .build();
        when(itemPriceRepository.findTopByItemIdOrderByFetchedAtDesc(5L)).thenReturn(Optional.of(row));

        PriceResponse result = pricingService.getPrice(5L);

        assertThat(result.itemId()).isEqualTo(5L);
        assertThat(result.priceUsd()).isEqualByComparingTo("0.52");
        assertThat(result.priceAvailable()).isTrue();
        assertThat(result.provider()).isEqualTo("STEAM_MARKET");

        // SQL HIT -> Redis repopulation, with the 10-minute baseline TTL.
        verify(valueOperations).set(eq(CacheKeys.priceItem(5L)), eq(result), eq(Duration.ofMinutes(10)));
    }

    @Test
    void getPrice_redisMiss_sqlMiss_returnsPriceUnavailable() {
        when(itemRepository.existsById(5L)).thenReturn(true);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CacheKeys.priceItem(5L))).thenReturn(null);
        when(itemPriceRepository.findTopByItemIdOrderByFetchedAtDesc(5L)).thenReturn(Optional.empty());

        PriceResponse result = pricingService.getPrice(5L);

        assertThat(result.itemId()).isEqualTo(5L);
        assertThat(result.priceUsd()).isNull();
        assertThat(result.priceAvailable()).isFalse();
        assertThat(result.provider()).isNull();
    }

    @Test
    void getPrice_redisCircuitBreakerOpen_skipsRedis_readsSqlDirectly() {
        when(itemRepository.existsById(5L)).thenReturn(true);
        CircuitBreaker redisCircuitBreaker = circuitBreakerRegistry.circuitBreaker("redisCache");
        redisCircuitBreaker.transitionToOpenState();

        ItemPrice row = ItemPrice.builder()
                .id(1L).itemId(5L).provider("STEAM_MARKET")
                .priceUsd(new BigDecimal("0.52")).priceAvailable(true)
                .fetchedAt(Instant.parse("2026-08-31T09:55:00Z")).createdAt(Instant.now())
                .build();
        when(itemPriceRepository.findTopByItemIdOrderByFetchedAtDesc(5L)).thenReturn(Optional.of(row));

        PriceResponse result = pricingService.getPrice(5L);

        assertThat(result.priceUsd()).isEqualByComparingTo("0.52");
        // Circuit OPEN -> Redis is never actually touched (opsForValue not obtained at all).
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void getPrice_neverCallsMarketPriceProvider() {
        // Structural invariant, not just a single-test assertion: PricingService has no
        // dependency on MarketPriceProvider at all (see its constructor) — provider calls are
        // exclusively PriceSyncJob's responsibility (a later milestone), per API_CONTRACT.md
        // §0.11 / TECHNICAL_SPEC.md §6.2. This test documents that invariant by exercising a
        // full SQL-miss read and confirming no provider-shaped interaction is possible, since
        // no such collaborator exists to mock.
        when(itemRepository.existsById(5L)).thenReturn(true);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CacheKeys.priceItem(5L))).thenReturn(null);
        when(itemPriceRepository.findTopByItemIdOrderByFetchedAtDesc(5L)).thenReturn(Optional.empty());

        pricingService.getPrice(5L);

        // No assertion needed beyond "this compiles and runs without a MarketPriceProvider
        // mock" — PricingService's constructor signature itself proves the invariant.
    }

    private void verifyNoRedisOrSqlInteraction() {
        verify(redisTemplate, never()).opsForValue();
        verify(itemPriceRepository, never()).findTopByItemIdOrderByFetchedAtDesc(any());
    }
}

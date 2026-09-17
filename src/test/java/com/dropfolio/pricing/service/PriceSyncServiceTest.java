package com.dropfolio.pricing.service;

import com.dropfolio.alert.service.AlertEvaluationService;
import com.dropfolio.common.cache.CacheKeys;
import com.dropfolio.item.entity.Item;
import com.dropfolio.item.entity.ItemType;
import com.dropfolio.pricing.dto.PriceResponse;
import com.dropfolio.pricing.entity.ItemPrice;
import com.dropfolio.pricing.exception.ItemPriceSyncException;
import com.dropfolio.pricing.provider.MarketPriceProvider;
import com.dropfolio.pricing.provider.MarketPriceProviderException;
import com.dropfolio.pricing.provider.ProviderPriceResult;
import com.dropfolio.pricing.repository.ItemPriceRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PriceSyncService} — M7 Implementation Authorization §22, extended for
 * M8 Implementation Authorization §9/§10 (embedded {@code AlertEvaluationService} call inside
 * the same per-item transaction). {@code RetryConfig}/{@code CircuitBreakerConfig} use
 * short/near-zero wait durations here (not the real 1s/2s/4s from application.yml) so
 * retry-exhaustion tests run fast and deterministically without real sleeps.
 * {@link TransactionOperations} is a synchronous test stub (no real datasource) — same pattern
 * already used in M7's {@code AdminSyncServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class PriceSyncServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-10T00:00:00Z");

    @Mock private MarketPriceProvider marketPriceProvider;
    @Mock private ItemPriceRepository itemPriceRepository;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> valueOperations;
    @Mock private AlertEvaluationService alertEvaluationService;

    private CircuitBreakerRegistry circuitBreakerRegistry;
    private RetryRegistry retryRegistry;
    private TransactionOperations transactionOperations;
    private Clock clock;
    private PriceSyncService priceSyncService;

    private Item item;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

        RetryConfig fastRetryConfig = RetryConfig.custom()
                .maxAttempts(4)
                .waitDuration(Duration.ofMillis(1))
                .build();
        retryRegistry = RetryRegistry.of(fastRetryConfig);

        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMillis(1))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();
        circuitBreakerRegistry = CircuitBreakerRegistry.of(cbConfig);

        // Synchronous "transaction" stub — just runs the callback immediately, no real datasource.
        transactionOperations = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };

        priceSyncService = new PriceSyncService(
                marketPriceProvider, itemPriceRepository, redisTemplate,
                circuitBreakerRegistry, retryRegistry, alertEvaluationService, transactionOperations, clock);

        item = Item.builder().id(5L).name("AK-47 | Redline").type(ItemType.SKIN)
                .marketHashName("AK-47 | Redline (Field-Tested)").isActive(true).build();
    }

    @Test
    void syncItem_successfulResponse_persistsAndRefreshesCache() {
        when(marketPriceProvider.fetchPrice(item.getMarketHashName()))
                .thenReturn(new ProviderPriceResult(true, new BigDecimal("12.34"), FIXED_INSTANT));
        when(marketPriceProvider.providerName()).thenReturn("STEAM_MARKET");
        when(itemPriceRepository.save(any(ItemPrice.class))).thenAnswer(inv -> inv.getArgument(0));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        priceSyncService.syncItem(item);

        verify(itemPriceRepository).save(argThatMatchesPersistedPrice());
        verify(valueOperations).set(eq(CacheKeys.priceItem(5L)), any(PriceResponse.class), eq(Duration.ofMinutes(10)));
    }

    /** M8 §9/§10 — alert evaluation runs for every successfully-persisted price, same transaction. */
    @Test
    void syncItem_successfulResponse_evaluatesAlertsInSameTransaction() {
        when(marketPriceProvider.fetchPrice(item.getMarketHashName()))
                .thenReturn(new ProviderPriceResult(true, new BigDecimal("12.34"), FIXED_INSTANT));
        when(marketPriceProvider.providerName()).thenReturn("STEAM_MARKET");
        when(itemPriceRepository.save(any(ItemPrice.class))).thenAnswer(inv -> inv.getArgument(0));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        priceSyncService.syncItem(item);

        verify(alertEvaluationService).evaluate(eq(item), argThatMatchesPersistedPrice());
    }

    @Test
    void syncItem_itemUnavailable_persistsNullPriceAvailableFalse_doesNotThrow() {
        when(marketPriceProvider.fetchPrice(item.getMarketHashName()))
                .thenReturn(new ProviderPriceResult(false, null, FIXED_INSTANT));
        when(marketPriceProvider.providerName()).thenReturn("STEAM_MARKET");
        when(itemPriceRepository.save(any(ItemPrice.class))).thenAnswer(inv -> inv.getArgument(0));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        priceSyncService.syncItem(item);

        verify(itemPriceRepository).save(any(ItemPrice.class));
        // Still evaluated — AlertEvaluationService itself is the one that no-ops on an
        // unavailable price (PriceSyncService doesn't special-case this).
        verify(alertEvaluationService).evaluate(any(Item.class), any(ItemPrice.class));
    }

    @Test
    void syncItem_providerThrowsTransientFailure_exhaustsRetries_thenThrowsItemPriceSyncException() {
        when(marketPriceProvider.fetchPrice(item.getMarketHashName()))
                .thenThrow(new MarketPriceProviderException("timeout"));

        assertThatThrownBy(() -> priceSyncService.syncItem(item))
                .isInstanceOf(ItemPriceSyncException.class);

        // maxAttempts=4 in the test's fast RetryConfig -> exactly 4 real calls to the provider.
        verify(marketPriceProvider, times(4)).fetchPrice(item.getMarketHashName());
        verify(itemPriceRepository, never()).save(any());
        verifyNoInteractions(alertEvaluationService);
    }

    @Test
    void syncItem_malformedResponse_isTreatedAsRetryableFailure() {
        when(marketPriceProvider.fetchPrice(item.getMarketHashName()))
                .thenThrow(new MarketPriceProviderException("malformed price value"));

        assertThatThrownBy(() -> priceSyncService.syncItem(item))
                .isInstanceOf(ItemPriceSyncException.class);
    }

    @Test
    void syncItem_persistenceFailure_propagatesAsIs_notWrappedAsItemLevelFailure() {
        when(marketPriceProvider.fetchPrice(item.getMarketHashName()))
                .thenReturn(new ProviderPriceResult(true, new BigDecimal("1.00"), FIXED_INSTANT));
        when(marketPriceProvider.providerName()).thenReturn("STEAM_MARKET");
        when(itemPriceRepository.save(any(ItemPrice.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> priceSyncService.syncItem(item))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .isNotInstanceOf(ItemPriceSyncException.class);
        // Persist failed inside the transactional block before alert evaluation ran.
        verifyNoInteractions(alertEvaluationService);
    }

    /**
     * M8 §7 (extended): an alert-evaluation/notification/email-enqueue failure is now inside
     * the same transaction as the persist — so it propagates as-is too (job-level fatal), same
     * classification M7 already applied to a raw persistence failure. Not wrapped as
     * {@link ItemPriceSyncException}, so {@code PriceSyncJob}'s per-item catch does not
     * swallow it.
     */
    @Test
    void syncItem_alertEvaluationFailure_propagatesAsIs_notWrappedAsItemLevelFailure() {
        when(marketPriceProvider.fetchPrice(item.getMarketHashName()))
                .thenReturn(new ProviderPriceResult(true, new BigDecimal("1.00"), FIXED_INSTANT));
        when(marketPriceProvider.providerName()).thenReturn("STEAM_MARKET");
        when(itemPriceRepository.save(any(ItemPrice.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new DataAccessResourceFailureException("alert eval db failure"))
                .when(alertEvaluationService).evaluate(any(Item.class), any(ItemPrice.class));

        assertThatThrownBy(() -> priceSyncService.syncItem(item))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .isNotInstanceOf(ItemPriceSyncException.class);
        // Redis refresh must not run — the transaction (including the item_prices insert) is
        // considered failed/rolled-back, so there's nothing durable yet to cache.
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void syncItem_redisRefreshFailure_isSwallowed_doesNotFailTheSync() {
        when(marketPriceProvider.fetchPrice(item.getMarketHashName()))
                .thenReturn(new ProviderPriceResult(true, new BigDecimal("1.00"), FIXED_INSTANT));
        when(marketPriceProvider.providerName()).thenReturn("STEAM_MARKET");
        when(itemPriceRepository.save(any(ItemPrice.class))).thenAnswer(inv -> inv.getArgument(0));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(valueOperations).set(any(), any(), any(Duration.class));

        // Must not throw.
        priceSyncService.syncItem(item);

        verify(itemPriceRepository).save(any(ItemPrice.class));
    }

    private ItemPrice argThatMatchesPersistedPrice() {
        return org.mockito.ArgumentMatchers.argThat(ip ->
                ip.getItemId().equals(5L)
                        && ip.getProvider().equals("STEAM_MARKET")
                        && ip.getPriceUsd().compareTo(new BigDecimal("12.34")) == 0
                        && Boolean.TRUE.equals(ip.getPriceAvailable())
                        && ip.getFetchedAt().equals(FIXED_INSTANT));
    }
}


package com.dropfolio.pricing.service;

import com.dropfolio.alert.service.AlertEvaluationService;
import com.dropfolio.common.cache.CacheKeys;
import com.dropfolio.item.entity.Item;
import com.dropfolio.pricing.dto.PriceResponse;
import com.dropfolio.pricing.entity.ItemPrice;
import com.dropfolio.pricing.exception.ItemPriceSyncException;
import com.dropfolio.pricing.provider.MarketPriceProvider;
import com.dropfolio.pricing.provider.ProviderPriceResult;
import com.dropfolio.pricing.repository.ItemPriceRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * The ONLY place {@code PriceSyncJob} reaches into the {@code pricing} module to fetch a fresh
 * price — deliberately separate from {@code PricingService} (the read path), which
 * {@code PriceSyncJob} must never touch or influence beyond what this class writes into
 * {@code item_prices}/Redis. M4's read-path behavior stays unchanged (M7 Implementation
 * Authorization §12).
 *
 * Wraps every provider call with: Retry (named instance {@code priceProvider}) as the OUTER
 * decorator, CircuitBreaker (also named {@code priceProvider}, NOT {@code steamApiClient} —
 * §9 lock) as the INNER decorator — this ordering means once the breaker opens, further retry
 * attempts within the same call fail fast via {@code CallNotPermittedException} instead of
 * making real calls against a known-down provider.
 *
 * M8 addition: after a successful {@code item_prices} persist, {@link AlertEvaluationService}
 * runs in the SAME per-item transaction (M8 Implementation Authorization §9/§10,
 * TECHNICAL_SPEC.md §9.3/§10) — NOT one transaction for the whole batch (that would mean one
 * item's alert-evaluation failure could roll back every other item already committed in the
 * same run, which M8 §10 explicitly forbids). {@link TransactionOperations} (not a
 * {@code @Transactional} method on this class) is used deliberately — a {@code @Transactional}
 * method called via {@code this.foo()} self-invocation would silently NOT be proxied by Spring
 * AOP and would run with no transaction at all; the same pattern was already established for
 * exactly this reason in M7's {@code AdminSyncService}.
 */
@Service
public class PriceSyncService {

    private static final Logger log = LoggerFactory.getLogger(PriceSyncService.class);

    /** Same 10-minute baseline TTL as the M4 read path — M7 Implementation Authorization §12: "Jangan mengubah TTL". */
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final String PRICE_PROVIDER_INSTANCE = "priceProvider";

    private final MarketPriceProvider marketPriceProvider;
    private final ItemPriceRepository itemPriceRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final CircuitBreaker priceProviderCircuitBreaker;
    private final Retry priceProviderRetry;
    private final AlertEvaluationService alertEvaluationService;
    private final TransactionOperations transactionOperations;
    private final Clock clock;

    public PriceSyncService(MarketPriceProvider marketPriceProvider,
                             ItemPriceRepository itemPriceRepository,
                             RedisTemplate<String, Object> redisTemplate,
                             CircuitBreakerRegistry circuitBreakerRegistry,
                             RetryRegistry retryRegistry,
                             AlertEvaluationService alertEvaluationService,
                             TransactionOperations transactionOperations,
                             Clock clock) {
        this.marketPriceProvider = marketPriceProvider;
        this.itemPriceRepository = itemPriceRepository;
        this.redisTemplate = redisTemplate;
        this.priceProviderCircuitBreaker = circuitBreakerRegistry.circuitBreaker(PRICE_PROVIDER_INSTANCE);
        this.priceProviderRetry = retryRegistry.retry(PRICE_PROVIDER_INSTANCE);
        this.alertEvaluationService = alertEvaluationService;
        this.transactionOperations = transactionOperations;
        this.clock = clock;
    }

    /**
     * Fetches, persists + evaluates alerts (one transaction), and (best-effort) refreshes the
     * cache for exactly one item.
     *
     * @throws ItemPriceSyncException on any recoverable, item-level failure (provider timeout,
     *                                malformed response, retry exhaustion, circuit breaker
     *                                open) — {@code PriceSyncJob} catches this type and moves
     *                                on to the next item (M7 §6). Any other exception (notably
     *                                a persistence/database failure, which now also covers
     *                                alert-evaluation/notification/email-enqueue failures since
     *                                M8 puts them in the same transaction) is NOT wrapped and
     *                                propagates as-is, since that is a job-level condition
     *                                (§7), not an item-level one — unchanged M7 classification,
     *                                just now covering a slightly larger unit of work per §10.
     */
    public void syncItem(Item item) {
        ProviderPriceResult result = fetchWithResilience(item);
        ItemPrice saved = transactionOperations.execute(status -> {
            ItemPrice persisted = persist(item, result);
            alertEvaluationService.evaluate(item, persisted);
            return persisted;
        });
        refreshCache(item.getId(), saved);
    }

    private ProviderPriceResult fetchWithResilience(Item item) {
        Supplier<ProviderPriceResult> call = () -> marketPriceProvider.fetchPrice(item.getMarketHashName());
        Supplier<ProviderPriceResult> resilient =
                Retry.decorateSupplier(priceProviderRetry, CircuitBreaker.decorateSupplier(priceProviderCircuitBreaker, call));
        try {
            return resilient.get();
        } catch (Exception e) {
            throw new ItemPriceSyncException(
                    "Price fetch failed for item " + item.getId() + " (" + item.getMarketHashName() + ")", e);
        }
    }

    /**
     * A persistence failure here is NOT an item-level condition — it signals the database
     * itself is unavailable, so the whole job cannot reliably continue (M7 §7). Deliberately
     * rethrown as-is (not wrapped in {@link ItemPriceSyncException}) so {@code PriceSyncJob}'s
     * per-item catch does not swallow it.
     */
    private ItemPrice persist(Item item, ProviderPriceResult result) {
        ItemPrice itemPrice = ItemPrice.builder()
                .itemId(item.getId())
                .provider(marketPriceProvider.providerName())
                .priceUsd(result.priceAvailable() ? result.priceUsd() : null)
                .priceAvailable(result.priceAvailable())
                .fetchedAt(result.fetchedAt())
                .createdAt(clock.instant())
                .build();
        try {
            return itemPriceRepository.save(itemPrice);
        } catch (DataAccessException dbFailure) {
            throw dbFailure;
        }
    }

    private void refreshCache(Long itemId, ItemPrice itemPrice) {
        PriceResponse response = new PriceResponse(
                itemId,
                itemPrice.getPriceAvailable() ? itemPrice.getPriceUsd() : null,
                Boolean.TRUE.equals(itemPrice.getPriceAvailable()),
                itemPrice.getProvider(),
                itemPrice.getFetchedAt());
        String key = CacheKeys.priceItem(itemId);
        try {
            redisTemplate.opsForValue().set(key, response, CACHE_TTL);
        } catch (Exception redisFailure) {
            // Best-effort — SQL is already durable (item_prices row committed above); a failed
            // proactive refresh just means the next GET /prices/{itemId} falls through to SQL
            // once on cache miss, the same degraded-mode behavior PricingService already
            // tolerates on the read side. Never fails the sync for this item.
            log.warn("Redis proactive refresh failed for item {}", itemId, redisFailure);
        }
    }
}

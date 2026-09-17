package com.dropfolio.pricing.service;

import com.dropfolio.common.cache.CacheKeys;
import com.dropfolio.common.exception.ResourceNotFoundException;
import com.dropfolio.item.repository.ItemRepository;
import com.dropfolio.pricing.dto.PriceResponse;
import com.dropfolio.pricing.entity.ItemPrice;
import com.dropfolio.pricing.repository.ItemPriceRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * Cache-aside read path for {@code GET /prices/{itemId}} — TECHNICAL_SPEC.md §6.2.
 *
 * <pre>
 * Redis GET
 *    HIT  -> deserialize -> return
 *    MISS -> SELECT TOP 1 item_prices ORDER BY fetched_at DESC
 *              SQL HIT  -> build response, repopulate Redis, return
 *              SQL MISS -> priceAvailable=false, priceUsd=null, provider=null, return
 * </pre>
 *
 * Never calls {@link com.dropfolio.pricing.provider.MarketPriceProvider} — provider calls are
 * exclusively background (PriceSyncJob, a later milestone), per API_CONTRACT.md §0.11 /
 * TECHNICAL_SPEC.md §6.2. If the {@code redisCache} circuit breaker is OPEN or a Redis call
 * fails, this service skips Redis entirely and reads SQL directly (TECHNICAL_SPEC.md §6.3) —
 * it never calls the provider as a Redis-down fallback either.
 */
@Service
public class PricingService {

    private static final Logger log = LoggerFactory.getLogger(PricingService.class);

    /** TTL (baseline) — TECHNICAL_SPEC.md §6.1. Not to be changed without a spec revision. */
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final ItemPriceRepository itemPriceRepository;
    private final ItemRepository itemRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final CircuitBreaker redisCircuitBreaker;

    public PricingService(ItemPriceRepository itemPriceRepository,
                           ItemRepository itemRepository,
                           RedisTemplate<String, Object> redisTemplate,
                           CircuitBreakerRegistry circuitBreakerRegistry) {
        this.itemPriceRepository = itemPriceRepository;
        this.itemRepository = itemRepository;
        this.redisTemplate = redisTemplate;
        // Named instance configured in application.yml (resilience4j.circuitbreaker.instances.redisCache)
        // — TECHNICAL_SPEC.md §7.
        this.redisCircuitBreaker = circuitBreakerRegistry.circuitBreaker("redisCache");
    }

    @Transactional(readOnly = true)
    public PriceResponse getPrice(Long itemId) {
        // Item existence vs. "item has no price row yet" must NOT be conflated —
        // API_CONTRACT.md §8: 404 only when the item itself isn't in the catalog.
        if (!itemRepository.existsById(itemId)) {
            throw new ResourceNotFoundException("Item not found");
        }

        PriceResponse cached = readFromCache(itemId);
        if (cached != null) {
            return cached;
        }

        PriceResponse fromSql = readFromSql(itemId);
        writeToCache(itemId, fromSql);
        return fromSql;
    }

    private PriceResponse readFromCache(Long itemId) {
        String key = CacheKeys.priceItem(itemId);
        try {
            Object value = redisCircuitBreaker.executeSupplier(() -> redisTemplate.opsForValue().get(key));
            return value instanceof PriceResponse priceResponse ? priceResponse : null;
        } catch (CallNotPermittedException e) {
            // Circuit OPEN — skip Redis, fall straight through to SQL (TECHNICAL_SPEC.md §6.3).
            log.warn("redisCache circuit breaker OPEN, skipping cache read for item {}", itemId);
            return null;
        } catch (Exception e) {
            // Any other Redis failure is treated as a cache miss, not a request failure.
            log.warn("Redis read failed for item {}, falling back to SQL", itemId, e);
            return null;
        }
    }

    private PriceResponse readFromSql(Long itemId) {
        return itemPriceRepository.findTopByItemIdOrderByFetchedAtDesc(itemId)
                .map(this::toResponse)
                .orElseGet(() -> unavailableResponse(itemId));
    }

    private void writeToCache(Long itemId, PriceResponse response) {
        String key = CacheKeys.priceItem(itemId);
        try {
            redisCircuitBreaker.executeRunnable(() ->
                    redisTemplate.opsForValue().set(key, response, CACHE_TTL));
        } catch (CallNotPermittedException e) {
            log.warn("redisCache circuit breaker OPEN, skipping cache repopulation for item {}", itemId);
        } catch (Exception e) {
            // Repopulation is best-effort — a failed cache write must not fail the read request.
            log.warn("Redis repopulation failed for item {}", itemId, e);
        }
    }

    private PriceResponse toResponse(ItemPrice itemPrice) {
        boolean available = Boolean.TRUE.equals(itemPrice.getPriceAvailable());
        return new PriceResponse(
                itemPrice.getItemId(),
                available ? itemPrice.getPriceUsd() : null,
                available,
                itemPrice.getProvider(),
                itemPrice.getFetchedAt()
        );
    }

    /**
     * SQL MISS (no price row exists yet for this item) — TECHNICAL_SPEC.md §6.2 step 4 locks
     * {@code priceAvailable=false, priceUsd=null, provider=null}. {@code fetchedAt} isn't
     * addressed by that step explicitly; since no fetch has ever happened for this item, {@code
     * null} is used here rather than a fabricated timestamp. Flagged as an interpretation note
     * in the completion report (analogous to Milestone 3's §22 flag), not escalated, since it
     * doesn't touch the locked field set/types and this is expected behavior for an
     * as-yet-unsynced item, not a bug — item_prices stays empty until PriceSyncJob (a later
     * milestone) populates it.
     */
    private PriceResponse unavailableResponse(Long itemId) {
        return new PriceResponse(itemId, null, false, null, null);
    }
}

package com.dropfolio.pricing.service;

import com.dropfolio.common.exception.ResourceNotFoundException;
import com.dropfolio.common.exception.ValidationException;
import com.dropfolio.item.repository.ItemRepository;
import com.dropfolio.pricing.dto.PriceHistoryPointResponse;
import com.dropfolio.pricing.dto.PriceHistoryResponse;
import com.dropfolio.pricing.entity.ItemPrice;
import com.dropfolio.pricing.repository.ItemPriceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Read path for {@code GET /prices/{itemId}/history}. Reads the existing {@code item_prices}
 * snapshots straight from SQL (no Redis, no provider call — Steam is only ever reached by
 * {@code PriceSyncService} through {@code MarketPriceProvider}).
 *
 * Only {@code price_available = true} rows become points; an unavailable snapshot is skipped,
 * never rendered as {@code 0}. Nothing is interpolated or invented: sparse data stays sparse.
 *
 * Kept separate from {@link PricingService} (whose constructor/tests are frozen by M4) and uses
 * the shared UTC {@link Clock} so range boundaries are deterministic in tests.
 */
@Service
public class PriceHistoryService {

    static final int DEFAULT_LIMIT = 500;
    static final int MAX_LIMIT = 2000;

    private final ItemPriceRepository itemPriceRepository;
    private final ItemRepository itemRepository;
    private final Clock clock;

    public PriceHistoryService(ItemPriceRepository itemPriceRepository,
                               ItemRepository itemRepository,
                               Clock clock) {
        this.itemPriceRepository = itemPriceRepository;
        this.itemRepository = itemRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PriceHistoryResponse getHistory(Long itemId, String rangeParam, Integer limitParam) {
        PriceHistoryRange range = PriceHistoryRange.fromParam(rangeParam);
        int limit = resolveLimit(limitParam);

        if (!itemRepository.existsById(itemId)) {
            throw new ResourceNotFoundException("Item not found");
        }

        List<ItemPrice> rows = range.unbounded()
                ? itemPriceRepository.findByItemIdAndPriceAvailableTrueOrderByFetchedAtAsc(itemId)
                : itemPriceRepository
                        .findByItemIdAndPriceAvailableTrueAndFetchedAtGreaterThanEqualOrderByFetchedAtAsc(
                                itemId, range.lowerBound(Instant.now(clock)));

        List<ItemPrice> usable = rows.stream()
                // Defensive: an "available" row without a price must never become a point.
                .filter(row -> row.getPriceUsd() != null)
                .toList();

        List<ItemPrice> selected = downsample(usable, limit);

        List<PriceHistoryPointResponse> points = selected.stream()
                .map(row -> new PriceHistoryPointResponse(row.getPriceUsd(), row.getFetchedAt()))
                .toList();
        String provider = selected.isEmpty() ? null : selected.get(selected.size() - 1).getProvider();

        return new PriceHistoryResponse(itemId, provider, range.param(), points);
    }

    private int resolveLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ValidationException("limit must be between 1 and " + MAX_LIMIT);
        }
        return limit;
    }

    /**
     * Keeps at most {@code limit} rows by picking evenly spaced existing rows (first and last are
     * always kept, so the newest point still equals the current price). Picks real snapshots only
     * — no averaging, no synthetic values.
     */
    private List<ItemPrice> downsample(List<ItemPrice> rows, int limit) {
        int n = rows.size();
        if (n <= limit) {
            return rows;
        }
        if (limit == 1) {
            return List.of(rows.get(n - 1));
        }
        List<ItemPrice> picked = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            long index = Math.round((double) i * (n - 1) / (limit - 1));
            picked.add(rows.get((int) index));
        }
        return picked;
    }
}

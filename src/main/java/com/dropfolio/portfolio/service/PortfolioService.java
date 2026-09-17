package com.dropfolio.portfolio.service;

import com.dropfolio.common.exception.ValidationException;
import com.dropfolio.drop.entity.Drop;
import com.dropfolio.drop.repository.DropRepository;
import com.dropfolio.item.entity.Item;
import com.dropfolio.item.entity.ItemType;
import com.dropfolio.item.repository.ItemRepository;
import com.dropfolio.pricing.dto.PriceResponse;
import com.dropfolio.pricing.service.PricingService;
import com.dropfolio.portfolio.dto.HighestValueItemRef;
import com.dropfolio.portfolio.dto.LatestDropRef;
import com.dropfolio.portfolio.dto.PortfolioBreakdownItemResponse;
import com.dropfolio.portfolio.dto.PortfolioSummaryResponse;
import com.dropfolio.portfolio.dto.WeeklyDropStats;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * TECHNICAL_SPEC.md §2: all business logic lives here, controller only binds/calls/wraps.
 *
 * No new persistence — {@code portfolio/} has no table of its own (ERD.md has none); everything
 * here is computed from {@code drops} + {@code items} + {@code item_prices} (via the existing
 * {@code drop/}/{@code item/}/{@code pricing/} modules, all read-only, reused exactly as-is).
 */
@Service
public class PortfolioService {

    private static final Set<String> BREAKDOWN_SORT_WHITELIST = Set.of("totalValueUsd", "quantity", "name");
    private static final int MAX_PAGE_SIZE = 100;

    /** PM Decision (Milestone 6): fixed weekly boundary, always UTC, never rolling/local-tz. */
    private static final DayOfWeek WEEK_BOUNDARY_DAY = DayOfWeek.WEDNESDAY;
    private static final int WEEK_BOUNDARY_HOUR_UTC = 1;

    private final DropRepository dropRepository;
    private final ItemRepository itemRepository;
    private final PricingService pricingService;
    private final Clock clock;

    public PortfolioService(DropRepository dropRepository, ItemRepository itemRepository,
                             PricingService pricingService, Clock clock) {
        this.dropRepository = dropRepository;
        this.itemRepository = itemRepository;
        this.pricingService = pricingService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PortfolioSummaryResponse getSummary(Long userId) {
        List<Drop> drops = dropRepository.findAllByUserIdAndDeletedAtIsNull(userId);
        if (drops.isEmpty()) {
            return new PortfolioSummaryResponse(BigDecimal.ZERO, 0, null, null,
                    new WeeklyDropStats(0, 0, BigDecimal.ZERO), 0);
        }

        Map<Long, Item> itemsById = loadItems(drops);
        Map<Long, PriceResponse> pricesById = loadPrices(itemsById.keySet());

        // PM Decision: totalItems = total physical quantity, not distinct-item or drop-row count.
        int totalItems = drops.stream().mapToInt(Drop::getQuantity).sum();

        Map<Long, Integer> quantityByItem = new LinkedHashMap<>();
        for (Drop d : drops) {
            quantityByItem.merge(d.getItemId(), d.getQuantity(), Integer::sum);
        }

        BigDecimal totalValueUsd = BigDecimal.ZERO;
        int itemsWithUnavailablePrice = 0;
        HighestValueItemRef highestValueItem = null;
        BigDecimal highestValue = null;

        for (Map.Entry<Long, Integer> entry : quantityByItem.entrySet()) {
            Long itemId = entry.getKey();
            int qty = entry.getValue();
            PriceResponse price = pricesById.get(itemId);
            if (price != null && price.priceAvailable()) {
                BigDecimal itemValue = price.priceUsd().multiply(BigDecimal.valueOf(qty));
                totalValueUsd = totalValueUsd.add(itemValue);

                boolean better = highestValue == null
                        || itemValue.compareTo(highestValue) > 0
                        // PM Decision: tie -> lowest itemId wins.
                        || (itemValue.compareTo(highestValue) == 0 && itemId < highestValueItem.itemId());
                if (better) {
                    highestValue = itemValue;
                    highestValueItem = new HighestValueItemRef(itemId, itemsById.get(itemId).getName(), itemValue);
                }
            } else {
                // PM Decision: itemsWithUnavailablePrice counts distinct items, not drop rows.
                itemsWithUnavailablePrice++;
            }
        }

        // PM Decision: latest by acquisitionDate, ties broken by latest createdAt.
        LatestDropRef latestDrop = drops.stream()
                .max(Comparator.comparing(Drop::getAcquisitionDate).thenComparing(Drop::getCreatedAt))
                .map(d -> new LatestDropRef(d.getItemId(), itemsById.get(d.getItemId()).getName(), d.getAcquisitionDate()))
                .orElse(null);

        WeeklyDropStats weeklyDrop = computeWeeklyDrop(drops, itemsById, pricesById, Instant.now(clock));

        return new PortfolioSummaryResponse(totalValueUsd, totalItems, highestValueItem, latestDrop,
                weeklyDrop, itemsWithUnavailablePrice);
    }

    @Transactional(readOnly = true)
    public Page<PortfolioBreakdownItemResponse> getBreakdown(Long userId, String search, ItemType type,
                                                               int page, int size, String sort) {
        if (page < 1) {
            throw new ValidationException("page must be >= 1");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ValidationException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        String[] parsedSort = parseSort(sort);
        String field = parsedSort[0];
        boolean descending = "desc".equals(parsedSort[1]);

        List<Drop> drops = dropRepository.findAllByUserIdAndDeletedAtIsNull(userId);
        Map<Long, Item> itemsById = loadItems(drops);

        Map<Long, Integer> quantityByItem = new LinkedHashMap<>();
        for (Drop d : drops) {
            quantityByItem.merge(d.getItemId(), d.getQuantity(), Integer::sum);
        }

        // Milestone 6 PM Decision: search/type only — no price-range filter (locked out of scope).
        List<Long> filteredItemIds = quantityByItem.keySet().stream()
                .filter(id -> matchesSearch(itemsById.get(id), search))
                .filter(id -> matchesType(itemsById.get(id), type))
                .collect(Collectors.toList());

        Map<Long, PriceResponse> pricesById = loadPrices(filteredItemIds);

        List<PortfolioBreakdownItemResponse> rows = filteredItemIds.stream()
                .map(id -> toBreakdownRow(itemsById.get(id), quantityByItem.get(id), pricesById.get(id)))
                .collect(Collectors.toList());

        rows.sort(breakdownComparator(field, descending));

        int fromIndex = Math.min((page - 1) * size, rows.size());
        int toIndex = Math.min(fromIndex + size, rows.size());
        List<PortfolioBreakdownItemResponse> pageContent = rows.subList(fromIndex, toIndex);

        return new PageImpl<>(pageContent, PageRequest.of(page - 1, size), rows.size());
    }

    /**
     * Returns the CSV body, or {@code Optional.empty()} when the user has no holdings at all
     * (controller maps that to {@code 204 No Content} — API_CONTRACT.md §7). One row per
     * {@code drops} entry (NOT aggregated per item like breakdown — the locked column list
     * includes a per-drop "Acquisition Price", which only makes sense at that granularity; see
     * MILESTONE_6_ASSESSMENT_REPORT.md §2). Sorted latest-first (acquisitionDate desc, then
     * createdAt desc — same tie-break as {@code latestDrop}, for consistency; not a locked
     * ordering, just a sensible default).
     */
    @Transactional(readOnly = true)
    public Optional<String> exportCsv(Long userId) {
        List<Drop> drops = dropRepository.findAllByUserIdAndDeletedAtIsNull(userId);
        if (drops.isEmpty()) {
            return Optional.empty();
        }

        Map<Long, Item> itemsById = loadItems(drops);
        Map<Long, PriceResponse> pricesById = loadPrices(itemsById.keySet());

        List<Drop> sorted = drops.stream()
                .sorted(Comparator.comparing(Drop::getAcquisitionDate).thenComparing(Drop::getCreatedAt).reversed())
                .collect(Collectors.toList());

        StringBuilder csv = new StringBuilder();
        // PRD.md §23 column order, verbatim.
        csv.append("Item,Type,Acquired Date,Quantity,Acquisition Price,Current Price,Current Value\r\n");
        for (Drop d : sorted) {
            Item item = itemsById.get(d.getItemId());
            PriceResponse price = pricesById.get(d.getItemId());
            boolean available = price != null && price.priceAvailable();

            // PM Decision: unavailable price -> literal "Price unavailable" in both cells.
            String currentPriceCell = available ? price.priceUsd().toPlainString() : "Price unavailable";
            String currentValueCell = available
                    ? price.priceUsd().multiply(BigDecimal.valueOf(d.getQuantity())).toPlainString()
                    : "Price unavailable";
            // Acquisition price is genuinely optional (never set at all), distinct from
            // "pricing subsystem couldn't fetch a price" — rendered as an empty cell, not
            // "Price unavailable" (that phrase is specifically about the pricing subsystem).
            String acquisitionPriceCell = d.getAcquisitionValueUsd() != null
                    ? d.getAcquisitionValueUsd().toPlainString() : "";

            csv.append(csvField(item.getName())).append(',')
                    .append(csvField(item.getType().name())).append(',')
                    .append(csvField(d.getAcquisitionDate().toString())).append(',')
                    .append(d.getQuantity()).append(',')
                    .append(csvField(acquisitionPriceCell)).append(',')
                    .append(csvField(currentPriceCell)).append(',')
                    .append(csvField(currentValueCell)).append("\r\n");
        }
        return Optional.of(csv.toString());
    }

    // ---- shared helpers ----

    private Map<Long, Item> loadItems(List<Drop> drops) {
        Set<Long> itemIds = drops.stream().map(Drop::getItemId).collect(Collectors.toSet());
        return itemRepository.findAllById(itemIds).stream().collect(Collectors.toMap(Item::getId, i -> i));
    }

    /** One {@link PricingService#getPrice(Long)} call per DISTINCT itemId — same reuse pattern as {@code drop/}. */
    private Map<Long, PriceResponse> loadPrices(Collection<Long> itemIds) {
        Map<Long, PriceResponse> result = new LinkedHashMap<>();
        for (Long itemId : itemIds) {
            result.put(itemId, pricingService.getPrice(itemId));
        }
        return result;
    }

    private boolean matchesSearch(Item item, String search) {
        return search == null || search.isBlank() || item.getName().toLowerCase().contains(search.toLowerCase());
    }

    private boolean matchesType(Item item, ItemType type) {
        return type == null || item.getType() == type;
    }

    private PortfolioBreakdownItemResponse toBreakdownRow(Item item, int totalQuantity, PriceResponse price) {
        boolean available = price != null && price.priceAvailable();
        BigDecimal currentPrice = available ? price.priceUsd() : null;
        BigDecimal totalValue = available ? currentPrice.multiply(BigDecimal.valueOf(totalQuantity)) : null;
        return new PortfolioBreakdownItemResponse(item.getId(), item.getName(), item.getType().name(),
                totalQuantity, currentPrice, available, totalValue);
    }

    private Comparator<PortfolioBreakdownItemResponse> breakdownComparator(String field, boolean descending) {
        return switch (field) {
            case "totalValueUsd" -> {
                Comparator<BigDecimal> valueOrder = descending ? Comparator.reverseOrder() : Comparator.naturalOrder();
                yield Comparator.comparing(PortfolioBreakdownItemResponse::totalValueUsd,
                        Comparator.nullsLast(valueOrder));
            }
            case "quantity" -> {
                Comparator<PortfolioBreakdownItemResponse> c =
                        Comparator.comparingInt(PortfolioBreakdownItemResponse::totalQuantity);
                yield descending ? c.reversed() : c;
            }
            default -> {
                Comparator<PortfolioBreakdownItemResponse> c =
                        Comparator.comparing(r -> r.name().toLowerCase());
                yield descending ? c.reversed() : c;
            }
        };
    }

    /**
     * PM Decision (Milestone 6): fixed weekly period, Wednesday 01:00 UTC to next Wednesday
     * 01:00 UTC, always UTC regardless of server timezone — NOT a rolling 7-day window.
     * {@code acquisitionDate} has no time component (ERD.md §2.9 — a calendar date, not a
     * timestamp), so the UTC instant boundaries are converted to plain calendar dates
     * ({@code [weekStartDate, weekEndDate)}, 7 calendar days, this Wednesday through next
     * Tuesday) and compared directly against {@code acquisitionDate} — the only way to apply a
     * time-of-day boundary to a field that doesn't carry time-of-day information.
     */
    private WeeklyDropStats computeWeeklyDrop(List<Drop> drops, Map<Long, Item> itemsById,
                                               Map<Long, PriceResponse> pricesById, Instant now) {
        LocalDate[] bounds = currentWeekBounds(now);
        LocalDate weekStart = bounds[0];
        LocalDate weekEndExclusive = bounds[1];

        int caseCount = 0;
        int skinOrGraffitiCount = 0;
        BigDecimal estimatedValue = BigDecimal.ZERO;

        for (Drop d : drops) {
            LocalDate date = d.getAcquisitionDate();
            if (date.isBefore(weekStart) || !date.isBefore(weekEndExclusive)) {
                continue;
            }
            Item item = itemsById.get(d.getItemId());
            int qty = d.getQuantity();
            // PM Decision: physical quantity, not drop-row count.
            if (item.getType() == ItemType.CASE) {
                caseCount += qty;
            } else {
                skinOrGraffitiCount += qty;
            }
            // PM Decision: current price only, never acquisitionValueUsd; skip if unavailable.
            PriceResponse price = pricesById.get(d.getItemId());
            if (price != null && price.priceAvailable()) {
                estimatedValue = estimatedValue.add(price.priceUsd().multiply(BigDecimal.valueOf(qty)));
            }
        }

        return new WeeklyDropStats(caseCount, skinOrGraffitiCount, estimatedValue);
    }

    private static LocalDate[] currentWeekBounds(Instant now) {
        ZonedDateTime nowUtc = now.atZone(ZoneOffset.UTC);
        ZonedDateTime candidate = nowUtc
                .with(TemporalAdjusters.previousOrSame(WEEK_BOUNDARY_DAY))
                .withHour(WEEK_BOUNDARY_HOUR_UTC).withMinute(0).withSecond(0).withNano(0);
        if (candidate.isAfter(nowUtc)) {
            candidate = candidate.minusWeeks(1);
        }
        LocalDate start = candidate.toLocalDate();
        LocalDate endExclusive = candidate.plusWeeks(1).toLocalDate();
        return new LocalDate[]{start, endExclusive};
    }

    /** Default {@code name,asc} when absent — same convention as {@code drop/}/{@code item/}. */
    private String[] parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return new String[]{"name", "asc"};
        }
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        if (!BREAKDOWN_SORT_WHITELIST.contains(field)) {
            throw new ValidationException(
                    "sort field not allowed: " + field + " (allowed: " + BREAKDOWN_SORT_WHITELIST + ")");
        }
        String direction = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim()) ? "desc" : "asc";
        return new String[]{field, direction};
    }

    private String csvField(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuoting = value.contains(",") || value.contains("\"")
                || value.contains("\n") || value.contains("\r");
        String escaped = value.replace("\"", "\"\"");
        return needsQuoting ? "\"" + escaped + "\"" : escaped;
    }
}

package com.dropfolio.drop.service;

import com.dropfolio.common.exception.OwnershipMismatchException;
import com.dropfolio.common.exception.ResourceNotFoundException;
import com.dropfolio.common.exception.ValidationException;
import com.dropfolio.drop.dto.CreateDropRequest;
import com.dropfolio.drop.dto.DropResponse;
import com.dropfolio.drop.dto.UpdateDropRequest;
import com.dropfolio.drop.entity.Drop;
import com.dropfolio.drop.entity.DropSource;
import com.dropfolio.drop.mapper.DropMapper;
import com.dropfolio.drop.repository.DropRepository;
import com.dropfolio.drop.repository.DropSpecifications;
import com.dropfolio.item.entity.Item;
import com.dropfolio.item.entity.ItemType;
import com.dropfolio.item.repository.ItemRepository;
import com.dropfolio.pricing.dto.PriceResponse;
import com.dropfolio.pricing.service.PricingService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** TECHNICAL_SPEC.md §2: all business logic lives here, controller only binds/calls/wraps. */
@Service
public class DropService {

    /** API_CONTRACT.md §5 GET /drops — the only three fields allowed in {@code sort}. */
    private static final Set<String> SORT_WHITELIST = Set.of("acquisitionDate", "currentValueUsd", "createdAt");
    private static final int MAX_PAGE_SIZE = 100;
    private static final String DEFAULT_SORT_FIELD = "acquisitionDate";

    private final DropRepository dropRepository;
    private final ItemRepository itemRepository;
    private final PricingService pricingService;

    public DropService(DropRepository dropRepository, ItemRepository itemRepository, PricingService pricingService) {
        this.dropRepository = dropRepository;
        this.itemRepository = itemRepository;
        this.pricingService = pricingService;
    }

    @Transactional(readOnly = true)
    public Page<DropResponse> list(Long userId, String search, ItemType type, DropSource source,
                                    LocalDate dateFrom, LocalDate dateTo,
                                    BigDecimal minValue, BigDecimal maxValue,
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

        Specification<Drop> spec = Specification
                .where(DropSpecifications.ownedBy(userId))
                .and(DropSpecifications.notDeleted())
                .and(DropSpecifications.search(search))
                .and(DropSpecifications.ofType(type))
                .and(DropSpecifications.ofSource(source))
                .and(DropSpecifications.acquiredFrom(dateFrom))
                .and(DropSpecifications.acquiredTo(dateTo))
                .and(DropSpecifications.minAcquisitionValue(minValue))
                .and(DropSpecifications.maxAcquisitionValue(maxValue));

        if ("currentValueUsd".equals(field)) {
            return listSortedByCurrentValue(spec, descending, page, size);
        }

        Sort springSort = Sort.by(descending ? Sort.Direction.DESC : Sort.Direction.ASC, field);
        Page<Drop> drops = dropRepository.findAll(spec, PageRequest.of(page - 1, size, springSort));
        List<DropResponse> content = toResponses(drops.getContent());
        return new PageImpl<>(content, drops.getPageable(), drops.getTotalElements());
    }

    /**
     * {@code currentValueUsd} isn't a stored column on {@code drops} — it's the live snapshot
     * from {@code pricing/}. Sorting by it can't be pushed to the database the way {@code
     * acquisitionDate}/{@code createdAt} can without joining against "latest price per item",
     * which was deliberately not built for the {@code minValue}/{@code maxValue} filter (PM
     * Decision) and isn't built here either, for the same reason: manual holdings are bounded
     * per user (not a public leaderboard over millions of rows), so for MVP this fetches the
     * full filtered-but-unpaginated result, resolves each row's current price through the same
     * {@link PricingService} used everywhere else (identical Redis cache-aside/circuit-breaker
     * behavior as every other read path — nothing pricing-specific is reimplemented here), sorts
     * in memory, then paginates the sorted list. Documented as a deliberate MVP trade-off in
     * MILESTONE_5_COMPLETION_REPORT.md, not a locked contract detail.
     *
     * Rows with no available price sort last regardless of direction — an unavailable price
     * isn't "zero", it's unknown, so it shouldn't be treated as lowest in ascending order or
     * highest in descending order either.
     */
    private Page<DropResponse> listSortedByCurrentValue(Specification<Drop> spec, boolean descending,
                                                          int page, int size) {
        List<Drop> all = dropRepository.findAll(spec);
        List<DropResponse> responses = toResponses(all);

        Comparator<BigDecimal> valueOrder = descending ? Comparator.reverseOrder() : Comparator.naturalOrder();
        Comparator<DropResponse> comparator = Comparator.comparing(
                DropResponse::currentValueUsd, Comparator.nullsLast(valueOrder));

        List<DropResponse> sorted = responses.stream().sorted(comparator).collect(Collectors.toList());

        int fromIndex = Math.min((page - 1) * size, sorted.size());
        int toIndex = Math.min(fromIndex + size, sorted.size());
        List<DropResponse> pageContent = sorted.subList(fromIndex, toIndex);

        return new PageImpl<>(pageContent, PageRequest.of(page - 1, size), sorted.size());
    }

    @Transactional(readOnly = true)
    public DropResponse getById(Long userId, Long id) {
        Drop drop = dropRepository.findByIdAndUserIdAndDeletedAtIsNull(id, userId)
                .orElseThrow(() -> new OwnershipMismatchException("Drop not found"));
        return toResponses(List.of(drop)).get(0);
    }

    @Transactional
    public DropResponse create(Long userId, CreateDropRequest request) {
        // API_CONTRACT.md §5: itemId not found OR isActive=false both map to the same 404 —
        // one code, not two (API_CONTRACT.md §0.6.2 forbids inventing a new domain code here).
        Item item = itemRepository.findById(request.itemId())
                .filter(i -> Boolean.TRUE.equals(i.getIsActive()))
                .orElseThrow(() -> new ResourceNotFoundException("Item not found or inactive"));

        Drop drop = Drop.builder()
                .userId(userId)
                .itemId(item.getId())
                .source(DropSource.MANUAL)
                .quantity(request.quantity())
                .acquisitionValueUsd(request.acquisitionValueUsd())
                .acquisitionDate(request.acquisitionDate())
                .createdAt(Instant.now())
                .build();

        Drop saved = dropRepository.save(drop);
        PriceResponse price = pricingService.getPrice(item.getId());
        return DropMapper.toResponse(saved, item, price);
    }

    @Transactional
    public DropResponse update(Long userId, Long id, UpdateDropRequest request) {
        Drop drop = dropRepository.findByIdAndUserIdAndDeletedAtIsNull(id, userId)
                .orElseThrow(() -> new OwnershipMismatchException("Drop not found"));

        if (request.quantity() != null) {
            drop.setQuantity(request.quantity());
        }
        if (request.acquisitionDate() != null) {
            drop.setAcquisitionDate(request.acquisitionDate());
        }
        if (request.acquisitionValueUsd() != null) {
            drop.setAcquisitionValueUsd(request.acquisitionValueUsd());
        }
        drop.setUpdatedAt(Instant.now());

        Drop saved = dropRepository.save(drop);
        return toResponses(List.of(saved)).get(0);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        Drop drop = dropRepository.findByIdAndUserIdAndDeletedAtIsNull(id, userId)
                .orElseThrow(() -> new OwnershipMismatchException("Drop not found"));
        drop.setDeletedAt(Instant.now());
        dropRepository.save(drop);
    }

    /**
     * Batch-resolves {@code Item} + current price for a list of drops — one
     * {@link PricingService#getPrice(Long)} call per DISTINCT {@code itemId}, not one per row
     * (multiple drops commonly reference the same item), reusing Milestone 4's cache-aside path
     * as-is rather than reimplementing anything pricing-specific here.
     */
    private List<DropResponse> toResponses(List<Drop> drops) {
        if (drops.isEmpty()) {
            return List.of();
        }
        Set<Long> itemIds = drops.stream().map(Drop::getItemId).collect(Collectors.toSet());
        Map<Long, Item> itemsById = itemRepository.findAllById(itemIds).stream()
                .collect(Collectors.toMap(Item::getId, i -> i));
        Map<Long, PriceResponse> pricesById = new LinkedHashMap<>();
        for (Long itemId : itemIds) {
            pricesById.put(itemId, pricingService.getPrice(itemId));
        }
        return drops.stream()
                .map(d -> DropMapper.toResponse(d, itemsById.get(d.getItemId()), pricesById.get(d.getItemId())))
                .collect(Collectors.toList());
    }

    /**
     * Returns {@code [field, direction]}. Absent {@code sort} defaults to {@code
     * acquisitionDate,asc} — same convention as {@code ItemService.parseSort}: default field
     * ascending when nothing is specified, explicit {@code desc} only when the caller asks for
     * it. Not a locked contract default (API_CONTRACT.md doesn't specify one), chosen purely for
     * consistency with the Item Catalog's established sort-parsing behavior.
     */
    private String[] parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return new String[]{DEFAULT_SORT_FIELD, "asc"};
        }
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        if (!SORT_WHITELIST.contains(field)) {
            throw new ValidationException("sort field not allowed: " + field + " (allowed: " + SORT_WHITELIST + ")");
        }
        String direction = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim()) ? "desc" : "asc";
        return new String[]{field, direction};
    }
}

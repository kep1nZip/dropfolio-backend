package com.dropfolio.portfolio.service;

import com.dropfolio.common.exception.ValidationException;
import com.dropfolio.drop.entity.Drop;
import com.dropfolio.drop.entity.DropSource;
import com.dropfolio.drop.repository.DropRepository;
import com.dropfolio.item.entity.Item;
import com.dropfolio.item.entity.ItemType;
import com.dropfolio.item.repository.ItemRepository;
import com.dropfolio.pricing.dto.PriceResponse;
import com.dropfolio.pricing.service.PricingService;
import com.dropfolio.portfolio.dto.PortfolioBreakdownItemResponse;
import com.dropfolio.portfolio.dto.PortfolioSummaryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for PortfolioService — TECHNICAL_SPEC.md §16.1. No ownership-mismatch tests here
 * (not applicable — see MILESTONE_6_ASSESSMENT_REPORT.md §5, portfolio has no per-resource ID).
 */
@ExtendWith(MockitoExtension.class)
class PortfolioServiceTest {

    private static final Long USER_ID = 1L;

    @Mock private DropRepository dropRepository;
    @Mock private ItemRepository itemRepository;
    @Mock private PricingService pricingService;

    private PortfolioService portfolioService;

    @BeforeEach
    void setUp() {
        portfolioService = new PortfolioService(dropRepository, itemRepository, pricingService, Clock.systemUTC());
    }

    // ---- getSummary() ----

    @Test
    void getSummary_emptyPortfolio_returnsZeroedResponse() {
        when(dropRepository.findAllByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(List.of());

        PortfolioSummaryResponse response = portfolioService.getSummary(USER_ID);

        assertThat(response.totalValueUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.totalItems()).isZero();
        assertThat(response.highestValueItem()).isNull();
        assertThat(response.latestDrop()).isNull();
        assertThat(response.itemsWithUnavailablePrice()).isZero();
        assertThat(response.weeklyDrop().caseCount()).isZero();
        assertThat(response.weeklyDrop().skinOrGraffitiCount()).isZero();
        assertThat(response.weeklyDrop().estimatedValueUsd()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void getSummary_totalItems_isSumOfQuantityNotDistinctItemsOrRows() {
        // PM Decision: totalItems = total physical quantity, not distinct item count / row count.
        Item caseItem = item(1L, "Revolution Case", ItemType.CASE);
        Item skinItem = item(2L, "AK-47 | Redline", ItemType.SKIN);
        Drop dropA = drop(10L, 1L, 3, LocalDate.of(2026, 1, 1));
        Drop dropB = drop(11L, 1L, 5, LocalDate.of(2026, 1, 2)); // same item as dropA
        Drop dropC = drop(12L, 2L, 2, LocalDate.of(2026, 1, 3));

        when(dropRepository.findAllByUserIdAndDeletedAtIsNull(USER_ID))
                .thenReturn(List.of(dropA, dropB, dropC));
        when(itemRepository.findAllById(any())).thenReturn(List.of(caseItem, skinItem));
        when(pricingService.getPrice(1L)).thenReturn(price(1L, "1.00", true));
        when(pricingService.getPrice(2L)).thenReturn(price(2L, "5.00", true));

        PortfolioSummaryResponse response = portfolioService.getSummary(USER_ID);

        // 3 + 5 + 2 = 10, NOT 2 (distinct items) and NOT 3 (drop rows).
        assertThat(response.totalItems()).isEqualTo(10);
    }

    @Test
    void getSummary_itemsWithUnavailablePrice_countsDistinctItemsNotDropRows() {
        // PM Decision: 3 drop rows referencing 1 unpriceable item -> count = 1, not 3.
        Item item = item(1L, "Unpriceable Skin", ItemType.SKIN);
        Drop dropA = drop(10L, 1L, 1, LocalDate.of(2026, 1, 1));
        Drop dropB = drop(11L, 1L, 1, LocalDate.of(2026, 1, 2));
        Drop dropC = drop(12L, 1L, 1, LocalDate.of(2026, 1, 3));

        when(dropRepository.findAllByUserIdAndDeletedAtIsNull(USER_ID))
                .thenReturn(List.of(dropA, dropB, dropC));
        when(itemRepository.findAllById(any())).thenReturn(List.of(item));
        when(pricingService.getPrice(1L)).thenReturn(new PriceResponse(1L, null, false, null, null));

        PortfolioSummaryResponse response = portfolioService.getSummary(USER_ID);

        assertThat(response.itemsWithUnavailablePrice()).isEqualTo(1);
        assertThat(response.totalValueUsd()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void getSummary_totalValueUsd_excludesUnavailablePriceItems() {
        Item available = item(1L, "Available Item", ItemType.CASE);
        Item unavailable = item(2L, "Unavailable Item", ItemType.SKIN);
        Drop dropA = drop(10L, 1L, 2, LocalDate.of(2026, 1, 1));
        Drop dropB = drop(11L, 2L, 100, LocalDate.of(2026, 1, 1));

        when(dropRepository.findAllByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(List.of(dropA, dropB));
        when(itemRepository.findAllById(any())).thenReturn(List.of(available, unavailable));
        when(pricingService.getPrice(1L)).thenReturn(price(1L, "2.00", true));
        when(pricingService.getPrice(2L)).thenReturn(new PriceResponse(2L, null, false, null, null));

        PortfolioSummaryResponse response = portfolioService.getSummary(USER_ID);

        // Only item 1 contributes: 2 * 2.00 = 4.00. Item 2 (unavailable) contributes nothing,
        // regardless of its huge quantity.
        assertThat(response.totalValueUsd()).isEqualByComparingTo("4.00");
    }

    @Test
    void getSummary_highestValueItem_picksMaxAggregatedValue_tieBreaksOnLowestItemId() {
        Item itemA = item(5L, "Item A", ItemType.CASE);
        Item itemB = item(3L, "Item B", ItemType.SKIN);
        // Both aggregate to the exact same value: 10.00
        Drop dropA = drop(10L, 5L, 10, LocalDate.of(2026, 1, 1));
        Drop dropB = drop(11L, 3L, 5, LocalDate.of(2026, 1, 1));

        when(dropRepository.findAllByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(List.of(dropA, dropB));
        when(itemRepository.findAllById(any())).thenReturn(List.of(itemA, itemB));
        when(pricingService.getPrice(5L)).thenReturn(price(5L, "1.00", true)); // 10 * 1.00 = 10.00
        when(pricingService.getPrice(3L)).thenReturn(price(3L, "2.00", true)); // 5 * 2.00 = 10.00

        PortfolioSummaryResponse response = portfolioService.getSummary(USER_ID);

        // Tie on value -> lowest itemId (3) wins, not item 5.
        assertThat(response.highestValueItem().itemId()).isEqualTo(3L);
        assertThat(response.highestValueItem().valueUsd()).isEqualByComparingTo("10.00");
    }

    @Test
    void getSummary_latestDrop_picksLatestAcquisitionDate_tieBreaksOnLatestCreatedAt() {
        Item item = item(5L, "Some Item", ItemType.CASE);
        Instant earlierCreated = Instant.parse("2026-01-01T00:00:00Z");
        Instant laterCreated = Instant.parse("2026-01-02T00:00:00Z");
        // Same acquisitionDate, different createdAt -> latest createdAt wins.
        Drop older = dropWithCreatedAt(10L, 5L, 1, LocalDate.of(2026, 8, 30), earlierCreated);
        Drop newer = dropWithCreatedAt(11L, 5L, 1, LocalDate.of(2026, 8, 30), laterCreated);

        when(dropRepository.findAllByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(List.of(older, newer));
        when(itemRepository.findAllById(any())).thenReturn(List.of(item));
        when(pricingService.getPrice(5L)).thenReturn(price(5L, "1.00", true));

        PortfolioSummaryResponse response = portfolioService.getSummary(USER_ID);

        assertThat(response.latestDrop().acquiredAt()).isEqualTo(LocalDate.of(2026, 8, 30));
    }

    @Test
    void getSummary_weeklyDrop_onlyIncludesDropsWithinCurrentUtcWeek() {
        Item caseItem = item(1L, "Weekly Case", ItemType.CASE);
        Item skinItem = item(2L, "Weekly Skin", ItemType.SKIN);

        // Anchor "now" to a known instant: Thursday 2026-09-10 12:00 UTC.
        // Current week per PM rule: Wed 2026-09-09 01:00 UTC -> Wed 2026-09-16 01:00 UTC,
        // i.e. calendar dates [2026-09-09, 2026-09-16).
        Instant fixedNow = Instant.parse("2026-09-10T12:00:00Z");
        PortfolioService service = new PortfolioService(dropRepository, itemRepository, pricingService,
                Clock.fixed(fixedNow, ZoneOffset.UTC));

        Drop inWindow = drop(10L, 1L, 3, LocalDate.of(2026, 9, 10)); // inside window
        Drop onBoundaryStart = drop(11L, 2L, 2, LocalDate.of(2026, 9, 9)); // exactly weekStart
        Drop beforeWindow = drop(12L, 1L, 99, LocalDate.of(2026, 9, 8)); // one day too early
        Drop onBoundaryEnd = drop(13L, 2L, 99, LocalDate.of(2026, 9, 16)); // exclusive end, excluded

        when(dropRepository.findAllByUserIdAndDeletedAtIsNull(USER_ID))
                .thenReturn(List.of(inWindow, onBoundaryStart, beforeWindow, onBoundaryEnd));
        when(itemRepository.findAllById(any())).thenReturn(List.of(caseItem, skinItem));
        when(pricingService.getPrice(1L)).thenReturn(price(1L, "1.00", true));
        when(pricingService.getPrice(2L)).thenReturn(price(2L, "2.00", true));

        PortfolioSummaryResponse response = service.getSummary(USER_ID);

        // Only inWindow (case, qty 3) and onBoundaryStart (skin, qty 2) count.
        assertThat(response.weeklyDrop().caseCount()).isEqualTo(3);
        assertThat(response.weeklyDrop().skinOrGraffitiCount()).isEqualTo(2);
        // 3 * 1.00 + 2 * 2.00 = 7.00
        assertThat(response.weeklyDrop().estimatedValueUsd()).isEqualByComparingTo("7.00");
    }

    @Test
    void getSummary_weeklyDrop_boundaryRespectsUtcHourNotJustDate() {
        // Wednesday 2026-09-09 at 00:30 UTC is BEFORE that Wednesday's 01:00 UTC boundary, so
        // "now" still belongs to the PREVIOUS week (started Wed 2026-09-02 01:00 UTC), even
        // though the calendar date is already "Wednesday". This is the specific case that
        // distinguishes "fixed 01:00 UTC boundary" from a naive "any Wednesday = new week".
        Item item = item(1L, "Edge Case Item", ItemType.CASE);
        Instant fixedNow = Instant.parse("2026-09-09T00:30:00Z");
        PortfolioService service = new PortfolioService(dropRepository, itemRepository, pricingService,
                Clock.fixed(fixedNow, ZoneOffset.UTC));

        // 2026-09-09 hasn't "started" its own week yet (needs 01:00 UTC) -> still previous
        // week's range [2026-09-02, 2026-09-09), so this date is EXCLUDED even though it looks
        // like it should be the boundary start.
        Drop stillPreviousWeek = drop(10L, 1L, 7, LocalDate.of(2026, 9, 9));

        when(dropRepository.findAllByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(List.of(stillPreviousWeek));
        when(itemRepository.findAllById(any())).thenReturn(List.of(item));
        when(pricingService.getPrice(1L)).thenReturn(price(1L, "1.00", true));

        PortfolioSummaryResponse response = service.getSummary(USER_ID);

        assertThat(response.weeklyDrop().caseCount()).isZero();
    }

    @Test
    void getSummary_weeklyDrop_countsPhysicalQuantityNotDropRows() {
        // PM Decision: one CASE drop with quantity=5 -> caseCount += 5, not += 1.
        Item caseItem = item(1L, "Case", ItemType.CASE);
        Instant fixedNow = Instant.parse("2026-09-10T12:00:00Z");
        PortfolioService service = new PortfolioService(dropRepository, itemRepository, pricingService,
                Clock.fixed(fixedNow, ZoneOffset.UTC));
        Drop bigDrop = drop(10L, 1L, 5, LocalDate.of(2026, 9, 10));

        when(dropRepository.findAllByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(List.of(bigDrop));
        when(itemRepository.findAllById(any())).thenReturn(List.of(caseItem));
        when(pricingService.getPrice(1L)).thenReturn(price(1L, "1.00", true));

        PortfolioSummaryResponse response = service.getSummary(USER_ID);

        assertThat(response.weeklyDrop().caseCount()).isEqualTo(5);
    }

    @Test
    void getSummary_weeklyDrop_usesCurrentPriceNotAcquisitionValue_excludesUnavailable() {
        Item caseItem = item(1L, "Case", ItemType.CASE);
        Instant fixedNow = Instant.parse("2026-09-10T12:00:00Z");
        PortfolioService service = new PortfolioService(dropRepository, itemRepository, pricingService,
                Clock.fixed(fixedNow, ZoneOffset.UTC));
        Drop withAcquisitionValue = dropWithAcquisitionValue(
                10L, 1L, 2, LocalDate.of(2026, 9, 10), new BigDecimal("999.00"));

        when(dropRepository.findAllByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(List.of(withAcquisitionValue));
        when(itemRepository.findAllById(any())).thenReturn(List.of(caseItem));
        when(pricingService.getPrice(1L)).thenReturn(price(1L, "0.50", true));

        PortfolioSummaryResponse response = service.getSummary(USER_ID);

        // 2 * 0.50 = 1.00, NOT based on the 999.00 acquisition value.
        assertThat(response.weeklyDrop().estimatedValueUsd()).isEqualByComparingTo("1.00");
    }

    // ---- getBreakdown() ----

    @Test
    void getBreakdown_invalidPage_throwsValidation() {
        assertThatThrownBy(() -> portfolioService.getBreakdown(USER_ID, null, null, 0, 20, null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void getBreakdown_sizeTooLarge_throwsValidation() {
        assertThatThrownBy(() -> portfolioService.getBreakdown(USER_ID, null, null, 1, 101, null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void getBreakdown_invalidSort_throwsValidation() {
        assertThatThrownBy(() -> portfolioService.getBreakdown(USER_ID, null, null, 1, 20, "acquisitionDate,asc"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void getBreakdown_aggregatesMultipleDropsForSameItem() {
        Item item = item(1L, "Revolution Case", ItemType.CASE);
        Drop dropA = drop(10L, 1L, 3, LocalDate.of(2026, 1, 1));
        Drop dropB = drop(11L, 1L, 7, LocalDate.of(2026, 1, 2));

        when(dropRepository.findAllByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(List.of(dropA, dropB));
        when(itemRepository.findAllById(any())).thenReturn(List.of(item));
        when(pricingService.getPrice(1L)).thenReturn(price(1L, "0.50", true));

        Page<PortfolioBreakdownItemResponse> result =
                portfolioService.getBreakdown(USER_ID, null, null, 1, 20, null);

        assertThat(result.getContent()).hasSize(1);
        PortfolioBreakdownItemResponse row = result.getContent().get(0);
        assertThat(row.totalQuantity()).isEqualTo(10);
        assertThat(row.totalValueUsd()).isEqualByComparingTo("5.00");
    }

    @Test
    void getBreakdown_priceUnavailable_totalValueUsdIsNull() {
        Item item = item(1L, "Unpriced Skin", ItemType.SKIN);
        Drop dropA = drop(10L, 1L, 3, LocalDate.of(2026, 1, 1));

        when(dropRepository.findAllByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(List.of(dropA));
        when(itemRepository.findAllById(any())).thenReturn(List.of(item));
        when(pricingService.getPrice(1L)).thenReturn(new PriceResponse(1L, null, false, null, null));

        Page<PortfolioBreakdownItemResponse> result =
                portfolioService.getBreakdown(USER_ID, null, null, 1, 20, null);

        assertThat(result.getContent().get(0).totalValueUsd()).isNull();
        assertThat(result.getContent().get(0).priceAvailable()).isFalse();
    }

    @Test
    void getBreakdown_searchFilter_matchesItemNameCaseInsensitive() {
        Item caseItem = item(1L, "Revolution Case", ItemType.CASE);
        Item skinItem = item(2L, "AK-47 | Redline", ItemType.SKIN);
        Drop dropA = drop(10L, 1L, 1, LocalDate.of(2026, 1, 1));
        Drop dropB = drop(11L, 2L, 1, LocalDate.of(2026, 1, 1));

        when(dropRepository.findAllByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(List.of(dropA, dropB));
        when(itemRepository.findAllById(any())).thenReturn(List.of(caseItem, skinItem));
        when(pricingService.getPrice(1L)).thenReturn(price(1L, "1.00", true));

        Page<PortfolioBreakdownItemResponse> result =
                portfolioService.getBreakdown(USER_ID, "revolution", null, 1, 20, null);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).name()).isEqualTo("Revolution Case");
    }

    @Test
    void getBreakdown_typeFilter_matchesOnly() {
        Item caseItem = item(1L, "Revolution Case", ItemType.CASE);
        Item skinItem = item(2L, "AK-47 | Redline", ItemType.SKIN);
        Drop dropA = drop(10L, 1L, 1, LocalDate.of(2026, 1, 1));
        Drop dropB = drop(11L, 2L, 1, LocalDate.of(2026, 1, 1));

        when(dropRepository.findAllByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(List.of(dropA, dropB));
        when(itemRepository.findAllById(any())).thenReturn(List.of(caseItem, skinItem));
        when(pricingService.getPrice(2L)).thenReturn(price(2L, "1.00", true));

        Page<PortfolioBreakdownItemResponse> result =
                portfolioService.getBreakdown(USER_ID, null, ItemType.SKIN, 1, 20, null);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).type()).isEqualTo("SKIN");
    }

    @Test
    void getBreakdown_sortByTotalValueUsdDesc() {
        Item cheap = item(1L, "Cheap", ItemType.CASE);
        Item pricy = item(2L, "Pricy", ItemType.SKIN);
        Drop dropCheap = drop(10L, 1L, 1, LocalDate.of(2026, 1, 1));
        Drop dropPricy = drop(11L, 2L, 1, LocalDate.of(2026, 1, 1));

        when(dropRepository.findAllByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(List.of(dropCheap, dropPricy));
        when(itemRepository.findAllById(any())).thenReturn(List.of(cheap, pricy));
        when(pricingService.getPrice(1L)).thenReturn(price(1L, "1.00", true));
        when(pricingService.getPrice(2L)).thenReturn(price(2L, "50.00", true));

        Page<PortfolioBreakdownItemResponse> result =
                portfolioService.getBreakdown(USER_ID, null, null, 1, 20, "totalValueUsd,desc");

        assertThat(result.getContent().get(0).name()).isEqualTo("Pricy");
        assertThat(result.getContent().get(1).name()).isEqualTo("Cheap");
    }

    // ---- exportCsv() ----

    @Test
    void exportCsv_noDrops_returnsEmptyOptional() {
        when(dropRepository.findAllByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(List.of());

        Optional<String> csv = portfolioService.exportCsv(USER_ID);

        assertThat(csv).isEmpty();
    }

    @Test
    void exportCsv_priceAvailable_rendersValuesAndHeader() {
        Item item = item(1L, "Revolution Case", ItemType.CASE);
        Drop d = dropWithAcquisitionValue(10L, 1L, 3, LocalDate.of(2026, 8, 25), new BigDecimal("0.48"));

        when(dropRepository.findAllByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(List.of(d));
        when(itemRepository.findAllById(any())).thenReturn(List.of(item));
        when(pricingService.getPrice(1L)).thenReturn(price(1L, "0.52", true));

        String csv = portfolioService.exportCsv(USER_ID).orElseThrow();

        assertThat(csv).startsWith("Item,Type,Acquired Date,Quantity,Acquisition Price,Current Price,Current Value\r\n");
        assertThat(csv).contains("Revolution Case,CASE,2026-08-25,3,0.48,0.52,1.56");
    }

    @Test
    void exportCsv_priceUnavailable_rendersPriceUnavailableLiteral() {
        Item item = item(1L, "Unpriced Skin", ItemType.SKIN);
        Drop d = drop(10L, 1L, 1, LocalDate.of(2026, 8, 25));

        when(dropRepository.findAllByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(List.of(d));
        when(itemRepository.findAllById(any())).thenReturn(List.of(item));
        when(pricingService.getPrice(1L)).thenReturn(new PriceResponse(1L, null, false, null, null));

        String csv = portfolioService.exportCsv(USER_ID).orElseThrow();

        assertThat(csv).contains("Price unavailable,Price unavailable");
    }

    @Test
    void exportCsv_itemNameWithComma_isQuoted() {
        Item item = item(1L, "AK-47 | Redline, Field-Tested", ItemType.SKIN);
        Drop d = drop(10L, 1L, 1, LocalDate.of(2026, 8, 25));

        when(dropRepository.findAllByUserIdAndDeletedAtIsNull(USER_ID)).thenReturn(List.of(d));
        when(itemRepository.findAllById(any())).thenReturn(List.of(item));
        when(pricingService.getPrice(1L)).thenReturn(price(1L, "1.00", true));

        String csv = portfolioService.exportCsv(USER_ID).orElseThrow();

        assertThat(csv).contains("\"AK-47 | Redline, Field-Tested\"");
    }

    // ---- helpers ----

    private static Item item(Long id, String name, ItemType type) {
        return Item.builder()
                .id(id).name(name).type(type)
                .marketHashName(name + " (Field-Tested)")
                .isActive(true)
                .createdAt(Instant.now())
                .build();
    }

    private static Drop drop(Long id, Long itemId, int quantity, LocalDate acquisitionDate) {
        return Drop.builder()
                .id(id).userId(USER_ID).itemId(itemId)
                .source(DropSource.MANUAL)
                .quantity(quantity)
                .acquisitionDate(acquisitionDate)
                .createdAt(Instant.now())
                .build();
    }

    private static Drop dropWithCreatedAt(Long id, Long itemId, int quantity, LocalDate acquisitionDate, Instant createdAt) {
        return Drop.builder()
                .id(id).userId(USER_ID).itemId(itemId)
                .source(DropSource.MANUAL)
                .quantity(quantity)
                .acquisitionDate(acquisitionDate)
                .createdAt(createdAt)
                .build();
    }

    private static Drop dropWithAcquisitionValue(Long id, Long itemId, int quantity, LocalDate acquisitionDate,
                                                  BigDecimal acquisitionValueUsd) {
        return Drop.builder()
                .id(id).userId(USER_ID).itemId(itemId)
                .source(DropSource.MANUAL)
                .quantity(quantity)
                .acquisitionValueUsd(acquisitionValueUsd)
                .acquisitionDate(acquisitionDate)
                .createdAt(Instant.now())
                .build();
    }

    private static PriceResponse price(Long itemId, String priceUsd, boolean available) {
        return new PriceResponse(itemId, available ? new BigDecimal(priceUsd) : null, available,
                available ? "STEAM_MARKET" : null, available ? Instant.now() : null);
    }
}

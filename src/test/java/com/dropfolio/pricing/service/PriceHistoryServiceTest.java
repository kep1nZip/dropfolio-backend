package com.dropfolio.pricing.service;

import com.dropfolio.common.exception.ResourceNotFoundException;
import com.dropfolio.common.exception.ValidationException;
import com.dropfolio.item.repository.ItemRepository;
import com.dropfolio.pricing.dto.PriceHistoryResponse;
import com.dropfolio.pricing.entity.ItemPrice;
import com.dropfolio.pricing.repository.ItemPriceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit tests for {@link PriceHistoryService}: repositories mocked, {@link Clock} fixed (UTC). */
@ExtendWith(MockitoExtension.class)
class PriceHistoryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    private static final long ITEM_ID = 4L;

    @Mock private ItemPriceRepository itemPriceRepository;
    @Mock private ItemRepository itemRepository;

    private PriceHistoryService service;

    @BeforeEach
    void setUp() {
        service = new PriceHistoryService(itemPriceRepository, itemRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ItemPrice row(String price, Instant fetchedAt) {
        return ItemPrice.builder()
                .itemId(ITEM_ID)
                .provider("STEAM_MARKET")
                .priceUsd(price == null ? null : new BigDecimal(price))
                .priceAvailable(true)
                .fetchedAt(fetchedAt)
                .createdAt(fetchedAt)
                .build();
    }

    @Test
    void getHistory_withSnapshots_returnsPointsAscendingWithProvider() {
        when(itemRepository.existsById(ITEM_ID)).thenReturn(true);
        Instant lowerBound = NOW.minus(30, ChronoUnit.DAYS);
        when(itemPriceRepository
                .findByItemIdAndPriceAvailableTrueAndFetchedAtGreaterThanEqualOrderByFetchedAtAsc(ITEM_ID, lowerBound))
                .thenReturn(List.of(
                        row("34.1200", Instant.parse("2026-09-01T10:00:00Z")),
                        row("35.2000", Instant.parse("2026-09-02T10:00:00Z"))));

        PriceHistoryResponse result = service.getHistory(ITEM_ID, "30d", null);

        assertThat(result.itemId()).isEqualTo(ITEM_ID);
        assertThat(result.range()).isEqualTo("30d");
        assertThat(result.provider()).isEqualTo("STEAM_MARKET");
        assertThat(result.points()).hasSize(2);
        assertThat(result.points().get(0).priceUsd()).isEqualByComparingTo("34.12");
        assertThat(result.points().get(1).fetchedAt()).isEqualTo(Instant.parse("2026-09-02T10:00:00Z"));
    }

    @Test
    void getHistory_noSnapshots_returnsEmptyPointsAndNullProvider() {
        when(itemRepository.existsById(ITEM_ID)).thenReturn(true);
        when(itemPriceRepository
                .findByItemIdAndPriceAvailableTrueAndFetchedAtGreaterThanEqualOrderByFetchedAtAsc(any(), any()))
                .thenReturn(List.of());

        PriceHistoryResponse result = service.getHistory(ITEM_ID, "7d", null);

        assertThat(result.points()).isEmpty();
        assertThat(result.provider()).isNull();
    }

    @Test
    void getHistory_availableRowWithNullPrice_isNeverEmittedAsAPoint() {
        when(itemRepository.existsById(ITEM_ID)).thenReturn(true);
        when(itemPriceRepository
                .findByItemIdAndPriceAvailableTrueAndFetchedAtGreaterThanEqualOrderByFetchedAtAsc(any(), any()))
                .thenReturn(List.of(
                        row(null, Instant.parse("2026-09-01T10:00:00Z")),
                        row("12.5000", Instant.parse("2026-09-02T10:00:00Z"))));

        PriceHistoryResponse result = service.getHistory(ITEM_ID, "30d", null);

        assertThat(result.points()).hasSize(1);
        assertThat(result.points().get(0).priceUsd()).isEqualByComparingTo("12.5");
    }

    @ParameterizedTest
    @CsvSource({"7d,7", "30d,30", "90d,90", "1y,365"})
    void getHistory_boundedRange_usesUtcNowMinusWindowAsInclusiveLowerBound(String range, long days) {
        when(itemRepository.existsById(ITEM_ID)).thenReturn(true);
        Instant expected = NOW.minus(days, ChronoUnit.DAYS);
        when(itemPriceRepository
                .findByItemIdAndPriceAvailableTrueAndFetchedAtGreaterThanEqualOrderByFetchedAtAsc(ITEM_ID, expected))
                .thenReturn(List.of());

        PriceHistoryResponse result = service.getHistory(ITEM_ID, range, null);

        assertThat(result.range()).isEqualTo(range);
        verify(itemPriceRepository)
                .findByItemIdAndPriceAvailableTrueAndFetchedAtGreaterThanEqualOrderByFetchedAtAsc(ITEM_ID, expected);
    }

    @Test
    void getHistory_all_hasNoLowerBound() {
        when(itemRepository.existsById(ITEM_ID)).thenReturn(true);
        when(itemPriceRepository.findByItemIdAndPriceAvailableTrueOrderByFetchedAtAsc(ITEM_ID))
                .thenReturn(List.of(row("1.0000", Instant.parse("2026-01-01T00:00:00Z"))));

        PriceHistoryResponse result = service.getHistory(ITEM_ID, "all", null);

        assertThat(result.range()).isEqualTo("all");
        assertThat(result.points()).hasSize(1);
        verify(itemPriceRepository, never())
                .findByItemIdAndPriceAvailableTrueAndFetchedAtGreaterThanEqualOrderByFetchedAtAsc(any(), any());
    }

    @Test
    void getHistory_rangeIsCaseInsensitive_andEchoedCanonical() {
        when(itemRepository.existsById(ITEM_ID)).thenReturn(true);
        when(itemPriceRepository.findByItemIdAndPriceAvailableTrueOrderByFetchedAtAsc(ITEM_ID))
                .thenReturn(List.of());

        assertThat(service.getHistory(ITEM_ID, "ALL", null).range()).isEqualTo("all");
    }

    @Test
    void getHistory_itemNotInCatalog_throwsResourceNotFound_andNeverQueriesPrices() {
        when(itemRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.getHistory(99L, "30d", null))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(itemPriceRepository);
    }

    @ParameterizedTest
    @CsvSource({"1w", "365d", "0d", "''", "forever"})
    void getHistory_invalidRange_throwsValidationException(String range) {
        assertThatThrownBy(() -> service.getHistory(ITEM_ID, range, null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("range must be one of");

        verifyNoInteractions(itemPriceRepository);
    }

    @Test
    void getHistory_limitOutOfBounds_throwsValidationException() {
        assertThatThrownBy(() -> service.getHistory(ITEM_ID, "30d", 0))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.getHistory(ITEM_ID, "30d", PriceHistoryService.MAX_LIMIT + 1))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void getHistory_moreRowsThanLimit_downsamplesToRealRowsKeepingFirstAndLast() {
        when(itemRepository.existsById(ITEM_ID)).thenReturn(true);
        List<ItemPrice> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rows.add(row(String.valueOf(10 + i) + ".0000", Instant.parse("2026-09-01T00:00:00Z").plus(i, ChronoUnit.HOURS)));
        }
        when(itemPriceRepository
                .findByItemIdAndPriceAvailableTrueAndFetchedAtGreaterThanEqualOrderByFetchedAtAsc(any(), any()))
                .thenReturn(rows);

        PriceHistoryResponse result = service.getHistory(ITEM_ID, "30d", 4);

        assertThat(result.points()).hasSize(4);
        assertThat(result.points().get(0).priceUsd()).isEqualByComparingTo("10");
        assertThat(result.points().get(3).priceUsd()).isEqualByComparingTo("19");
        assertThat(result.points()).extracting(p -> p.fetchedAt())
                .isSorted();
    }
}

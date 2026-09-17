package com.dropfolio.drop.service;

import com.dropfolio.common.exception.OwnershipMismatchException;
import com.dropfolio.common.exception.ResourceNotFoundException;
import com.dropfolio.common.exception.ValidationException;
import com.dropfolio.drop.dto.CreateDropRequest;
import com.dropfolio.drop.dto.DropResponse;
import com.dropfolio.drop.dto.UpdateDropRequest;
import com.dropfolio.drop.entity.Drop;
import com.dropfolio.drop.entity.DropSource;
import com.dropfolio.drop.repository.DropRepository;
import com.dropfolio.item.entity.Item;
import com.dropfolio.item.entity.ItemType;
import com.dropfolio.item.repository.ItemRepository;
import com.dropfolio.pricing.dto.PriceResponse;
import com.dropfolio.pricing.service.PricingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for DropService — TECHNICAL_SPEC.md §16.1 (service layer, mocked repositories).
 */
@ExtendWith(MockitoExtension.class)
class DropServiceTest {

    private static final Long USER_ID = 1L;

    @Mock private DropRepository dropRepository;
    @Mock private ItemRepository itemRepository;
    @Mock private PricingService pricingService;

    private DropService dropService;

    @BeforeEach
    void setUp() {
        dropService = new DropService(dropRepository, itemRepository, pricingService);
    }

    // ---- list() validation ----

    @Test
    void list_invalidPage_throwsValidation() {
        assertThatThrownBy(() -> dropService.list(USER_ID, null, null, null, null, null, null, null, 0, 20, null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void list_sizeTooLarge_throwsValidation() {
        assertThatThrownBy(() -> dropService.list(USER_ID, null, null, null, null, null, null, null, 1, 101, null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void list_sortFieldNotWhitelisted_throwsValidation() {
        assertThatThrownBy(() -> dropService.list(
                USER_ID, null, null, null, null, null, null, null, 1, 20, "quantity,asc"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_dbSortField_delegatesPaginationToRepository() {
        Item item = item(5L, "Revolution Case", ItemType.CASE);
        Drop drop = drop(101L, 5L, new BigDecimal("0.50"), LocalDate.of(2026, 8, 25));

        when(dropRepository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(drop), PageRequest.of(0, 20), 1));
        when(itemRepository.findAllById(any())).thenReturn(List.of(item));
        when(pricingService.getPrice(5L)).thenReturn(
                new PriceResponse(5L, new BigDecimal("0.52"), true, "STEAM_MARKET", Instant.now()));

        Page<DropResponse> result = dropService.list(
                USER_ID, null, null, null, null, null, null, null, 1, 20, "acquisitionDate,asc");

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).currentValueUsd()).isEqualByComparingTo("0.52");
        assertThat(result.getContent().get(0).item().name()).isEqualTo("Revolution Case");
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_sortByCurrentValueUsd_sortsInMemoryAndPaginates() {
        Item cheapItem = item(1L, "Cheap Case", ItemType.CASE);
        Item pricyItem = item(2L, "Pricy Skin", ItemType.SKIN);
        Drop cheapDrop = drop(10L, 1L, null, LocalDate.of(2026, 1, 1));
        Drop pricyDrop = drop(11L, 2L, null, LocalDate.of(2026, 1, 2));

        when(dropRepository.findAll(any(Specification.class)))
                .thenReturn(List.of(cheapDrop, pricyDrop));
        when(itemRepository.findAllById(any())).thenReturn(List.of(cheapItem, pricyItem));
        when(pricingService.getPrice(1L)).thenReturn(
                new PriceResponse(1L, new BigDecimal("1.00"), true, "STEAM_MARKET", Instant.now()));
        when(pricingService.getPrice(2L)).thenReturn(
                new PriceResponse(2L, new BigDecimal("50.00"), true, "STEAM_MARKET", Instant.now()));

        Page<DropResponse> result = dropService.list(
                USER_ID, null, null, null, null, null, null, null, 1, 20, "currentValueUsd,desc");

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent().get(0).item().name()).isEqualTo("Pricy Skin");
        assertThat(result.getContent().get(1).item().name()).isEqualTo("Cheap Case");
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_sortByCurrentValueUsd_priceUnavailableRowsSortLast() {
        Item available = item(1L, "Available Item", ItemType.SKIN);
        Item unavailable = item(2L, "Unavailable Item", ItemType.SKIN);
        Drop availableDrop = drop(10L, 1L, null, LocalDate.of(2026, 1, 1));
        Drop unavailableDrop = drop(11L, 2L, null, LocalDate.of(2026, 1, 2));

        when(dropRepository.findAll(any(Specification.class)))
                .thenReturn(List.of(unavailableDrop, availableDrop));
        when(itemRepository.findAllById(any())).thenReturn(List.of(available, unavailable));
        when(pricingService.getPrice(1L)).thenReturn(
                new PriceResponse(1L, new BigDecimal("5.00"), true, "STEAM_MARKET", Instant.now()));
        when(pricingService.getPrice(2L)).thenReturn(
                new PriceResponse(2L, null, false, null, null));

        Page<DropResponse> resultAsc = dropService.list(
                USER_ID, null, null, null, null, null, null, null, 1, 20, "currentValueUsd,asc");
        Page<DropResponse> resultDesc = dropService.list(
                USER_ID, null, null, null, null, null, null, null, 1, 20, "currentValueUsd,desc");

        assertThat(resultAsc.getContent().get(resultAsc.getContent().size() - 1).item().name())
                .isEqualTo("Unavailable Item");
        assertThat(resultDesc.getContent().get(resultDesc.getContent().size() - 1).item().name())
                .isEqualTo("Unavailable Item");
    }

    // ---- getById() ----

    @Test
    void getById_ownershipMismatch_throwsOwnershipMismatch() {
        when(dropRepository.findByIdAndUserIdAndDeletedAtIsNull(99L, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dropService.getById(USER_ID, 99L))
                .isInstanceOf(OwnershipMismatchException.class);
    }

    @Test
    void getById_found_returnsMappedResponse() {
        Item item = item(5L, "Revolution Case", ItemType.CASE);
        Drop drop = drop(101L, 5L, new BigDecimal("0.50"), LocalDate.of(2026, 8, 25));
        when(dropRepository.findByIdAndUserIdAndDeletedAtIsNull(101L, USER_ID)).thenReturn(Optional.of(drop));
        when(itemRepository.findAllById(any())).thenReturn(List.of(item));
        when(pricingService.getPrice(5L)).thenReturn(
                new PriceResponse(5L, new BigDecimal("0.52"), true, "STEAM_MARKET", Instant.now()));

        DropResponse response = dropService.getById(USER_ID, 101L);

        assertThat(response.id()).isEqualTo(101L);
        assertThat(response.source()).isEqualTo("MANUAL");
    }

    // ---- create() ----

    @Test
    void create_itemNotFound_throwsResourceNotFound() {
        when(itemRepository.findById(5L)).thenReturn(Optional.empty());
        CreateDropRequest request = new CreateDropRequest(5L, 1, LocalDate.now(), null);

        assertThatThrownBy(() -> dropService.create(USER_ID, request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void create_itemInactive_throwsResourceNotFound() {
        Item inactiveItem = item(5L, "Old Skin", ItemType.SKIN);
        inactiveItem.setIsActive(false);
        when(itemRepository.findById(5L)).thenReturn(Optional.of(inactiveItem));
        CreateDropRequest request = new CreateDropRequest(5L, 1, LocalDate.now(), null);

        assertThatThrownBy(() -> dropService.create(USER_ID, request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void create_validRequest_savesDropWithManualSourceAndReturnsPrice() {
        Item item = item(5L, "Revolution Case", ItemType.CASE);
        when(itemRepository.findById(5L)).thenReturn(Optional.of(item));
        when(dropRepository.save(any(Drop.class))).thenAnswer(inv -> {
            Drop d = inv.getArgument(0);
            d.setId(101L);
            return d;
        });
        when(pricingService.getPrice(5L)).thenReturn(
                new PriceResponse(5L, new BigDecimal("0.52"), true, "STEAM_MARKET", Instant.now()));

        CreateDropRequest request = new CreateDropRequest(5L, 3, LocalDate.of(2026, 8, 30), new BigDecimal("0.48"));
        DropResponse response = dropService.create(USER_ID, request);

        assertThat(response.id()).isEqualTo(101L);
        assertThat(response.source()).isEqualTo("MANUAL");
        assertThat(response.quantity()).isEqualTo(3);
        assertThat(response.acquisitionValueUsd()).isEqualByComparingTo("0.48");
        assertThat(response.currentValueUsd()).isEqualByComparingTo("0.52");

        ArgumentCaptor<Drop> captor = ArgumentCaptor.forClass(Drop.class);
        verify(dropRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().getSource()).isEqualTo(DropSource.MANUAL);
    }

    // ---- update() ----

    @Test
    void update_ownershipMismatch_throwsOwnershipMismatch() {
        when(dropRepository.findByIdAndUserIdAndDeletedAtIsNull(99L, USER_ID)).thenReturn(Optional.empty());
        UpdateDropRequest request = new UpdateDropRequest(5, null, null);

        assertThatThrownBy(() -> dropService.update(USER_ID, 99L, request))
                .isInstanceOf(OwnershipMismatchException.class);
    }

    @Test
    void update_nullFields_leaveUnchanged() {
        Drop existing = drop(101L, 5L, new BigDecimal("0.48"), LocalDate.of(2026, 8, 25));
        existing.setQuantity(3);
        when(dropRepository.findByIdAndUserIdAndDeletedAtIsNull(101L, USER_ID)).thenReturn(Optional.of(existing));
        when(dropRepository.save(any(Drop.class))).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.findAllById(any())).thenReturn(List.of(item(5L, "Revolution Case", ItemType.CASE)));
        when(pricingService.getPrice(5L)).thenReturn(
                new PriceResponse(5L, new BigDecimal("0.52"), true, "STEAM_MARKET", Instant.now()));

        UpdateDropRequest request = new UpdateDropRequest(null, null, null);
        DropResponse response = dropService.update(USER_ID, 101L, request);

        // Nothing sent -> everything stays as it was.
        assertThat(response.quantity()).isEqualTo(3);
        assertThat(response.acquisitionValueUsd()).isEqualByComparingTo("0.48");
        assertThat(response.acquisitionDate()).isEqualTo(LocalDate.of(2026, 8, 25));
    }

    @Test
    void update_partialFields_onlySentFieldsChange() {
        Drop existing = drop(101L, 5L, new BigDecimal("0.48"), LocalDate.of(2026, 8, 25));
        existing.setQuantity(3);
        when(dropRepository.findByIdAndUserIdAndDeletedAtIsNull(101L, USER_ID)).thenReturn(Optional.of(existing));
        when(dropRepository.save(any(Drop.class))).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.findAllById(any())).thenReturn(List.of(item(5L, "Revolution Case", ItemType.CASE)));
        when(pricingService.getPrice(5L)).thenReturn(
                new PriceResponse(5L, new BigDecimal("0.52"), true, "STEAM_MARKET", Instant.now()));

        UpdateDropRequest request = new UpdateDropRequest(10, null, null);
        DropResponse response = dropService.update(USER_ID, 101L, request);

        assertThat(response.quantity()).isEqualTo(10);
        // Untouched fields keep their original value.
        assertThat(response.acquisitionValueUsd()).isEqualByComparingTo("0.48");
        assertThat(response.acquisitionDate()).isEqualTo(LocalDate.of(2026, 8, 25));
    }

    // ---- delete() ----

    @Test
    void delete_ownershipMismatch_throwsOwnershipMismatch() {
        when(dropRepository.findByIdAndUserIdAndDeletedAtIsNull(99L, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dropService.delete(USER_ID, 99L))
                .isInstanceOf(OwnershipMismatchException.class);
    }

    @Test
    void delete_found_softDeletesRow() {
        Drop existing = drop(101L, 5L, null, LocalDate.of(2026, 8, 25));
        when(dropRepository.findByIdAndUserIdAndDeletedAtIsNull(101L, USER_ID)).thenReturn(Optional.of(existing));
        when(dropRepository.save(any(Drop.class))).thenAnswer(inv -> inv.getArgument(0));

        dropService.delete(USER_ID, 101L);

        ArgumentCaptor<Drop> captor = ArgumentCaptor.forClass(Drop.class);
        verify(dropRepository).save(captor.capture());
        assertThat(captor.getValue().getDeletedAt()).isNotNull();
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

    private static Drop drop(Long id, Long itemId, BigDecimal acquisitionValueUsd, LocalDate acquisitionDate) {
        return Drop.builder()
                .id(id).userId(USER_ID).itemId(itemId)
                .source(DropSource.MANUAL)
                .quantity(1)
                .acquisitionValueUsd(acquisitionValueUsd)
                .acquisitionDate(acquisitionDate)
                .createdAt(Instant.now())
                .build();
    }
}

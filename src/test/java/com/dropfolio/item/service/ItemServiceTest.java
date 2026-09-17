package com.dropfolio.item.service;

import com.dropfolio.common.exception.ItemMarketHashNameConflictException;
import com.dropfolio.common.exception.ResourceNotFoundException;
import com.dropfolio.common.exception.ValidationException;
import com.dropfolio.item.dto.CreateItemRequest;
import com.dropfolio.item.dto.UpdateItemRequest;
import com.dropfolio.item.entity.Item;
import com.dropfolio.item.entity.ItemType;
import com.dropfolio.item.repository.ItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ItemService — TECHNICAL_SPEC.md §16.1 (service layer, mocked repository).
 */
@ExtendWith(MockitoExtension.class)
class ItemServiceTest {

    @Mock private ItemRepository itemRepository;

    private ItemService itemService;

    @BeforeEach
    void setUp() {
        itemService = new ItemService(itemRepository);
    }

    @Test
    void list_invalidPage_throwsValidation() {
        assertThatThrownBy(() -> itemService.list(null, null, 0, 20, null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void list_sizeTooLarge_throwsValidation() {
        assertThatThrownBy(() -> itemService.list(null, null, 1, 101, null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void list_sortFieldNotWhitelisted_throwsValidation() {
        assertThatThrownBy(() -> itemService.list(null, null, 1, 20, "marketHashName,asc"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_validParams_returnsMappedPage() {
        Item item = activeItem();
        Page<Item> page = new PageImpl<>(List.of(item), PageRequest.of(0, 20), 1);
        when(itemRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);

        var result = itemService.list("Revolution", ItemType.CASE, 1, 20, "name,asc");

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).name()).isEqualTo("Revolution Case");
    }

    @Test
    void getById_notFound_throwsResourceNotFound() {
        when(itemRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> itemService.getById(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getById_found_returnsMapped() {
        when(itemRepository.findById(5L)).thenReturn(Optional.of(activeItem()));

        var result = itemService.getById(5L);

        assertThat(result.id()).isEqualTo(5L);
        assertThat(result.marketHashName()).isEqualTo("Revolution Case");
    }

    @Test
    void create_duplicateMarketHashName_throwsConflict_withGenericCode() {
        CreateItemRequest req = new CreateItemRequest("Revolution Case", ItemType.CASE, "Revolution Case", null);
        when(itemRepository.existsByMarketHashName("Revolution Case")).thenReturn(true);

        assertThatThrownBy(() -> itemService.create(req))
                .isInstanceOf(ItemMarketHashNameConflictException.class)
                .satisfies(ex -> assertThat(((ItemMarketHashNameConflictException) ex).code()).isEqualTo("CONFLICT"));

        verify(itemRepository, never()).save(any());
    }

    @Test
    void create_success_persistsActiveItem() {
        CreateItemRequest req = new CreateItemRequest("Revolution Case", ItemType.CASE, "Revolution Case", "http://icon");
        when(itemRepository.existsByMarketHashName("Revolution Case")).thenReturn(false);
        when(itemRepository.save(any(Item.class))).thenAnswer(inv -> {
            Item i = inv.getArgument(0);
            i.setId(5L);
            return i;
        });

        var result = itemService.create(req);

        assertThat(result.id()).isEqualTo(5L);
        assertThat(result.isActive()).isTrue();
    }

    @Test
    void update_notFound_throwsResourceNotFound() {
        when(itemRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> itemService.update(99L, new UpdateItemRequest(null, null, false)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void update_partialFields_onlyOverwritesProvidedFields() {
        Item existing = activeItem();
        when(itemRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(itemRepository.save(any(Item.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = itemService.update(5L, new UpdateItemRequest(null, null, false));

        assertThat(result.isActive()).isFalse();
        assertThat(result.name()).isEqualTo("Revolution Case"); // untouched
    }

    private Item activeItem() {
        return Item.builder().id(5L).name("Revolution Case").type(ItemType.CASE)
                .marketHashName("Revolution Case").iconUrl(null).isActive(true).build();
    }
}

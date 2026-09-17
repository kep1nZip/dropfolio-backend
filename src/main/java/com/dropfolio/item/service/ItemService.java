package com.dropfolio.item.service;

import com.dropfolio.common.audit.Auditable;
import com.dropfolio.common.exception.ItemMarketHashNameConflictException;
import com.dropfolio.common.exception.ResourceNotFoundException;
import com.dropfolio.common.exception.ValidationException;
import com.dropfolio.item.dto.CreateItemRequest;
import com.dropfolio.item.dto.ItemResponse;
import com.dropfolio.item.dto.UpdateItemRequest;
import com.dropfolio.item.entity.Item;
import com.dropfolio.item.entity.ItemType;
import com.dropfolio.item.mapper.ItemMapper;
import com.dropfolio.item.repository.ItemRepository;
import com.dropfolio.item.repository.ItemSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;

/** TECHNICAL_SPEC.md §2: all business logic lives here, controller only binds/calls/wraps. */
@Service
public class ItemService {

    /** API_CONTRACT.md §4 GET /items — the ONLY two fields allowed in {@code sort}. */
    private static final Set<String> SORT_WHITELIST = Set.of("name", "createdAt");
    private static final int MAX_PAGE_SIZE = 100;

    private final ItemRepository itemRepository;

    public ItemService(ItemRepository itemRepository) {
        this.itemRepository = itemRepository;
    }

    @Transactional(readOnly = true)
    public Page<ItemResponse> list(String search, ItemType type, int page, int size, String sort) {
        if (page < 1) {
            throw new ValidationException("page must be >= 1");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ValidationException("size must be between 1 and " + MAX_PAGE_SIZE);
        }

        Sort springSort = parseSort(sort);
        // page is 1-based in the contract, Spring Data's PageRequest is 0-based.
        PageRequest pageRequest = PageRequest.of(page - 1, size, springSort);

        var spec = ItemSpecifications.search(search).and(ItemSpecifications.ofType(type));
        return itemRepository.findAll(spec, pageRequest).map(ItemMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public ItemResponse getById(Long id) {
        Item item = itemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found"));
        return ItemMapper.toResponse(item);
    }

    @Auditable(action = "ADMIN_CREATE_ITEM")
    @Transactional
    public ItemResponse create(CreateItemRequest request) {
        if (itemRepository.existsByMarketHashName(request.marketHashName())) {
            throw new ItemMarketHashNameConflictException("marketHashName already exists in the catalog");
        }

        Item item = Item.builder()
                .name(request.name())
                .type(request.type())
                .marketHashName(request.marketHashName())
                .iconUrl(request.iconUrl())
                .isActive(true)
                .createdAt(Instant.now())
                .build();

        Item saved = itemRepository.save(item);
        return ItemMapper.toResponse(saved);
    }

    @Auditable(action = "ADMIN_UPDATE_ITEM")
    @Transactional
    public ItemResponse update(Long id, UpdateItemRequest request) {
        Item item = itemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found"));

        if (request.name() != null) {
            item.setName(request.name());
        }
        if (request.iconUrl() != null) {
            item.setIconUrl(request.iconUrl());
        }
        if (request.isActive() != null) {
            item.setIsActive(request.isActive());
        }
        item.setUpdatedAt(Instant.now());

        Item saved = itemRepository.save(item);
        return ItemMapper.toResponse(saved);
    }

    private Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.ASC, "name");
        }
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        if (!SORT_WHITELIST.contains(field)) {
            throw new ValidationException("sort field not allowed: " + field
                    + " (allowed: " + SORT_WHITELIST + ")");
        }
        Sort.Direction direction = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim())
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;
        return Sort.by(direction, field);
    }
}

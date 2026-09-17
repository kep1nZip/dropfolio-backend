package com.dropfolio.item.controller;

import com.dropfolio.common.envelope.ApiResponse;
import com.dropfolio.item.dto.CreateItemRequest;
import com.dropfolio.item.dto.ItemResponse;
import com.dropfolio.item.dto.UpdateItemRequest;
import com.dropfolio.item.entity.ItemType;
import com.dropfolio.item.service.ItemService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API_CONTRACT.md §4 — Domain: Items (Catalog). GET endpoints are public, POST/PATCH are
 * ADMIN-only — enforced at {@code SecurityConfig} path-matcher level (same single-layer
 * convention already established by auth/'s endpoints, not duplicated here via
 * {@code @PreAuthorize} to avoid introducing a second, inconsistent enforcement mechanism).
 *
 * Controller only binds DTOs, calls one service method, wraps the response — no business logic
 * here (TECHNICAL_SPEC.md §2).
 */
@RestController
@RequestMapping("/api/v1/items")
public class ItemController {

    private final ItemService itemService;

    public ItemController(ItemService itemService) {
        this.itemService = itemService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ItemResponse>>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) ItemType type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        Page<ItemResponse> result = itemService.list(search, type, page, size, sort);
        return ResponseEntity.ok(ApiResponse.of(result.getContent(), paginationMeta(result)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ItemResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(itemService.getById(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ItemResponse>> create(@Valid @RequestBody CreateItemRequest request) {
        ItemResponse created = itemService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/items/" + created.id()))
                .body(ApiResponse.of(created));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<ItemResponse>> update(@PathVariable Long id,
                                                              @Valid @RequestBody UpdateItemRequest request) {
        return ResponseEntity.ok(ApiResponse.of(itemService.update(id, request)));
    }

    private Map<String, Object> paginationMeta(Page<?> page) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("page", page.getNumber() + 1); // back to 1-based for the response
        meta.put("size", page.getSize());
        meta.put("totalElements", page.getTotalElements());
        meta.put("totalPages", page.getTotalPages());
        return meta;
    }
}

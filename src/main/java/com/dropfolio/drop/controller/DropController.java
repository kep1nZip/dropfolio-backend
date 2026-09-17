package com.dropfolio.drop.controller;

import com.dropfolio.common.envelope.ApiResponse;
import com.dropfolio.drop.dto.CreateDropRequest;
import com.dropfolio.drop.dto.DropResponse;
import com.dropfolio.drop.dto.UpdateDropRequest;
import com.dropfolio.drop.entity.DropSource;
import com.dropfolio.drop.service.DropService;
import com.dropfolio.item.entity.ItemType;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API_CONTRACT.md §5 — Domain: Drops. Every endpoint is {@code Auth: Required, Ownership:
 * self} — enforced by {@code SecurityConfig}'s default {@code anyRequest().authenticated()}
 * (no new matcher needed, unlike Milestone 4's public {@code /prices/*}) plus the
 * {@code findByIdAndUserIdAndDeletedAtIsNull} pattern in {@code DropService}/{@code
 * DropRepository} for row-level ownership.
 *
 * Controller only binds DTOs, calls one service method, wraps the response — no business logic
 * here (TECHNICAL_SPEC.md §2), same convention as {@code ItemController}/{@code PriceController}.
 */
@RestController
@RequestMapping("/api/v1/drops")
public class DropController {

    private final DropService dropService;

    public DropController(DropService dropService) {
        this.dropService = dropService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<DropResponse>>> list(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) ItemType type,
            @RequestParam(required = false) DropSource source,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(required = false) BigDecimal minValue,
            @RequestParam(required = false) BigDecimal maxValue,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        Page<DropResponse> result = dropService.list(userId, search, type, source, dateFrom, dateTo,
                minValue, maxValue, page, size, sort);
        return ResponseEntity.ok(ApiResponse.of(result.getContent(), paginationMeta(result)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DropResponse>> getById(@AuthenticationPrincipal Long userId,
                                                              @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(dropService.getById(userId, id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<DropResponse>> create(@AuthenticationPrincipal Long userId,
                                                             @Valid @RequestBody CreateDropRequest request) {
        DropResponse created = dropService.create(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/drops/" + created.id()))
                .body(ApiResponse.of(created));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<DropResponse>> update(@AuthenticationPrincipal Long userId,
                                                             @PathVariable Long id,
                                                             @Valid @RequestBody UpdateDropRequest request) {
        return ResponseEntity.ok(ApiResponse.of(dropService.update(userId, id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        dropService.delete(userId, id);
        return ResponseEntity.noContent().build();
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

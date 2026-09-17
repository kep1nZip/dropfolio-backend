package com.dropfolio.alert.controller;

import com.dropfolio.alert.dto.AlertResponse;
import com.dropfolio.alert.dto.CreateAlertRequest;
import com.dropfolio.alert.dto.UpdateAlertRequest;
import com.dropfolio.alert.entity.AlertStatus;
import com.dropfolio.alert.service.AlertService;
import com.dropfolio.common.envelope.ApiResponse;
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

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API_CONTRACT.md §9 — Domain: Alerts. Every endpoint is {@code Auth: Required, Ownership:
 * self} — enforced by {@code SecurityConfig}'s default {@code anyRequest().authenticated()}
 * (no new matcher needed, same as {@code DropController}) plus
 * {@code findByIdAndUserId} row-level ownership in {@code AlertService}/{@code
 * PriceAlertRepository} — mismatch is 404, never 403 (M8 Implementation Authorization §16).
 *
 * Controller only binds DTOs, calls one service method, wraps the response — no business logic.
 */
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<AlertResponse>>> list(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) AlertStatus status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        Page<AlertResponse> result = alertService.list(userId, status, page, size, sort);
        return ResponseEntity.ok(ApiResponse.of(result.getContent(), paginationMeta(result)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AlertResponse>> getById(@AuthenticationPrincipal Long userId,
                                                               @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(alertService.getById(userId, id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AlertResponse>> create(@AuthenticationPrincipal Long userId,
                                                              @Valid @RequestBody CreateAlertRequest request) {
        AlertResponse created = alertService.create(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/alerts/" + created.id()))
                .body(ApiResponse.of(created));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<AlertResponse>> update(@AuthenticationPrincipal Long userId,
                                                              @PathVariable Long id,
                                                              @Valid @RequestBody UpdateAlertRequest request) {
        return ResponseEntity.ok(ApiResponse.of(alertService.update(userId, id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        alertService.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> paginationMeta(Page<?> page) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("page", page.getNumber() + 1);
        meta.put("size", page.getSize());
        meta.put("totalElements", page.getTotalElements());
        meta.put("totalPages", page.getTotalPages());
        return meta;
    }
}

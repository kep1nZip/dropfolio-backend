package com.dropfolio.notification.controller;

import com.dropfolio.common.envelope.ApiResponse;
import com.dropfolio.notification.dto.NotificationPreferencesResponse;
import com.dropfolio.notification.dto.NotificationResponse;
import com.dropfolio.notification.dto.UpdateNotificationPreferencesRequest;
import com.dropfolio.notification.service.NotificationService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API_CONTRACT.md §10 — Domain: Notifications. Every endpoint is {@code Auth: Required,
 * Ownership: self} — same {@code SecurityConfig} default + {@code findByIdAndUserId} pattern
 * as {@code AlertController}.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<NotificationResponse>>> list(
            @AuthenticationPrincipal Long userId,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<NotificationResponse> result = notificationService.list(userId, unreadOnly, page, size);
        return ResponseEntity.ok(ApiResponse.of(result.getContent(), paginationMeta(result, userId)));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        notificationService.markRead(userId, id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/read-all")
    public ResponseEntity<Void> markAllRead(@AuthenticationPrincipal Long userId) {
        notificationService.markAllRead(userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/preferences")
    public ResponseEntity<ApiResponse<NotificationPreferencesResponse>> getPreferences(
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.of(notificationService.getPreferences(userId)));
    }

    @PatchMapping("/preferences")
    public ResponseEntity<ApiResponse<NotificationPreferencesResponse>> updatePreferences(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody UpdateNotificationPreferencesRequest request) {
        return ResponseEntity.ok(ApiResponse.of(notificationService.updatePreferences(userId, request)));
    }

    /** API_CONTRACT.md §10: GET /notifications meta includes {@code unreadCount} alongside standard pagination. */
    private Map<String, Object> paginationMeta(Page<?> page, Long userId) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("page", page.getNumber() + 1);
        meta.put("size", page.getSize());
        meta.put("totalElements", page.getTotalElements());
        meta.put("totalPages", page.getTotalPages());
        meta.put("unreadCount", notificationService.unreadCount(userId));
        return meta;
    }
}

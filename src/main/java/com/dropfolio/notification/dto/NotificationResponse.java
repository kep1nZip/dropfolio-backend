package com.dropfolio.notification.dto;

import java.time.Instant;

/** Locked response shape — API_CONTRACT.md §10. */
public record NotificationResponse(
        Long id,
        String type,
        String title,
        String message,
        Instant readAt,
        Instant createdAt
) {
}

package com.dropfolio.notification.dto;

/** Locked response shape — API_CONTRACT.md §10 GET/PATCH /notifications/preferences. */
public record NotificationPreferencesResponse(Boolean emailEnabled, Boolean inAppEnabled) {
}

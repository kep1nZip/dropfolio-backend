package com.dropfolio.notification.dto;

/** API_CONTRACT.md §10 PATCH /notifications/preferences — partial, {@code null} = leave unchanged. */
public record UpdateNotificationPreferencesRequest(Boolean emailEnabled, Boolean inAppEnabled) {
}

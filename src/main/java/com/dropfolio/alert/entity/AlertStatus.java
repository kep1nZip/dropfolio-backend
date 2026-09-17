package com.dropfolio.alert.entity;

/** Mirrors the CHECK constraint on {@code price_alerts.status} — ERD.md §2.8 / §4. */
public enum AlertStatus {
    ACTIVE,
    TRIGGERED,
    DISABLED
}

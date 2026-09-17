package com.dropfolio.notification.entity;

/** Mirrors the CHECK constraint on {@code email_jobs.status} — ERD.md §2.12 / §4. */
public enum EmailJobStatus {
    PENDING,
    SENT,
    FAILED
}

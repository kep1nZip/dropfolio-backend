package com.dropfolio.scheduler.entity;

/** Mirrors the CHECK constraint on {@code sync_jobs.triggered_by} — ERD.md §2.11 / §4. */
public enum TriggeredBy {
    SCHEDULER,
    ADMIN
}

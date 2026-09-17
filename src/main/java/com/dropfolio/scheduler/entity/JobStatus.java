package com.dropfolio.scheduler.entity;

/**
 * Mirrors the CHECK constraint on {@code sync_jobs.status} — ERD.md §2.11 / §4.
 * No {@code QUEUED} value — API_CONTRACT.md §11 explicitly locks this 3-value enum and treats
 * "accepted" and "executor actually running" as the same {@code RUNNING} state.
 */
public enum JobStatus {
    RUNNING,
    SUCCESS,
    FAILED
}

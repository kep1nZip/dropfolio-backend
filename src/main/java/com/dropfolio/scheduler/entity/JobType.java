package com.dropfolio.scheduler.entity;

/**
 * Mirrors the CHECK constraint on {@code sync_jobs.job_type} — ERD.md §2.11 / §4.
 *
 * {@code ALERT_EVALUATION} is a reserved enum value for M7 — {@code AlertEvaluationJob} is
 * deferred (M7 Implementation Authorization §2), so no code path in this milestone ever
 * produces a {@code sync_jobs} row with this value. Kept here (not omitted) because the DB
 * CHECK constraint and API_CONTRACT.md §11 query param both already reference it as a valid
 * schema value, independent of whether M7 populates it.
 */
public enum JobType {
    PRICE_SYNC,
    ALERT_EVALUATION
}

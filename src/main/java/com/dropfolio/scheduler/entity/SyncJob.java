package com.dropfolio.scheduler.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Background job execution log — ERD.md §2.11. One row per {@code PriceSyncJob} run, whether
 * triggered by the scheduler or by an admin (M7 Implementation Authorization §16).
 *
 * No {@code @ManyToOne} to {@code User} — same rationale as {@code ItemPrice} not mapping to
 * {@code Item}: nothing in M7 traverses the association, only the raw
 * {@code triggeredByUserId} column is needed.
 */
@Entity
@Table(name = "sync_jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SyncJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, length = 30)
    private JobType jobType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private JobStatus status;

    @Column(name = "items_processed", nullable = false)
    @Builder.Default
    private Integer itemsProcessed = 0;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "triggered_by", nullable = false, length = 20)
    private TriggeredBy triggeredBy;

    /** Nullable — only populated when {@code triggeredBy == ADMIN} (ERD.md §2.11). */
    @Column(name = "triggered_by_user_id")
    private Long triggeredByUserId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;
}

package com.dropfolio.drop.entity;

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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A single manually-entered holding row — ERD.md §2.7. User-scoped (never accessed except via
 * {@code findByIdAndUserId}), soft-deleted (ERD.md §6.3 — history preserved for future
 * portfolio-growth charts), never hard-deleted.
 *
 * No {@code steam_asset_id}, no dedup constraint — 100% manual, no Steam inventory sync exists
 * in this product (PM Decision, CLAUDE_CONTEXT.md §15/§16).
 *
 * {@code created_at}/{@code updated_at}/{@code deleted_at} are mapped {@code DATETIMEOFFSET(6)}
 * in the migration, not the {@code DATETIME2} literally written in ERD.md §2.7 for this table —
 * same Instant/Hibernate 6 fix already applied to {@code users}/{@code user_roles} (V2) and
 * {@code items}/{@code item_prices} (V3/V4). ERD.md §2.7 wasn't updated to carry that fix
 * forward for this table; applying it here rather than reintroducing a known, already-fixed
 * mapping bug. See MILESTONE_5_COMPLETION_REPORT.md for the full note. {@code acquisition_date}
 * is unaffected — it's a plain {@code DATE}/{@link LocalDate}, not an Instant-mapped column
 * (ERD.md §2.9 explains why: it represents a calendar date, not a precise timestamp).
 */
@Entity
@Table(name = "drops")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Drop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    @Builder.Default
    private DropSource source = DropSource.MANUAL;

    @Column(name = "quantity", nullable = false)
    @Builder.Default
    private Integer quantity = 1;

    /** Nullable — snapshot price at acquisition time, if known (ERD.md §2.7). */
    @Column(name = "acquisition_value_usd", precision = 18, scale = 4)
    private BigDecimal acquisitionValueUsd;

    @Column(name = "acquisition_date", nullable = false)
    private LocalDate acquisitionDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Soft delete — ERD.md §6.3. All list/read queries must filter {@code deletedAt IS NULL}. */
    @Column(name = "deleted_at")
    private Instant deletedAt;
}

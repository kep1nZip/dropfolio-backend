package com.dropfolio.pricing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**
 * Time-series price snapshot — ERD.md §2.6. One row per fetch (scheduled job or, per
 * TECHNICAL_SPEC.md §6.2, historically cache-miss — though Milestone 4's read path never
 * writes here itself, only reads). This table doubles as price history; "harga terkini" for an
 * item = latest row by {@code fetched_at DESC}.
 *
 * No {@code @ManyToOne} to {@code Item} is used here — Milestone 4's read path only needs the
 * raw {@code item_id} to query "latest row for this item", not the full Item association;
 * keeping this a plain FK column avoids pulling in the {@code item/} entity for a relationship
 * nothing in this milestone traverses.
 *
 * {@code fetched_at}/{@code created_at} use {@code DATETIMEOFFSET(6)} in the migration (not
 * {@code DATETIME2}) — same Instant/Hibernate 6 fix already applied to {@code items} in V3.
 */
@Entity
@Table(name = "item_prices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ItemPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    /** e.g. {@code STEAM_MARKET}, {@code PRICEEMPIRE} — ERD.md §2.6. */
    @Column(name = "provider", nullable = false, length = 30)
    private String provider;

    /** Nullable — NULL when {@code priceAvailable} is false (ERD.md §2.6). */
    @Column(name = "price_usd", precision = 18, scale = 4)
    private BigDecimal priceUsd;

    @Column(name = "price_available", nullable = false)
    private Boolean priceAvailable;

    /** When the price was fetched from the provider — not when this row was persisted. */
    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

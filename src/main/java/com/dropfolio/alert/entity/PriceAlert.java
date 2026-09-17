package com.dropfolio.alert.entity;

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

/**
 * ERD.md §2.8. No {@code @ManyToOne} to {@code User}/{@code Item} — same convention as
 * {@code ItemPrice}/{@code SyncJob}: nothing here traverses the association, only the raw
 * FK columns are needed (row-level ownership is enforced via
 * {@code findByIdAndUserId} queries in {@code PriceAlertRepository}, not via the entity graph).
 */
@Entity
@Table(name = "price_alerts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "target_price_usd", nullable = false, precision = 18, scale = 4)
    private BigDecimal targetPriceUsd;

    @Column(name = "notify_email", nullable = false)
    private Boolean notifyEmail;

    @Column(name = "notify_in_app", nullable = false)
    private Boolean notifyInApp;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AlertStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "triggered_at")
    private Instant triggeredAt;
}

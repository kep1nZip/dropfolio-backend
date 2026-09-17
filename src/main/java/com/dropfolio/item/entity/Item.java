package com.dropfolio.item.entity;

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
 * Global catalog entry (not user-scoped) — ERD.md §2.5. Never physically deleted — a
 * referenced-by-drops item is deactivated via {@code isActive=false}, never removed
 * (API_CONTRACT.md §4 PATCH /items/{id}: "tidak ada DELETE /items/{id}").
 *
 * {@code created_at}/{@code updated_at} are mapped {@code DATETIMEOFFSET(6)} in the migration
 * (not {@code DATETIME2}) — same fix already applied to users/user_roles in V2, applied here
 * from the start to avoid repeating that mismatch (Hibernate 6.5 maps java.time.Instant to
 * DATETIMEOFFSET, not DATETIME2).
 */
@Entity
@Table(name = "items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private ItemType type;

    /** Key used to query the price provider — ERD.md §2.5. */
    @Column(name = "market_hash_name", nullable = false, unique = true, length = 300)
    private String marketHashName;

    @Column(name = "icon_url", length = 500)
    private String iconUrl;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}

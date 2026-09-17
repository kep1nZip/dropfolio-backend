package com.dropfolio.user.entity;

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
 * Root identity — ERD.md §2.1. Represents both email/password accounts and steam-only
 * accounts (nullable {@code email}/{@code passwordHash}, PRD §3.1B).
 *
 * NOTE: {@code user_roles} is intentionally NOT mapped as a {@code @ManyToMany} collection
 * on this entity — see {@link UserRole}. Callers needing a user's roles go through
 * {@code UserRoleRepository} explicitly, keeping the ownership of that join table's lifecycle
 * (assignment/audit trail) in one place rather than implicit cascade behavior.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nullable — steam-only accounts don't require an email at first register. */
    @Column(name = "email", length = 320, unique = true)
    private String email;

    /** Nullable — steam-only accounts have no password until they set one themselves. */
    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    /** Instant-JWT-revoke mechanism — SYSTEM_ARCHITECTURE.md §3.3. */
    @Column(name = "token_version", nullable = false)
    @Builder.Default
    private Integer tokenVersion = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Soft delete marker — ERD.md §6.1. */
    @Column(name = "deleted_at")
    private Instant deletedAt;
}

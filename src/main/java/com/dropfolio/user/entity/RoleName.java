package com.dropfolio.user.entity;

/**
 * Mirrors the CHECK constraint on {@code roles.name} — ERD.md §2.2.
 * {@code PREMIUM} is a seeded row for future use but drives no logic in MVP (PRD §7).
 */
public enum RoleName {
    USER,
    ADMIN,
    PREMIUM
}

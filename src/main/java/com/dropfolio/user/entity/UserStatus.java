package com.dropfolio.user.entity;

/** Mirrors the CHECK constraint on {@code users.status} — ERD.md §2.1. */
public enum UserStatus {
    ACTIVE,
    DEACTIVATED,
    DELETED
}

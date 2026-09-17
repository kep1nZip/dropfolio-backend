package com.dropfolio.admin.dto;

import java.time.Instant;

/**
 * API_CONTRACT.md §11 GET /admin/users — "list user (tanpa passwordHash)". No explicit field
 * list is given in the contract beyond that constraint, so this mirrors the fields already
 * exposed elsewhere for a user (GET /users/me shape, minus roles/steamIntegration which are
 * reserved for the detail view) — explicit mapping, never the entity directly, so
 * {@code passwordHash}/{@code tokenVersion} can never leak by accident.
 */
public record AdminUserResponse(
        Long id,
        String email,
        String displayName,
        String status,
        Instant createdAt
) {
}

package com.dropfolio.user.dto;

import java.time.Instant;
import java.util.List;

/**
 * Locked response shape — API_CONTRACT.md §2 GET/PATCH /users/me. {@code passwordHash} never
 * appears here — this record has no such field at all (structural guarantee, not just a
 * mapping choice).
 *
 * {@code steamIntegration} is ALWAYS {@code null} — M10 Implementation Authorization §3
 * (LOCKED PM DECISION): follows the M9 precedent for {@code GET /admin/users/{id}} exactly.
 * This product has no Steam login/OAuth/account-linking anywhere; {@code null} represents "no
 * integration" without building, faking, or implying one exists.
 */
public record UserProfileResponse(
        Long id,
        String email,
        String displayName,
        String status,
        List<String> roles,
        Boolean steamIntegration,
        Instant createdAt
) {
}

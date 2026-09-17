package com.dropfolio.auth.dto;

/** TECHNICAL_SPEC.md §3.1 / API_CONTRACT.md POST /auth/register 201 response. */
public record RegisterResponse(Long userId, String email, String displayName) {
}

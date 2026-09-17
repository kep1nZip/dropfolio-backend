package com.dropfolio.auth.dto;

/**
 * TECHNICAL_SPEC.md §3.1 / API_CONTRACT.md POST /auth/login 200 response.
 * Refresh token is never part of this body — it's set via Set-Cookie (dropfolio_rt) only.
 */
public record LoginResponse(String accessToken, int expiresIn, UserSummary user) {
}

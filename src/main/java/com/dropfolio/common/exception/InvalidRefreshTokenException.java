package com.dropfolio.common.exception;

/**
 * Refresh token invalid/expired/reused. Reuse detection (TECHNICAL_SPEC.md §5) juga
 * melempar exception ini setelah men-trigger revoke seluruh token family di Redis.
 */
public class InvalidRefreshTokenException extends UnauthorizedException {
    public InvalidRefreshTokenException(String message) {
        super("INVALID_REFRESH_TOKEN", message);
    }
}

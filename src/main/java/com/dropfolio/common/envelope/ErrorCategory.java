package com.dropfolio.common.envelope;

import org.springframework.http.HttpStatus;

/**
 * Fixed set of 8 error categories — API_CONTRACT.md §0.6.1 / CLAUDE_CONTEXT.md §7.2.
 *
 * This set is LOCKED. Do not add, remove, or rename values without a contract revision —
 * see CLAUDE_CONTEXT.md §11 scope freeze.
 */
public enum ErrorCategory {
    VALIDATION_ERROR(HttpStatus.UNPROCESSABLE_ENTITY),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    CONFLICT(HttpStatus.CONFLICT),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    UPSTREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus httpStatus;

    ErrorCategory(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}

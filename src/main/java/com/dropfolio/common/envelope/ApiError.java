package com.dropfolio.common.envelope;

/**
 * Error detail object — CLAUDE_CONTEXT.md §7.1 / §7.2 / §7.3.
 *
 * {@code category} is the fixed HTTP-level set (8 values, API_CONTRACT.md §0.6.1).
 * {@code code} is the extensible domain registry (API_CONTRACT.md §0.6.2). These two fields
 * are kept strictly separate per lock rule — never merged into a single field.
 *
 * When an error has no domain-specific reason, {@code code} MUST equal {@code category}
 * (e.g. a plain 401 with no specific cause → category=UNAUTHORIZED, code=UNAUTHORIZED) —
 * this is mandatory, not optional, per CLAUDE_CONTEXT.md §7.1.
 */
public record ApiError(ErrorCategory category, String code, String message) {

    public static ApiError of(ErrorCategory category, String message) {
        return new ApiError(category, category.name(), message);
    }

    public static ApiError of(ErrorCategory category, String code, String message) {
        return new ApiError(category, code, message);
    }
}

package com.dropfolio.common.exception;

import com.dropfolio.common.envelope.ErrorCategory;

/**
 * Root of the domain exception hierarchy — CLAUDE_CONTEXT.md §7.4.
 *
 * Every concrete exception maps 1:1 to an {@link ErrorCategory} and a domain
 * {@code code} (API_CONTRACT.md §0.6.2 registry). Controllers never throw these directly to
 * the client body — {@code GlobalExceptionHandler} (common/exception) translates every
 * instance into the standard error envelope (§7.1).
 */
public abstract class DropfolioException extends RuntimeException {

    private final ErrorCategory category;
    private final String code;

    protected DropfolioException(ErrorCategory category, String code, String message) {
        super(message);
        this.category = category;
        this.code = code;
    }

    public ErrorCategory category() {
        return category;
    }

    public String code() {
        return code;
    }
}

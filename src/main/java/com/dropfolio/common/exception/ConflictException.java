package com.dropfolio.common.exception;

import com.dropfolio.common.envelope.ErrorCategory;

/**
 * Abstract base for all 409 CONFLICT domain errors — CLAUDE_CONTEXT.md §7.4.
 * Concrete subclasses fix the domain `code` (API_CONTRACT.md §0.6.2 registry).
 */
public abstract class ConflictException extends DropfolioException {
    protected ConflictException(String code, String message) {
        super(ErrorCategory.CONFLICT, code, message);
    }
}

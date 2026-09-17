package com.dropfolio.common.exception;

import com.dropfolio.common.envelope.ErrorCategory;

/**
 * Abstract base for all 401 UNAUTHORIZED domain errors — CLAUDE_CONTEXT.md §7.4.
 */
public abstract class UnauthorizedException extends DropfolioException {
    protected UnauthorizedException(String code, String message) {
        super(ErrorCategory.UNAUTHORIZED, code, message);
    }
}

package com.dropfolio.common.exception;

import com.dropfolio.common.envelope.ErrorCategory;

/** Rate limit terlampaui. Maps to 429 RATE_LIMITED — TECHNICAL_SPEC.md §13. */
public class RateLimitedException extends DropfolioException {
    public RateLimitedException(String message) {
        super(ErrorCategory.RATE_LIMITED, ErrorCategory.RATE_LIMITED.name(), message);
    }
}

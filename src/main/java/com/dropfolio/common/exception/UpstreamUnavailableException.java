package com.dropfolio.common.exception;

import com.dropfolio.common.envelope.ErrorCategory;

/**
 * Upstream dependency (Steam API / price provider / SMTP) unavailable dan circuit breaker
 * open atau fallback gagal. Maps to 503 UPSTREAM_UNAVAILABLE — TECHNICAL_SPEC.md §7.
 */
public class UpstreamUnavailableException extends DropfolioException {
    public UpstreamUnavailableException(String message) {
        super(ErrorCategory.UPSTREAM_UNAVAILABLE, ErrorCategory.UPSTREAM_UNAVAILABLE.name(), message);
    }
}

package com.dropfolio.common.exception;

import com.dropfolio.common.envelope.ErrorCategory;

/**
 * Business-rule validation failure that isn't a plain Bean Validation annotation violation
 * (e.g. cross-field rules like "at least one of notifyEmail/notifyInApp must be true").
 * Maps to 422 VALIDATION_ERROR — API_CONTRACT.md §0.6.1.
 */
public class ValidationException extends DropfolioException {

    public ValidationException(String message) {
        super(ErrorCategory.VALIDATION_ERROR, ErrorCategory.VALIDATION_ERROR.name(), message);
    }
}

package com.dropfolio.common.exception;

import com.dropfolio.common.envelope.ErrorCategory;

/** Resource tidak ditemukan sama sekali (bukan ownership mismatch). Maps to 404 NOT_FOUND. */
public class ResourceNotFoundException extends DropfolioException {
    public ResourceNotFoundException(String message) {
        super(ErrorCategory.NOT_FOUND, ErrorCategory.NOT_FOUND.name(), message);
    }
}

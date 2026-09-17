package com.dropfolio.common.exception;

import com.dropfolio.common.envelope.ErrorCategory;

/** Role tidak cukup (mis. non-ADMIN memanggil endpoint admin/). Maps to 403 FORBIDDEN. */
public class InsufficientRoleException extends DropfolioException {
    public InsufficientRoleException(String message) {
        super(ErrorCategory.FORBIDDEN, ErrorCategory.FORBIDDEN.name(), message);
    }
}

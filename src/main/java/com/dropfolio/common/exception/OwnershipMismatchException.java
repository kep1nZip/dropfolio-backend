package com.dropfolio.common.exception;

import com.dropfolio.common.envelope.ErrorCategory;

/**
 * Resource ada tapi milik user lain. WAJIB di-map ke 404 (bukan 403) — ini locked security
 * rule di CLAUDE_CONTEXT.md / TECHNICAL_SPEC.md: jangan bocorkan keberadaan resource ke
 * user yang tidak memilikinya. Repository harus pakai findByIdAndUserId(...) sehingga
 * exception ini biasanya dilempar dari kondisi "Optional.empty()" pada query ownership-scoped,
 * identik dengan ResourceNotFoundException secara response, tapi dipisah sebagai class agar
 * intent di kode service tetap eksplisit (audit/log bisa membedakan "genuinely missing" vs
 * "belongs to someone else" secara internal tanpa membocorkannya ke client).
 */
public class OwnershipMismatchException extends DropfolioException {
    public OwnershipMismatchException(String message) {
        super(ErrorCategory.NOT_FOUND, ErrorCategory.NOT_FOUND.name(), message);
    }
}

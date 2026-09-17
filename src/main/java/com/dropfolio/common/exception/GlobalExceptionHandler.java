package com.dropfolio.common.exception;

import com.dropfolio.common.envelope.ApiError;
import com.dropfolio.common.envelope.ApiErrorResponse;
import com.dropfolio.common.envelope.ErrorCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * Translates every exception into the standard error envelope — CLAUDE_CONTEXT.md §7.1/§7.2.
 * Controllers never build {@link ApiErrorResponse} themselves.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** All domain exceptions already carry their category + code. */
    @ExceptionHandler(DropfolioException.class)
    public ResponseEntity<ApiErrorResponse> handleDropfolio(DropfolioException ex) {
        return build(ex.category(), ex.code(), ex.getMessage());
    }

    /** Bean Validation (@Valid on request DTO) — aggregate field errors into one message. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleBeanValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(ErrorCategory.VALIDATION_ERROR, ErrorCategory.VALIDATION_ERROR.name(),
                message.isBlank() ? "Validation failed" : message);
    }

    /**
     * Malformed JSON body — most commonly an invalid enum value (e.g. {@code "type": "WEAPON"}
     * where only CASE/SKIN/GRAFFITI are valid) failing during Jackson deserialization, which
     * happens BEFORE {@code @Valid} even runs. Without this handler such a request fell through
     * to {@link #handleUnexpected} and wrongly returned 500 instead of 422 — latent gap since
     * Milestone 1 (no request DTO had an enum field until item/'s CreateItemRequest.type).
     * General common/ fix, not item/-specific — found while implementing Milestone 3.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleMalformedJson(HttpMessageNotReadableException ex) {
        return build(ErrorCategory.VALIDATION_ERROR, ErrorCategory.VALIDATION_ERROR.name(),
                "Malformed request body");
    }

    /**
     * Invalid enum value in a query param (e.g. {@code ?type=WEAPON}) — same rationale as
     * {@link #handleMalformedJson}, just the query-param equivalent (body vs. param binding
     * fail differently in Spring MVC).
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return build(ErrorCategory.VALIDATION_ERROR, ErrorCategory.VALIDATION_ERROR.name(),
                "Invalid value for parameter: " + ex.getName());
    }

    /** Anything unexpected — never leak stack trace / internal message to the client. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return build(ErrorCategory.INTERNAL_ERROR, ErrorCategory.INTERNAL_ERROR.name(),
                "An unexpected error occurred");
    }

    private ResponseEntity<ApiErrorResponse> build(ErrorCategory category, String code, String message) {
        ApiError error = ApiError.of(category, code, message);
        return ResponseEntity.status(category.httpStatus()).body(ApiErrorResponse.of(error));
    }
}

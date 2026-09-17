package com.dropfolio.common.exception;

import com.dropfolio.common.envelope.ApiErrorResponse;
import com.dropfolio.common.envelope.ErrorCategory;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies every domain exception maps to the correct category/status and that unhandled
 * exceptions never leak internal details — CLAUDE_CONTEXT.md §7.2.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void validationException_mapsTo422() {
        ResponseEntity<ApiErrorResponse> res = handler.handleDropfolio(new ValidationException("bad input"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(res.getBody().error().category()).isEqualTo(ErrorCategory.VALIDATION_ERROR);
        assertThat(res.getBody().error().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(res.getBody().success()).isFalse();
    }

    @Test
    void resourceNotFound_mapsTo404() {
        ResponseEntity<ApiErrorResponse> res = handler.handleDropfolio(new ResourceNotFoundException("not found"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody().error().category()).isEqualTo(ErrorCategory.NOT_FOUND);
    }

    @Test
    void ownershipMismatch_mapsTo404NotForbidden() {
        // Critical security rule: ownership mismatch must NEVER be 403 — CLAUDE_CONTEXT.md.
        ResponseEntity<ApiErrorResponse> res = handler.handleDropfolio(new OwnershipMismatchException("not yours"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void insufficientRole_mapsTo403() {
        ResponseEntity<ApiErrorResponse> res = handler.handleDropfolio(new InsufficientRoleException("no admin"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void invalidCredentials_mapsTo401WithSpecificCode() {
        ResponseEntity<ApiErrorResponse> res = handler.handleDropfolio(new InvalidCredentialsException("bad login"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody().error().code()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void invalidRefreshToken_mapsTo401WithSpecificCode() {
        ResponseEntity<ApiErrorResponse> res = handler.handleDropfolio(new InvalidRefreshTokenException("reused"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody().error().code()).isEqualTo("INVALID_REFRESH_TOKEN");
    }

    @Test
    void emailAlreadyRegistered_mapsTo409WithSpecificCode() {
        ResponseEntity<ApiErrorResponse> res = handler.handleDropfolio(new EmailAlreadyRegisteredException("dup"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().error().code()).isEqualTo("EMAIL_ALREADY_REGISTERED");
    }

    @Test
    void steamAlreadyLinked_mapsTo409WithSpecificCode() {
        // Note: STEAM_ALREADY_LINKED_TO_ANOTHER_USER is intentionally NOT a thrown/caught
        // JSON exception — TECHNICAL_SPEC.md §3.0 point 5 / §5.1 specifies it is surfaced via
        // a redirect error state on GET /steam/link/callback, not through GlobalExceptionHandler.
        // Implemented directly in the steam/ controller when that module is built (milestone 6).
        ResponseEntity<ApiErrorResponse> res1 = handler.handleDropfolio(new SteamAlreadyLinkedException("self"));
        assertThat(res1.getBody().error().code()).isEqualTo("STEAM_ALREADY_LINKED");
        assertThat(res1.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void rateLimited_mapsTo429() {
        ResponseEntity<ApiErrorResponse> res = handler.handleDropfolio(new RateLimitedException("slow down"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void upstreamUnavailable_mapsTo503() {
        ResponseEntity<ApiErrorResponse> res = handler.handleDropfolio(new UpstreamUnavailableException("steam down"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void unexpectedException_mapsTo500AndDoesNotLeakInternalMessage() {
        ResponseEntity<ApiErrorResponse> res =
                handler.handleUnexpected(new RuntimeException("SELECT * FROM users failed: leaked SQL"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.getBody().error().message()).doesNotContain("SELECT");
        assertThat(res.getBody().error().category()).isEqualTo(ErrorCategory.INTERNAL_ERROR);
    }
}

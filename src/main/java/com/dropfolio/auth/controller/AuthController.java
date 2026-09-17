package com.dropfolio.auth.controller;

import com.dropfolio.auth.dto.LoginRequest;
import com.dropfolio.auth.dto.LoginResponse;
import com.dropfolio.auth.dto.RegisterRequest;
import com.dropfolio.auth.dto.RegisterResponse;
import com.dropfolio.auth.service.AuthService;
import com.dropfolio.auth.service.RefreshTokenService;
import com.dropfolio.common.envelope.ApiResponse;
import com.dropfolio.common.security.CookieProperties;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API_CONTRACT.md §1 — register/login/refresh/logout. Steam login (§1 GET /auth/steam/*) is
 * out of this milestone's scope, landing with steam/ (CLAUDE_CONTEXT.md §12).
 *
 * Controller only binds DTOs, calls one service method, and wraps the response — no business
 * logic here (TECHNICAL_SPEC.md §2). The refresh-token cookie is the one piece of plumbing
 * that legitimately lives at this layer (it's a transport concern, not business logic).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final String REFRESH_COOKIE_NAME = "dropfolio_rt";
    private static final String REFRESH_COOKIE_PATH = "/api/v1/auth";
    private static final long REFRESH_COOKIE_MAX_AGE_SECONDS = 30L * 24 * 60 * 60; // 30 days

    private final AuthService authService;
    private final RefreshTokenService refreshTokenService;
    private final CookieProperties cookieProperties;

    public AuthController(AuthService authService, RefreshTokenService refreshTokenService,
                           CookieProperties cookieProperties) {
        this.authService = authService;
        this.refreshTokenService = refreshTokenService;
        this.cookieProperties = cookieProperties;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<RegisterResponse>> register(@Valid @RequestBody RegisterRequest request) {
        RegisterResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request,
                                                              HttpServletResponse response) {
        AuthService.LoginResult result = authService.login(request);
        setRefreshCookie(response, result.refreshTokenJwt(), REFRESH_COOKIE_MAX_AGE_SECONDS);
        return ResponseEntity.ok(ApiResponse.of(result.body()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<LoginResponse>> refresh(
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshCookie,
            HttpServletResponse response) {
        // AuthService.refresh throws InvalidRefreshTokenException (→ 401) for null/blank too,
        // via JwtService failing to parse a null token — handled uniformly by GlobalExceptionHandler.
        AuthService.LoginResult result = authService.refresh(refreshCookie);
        setRefreshCookie(response, result.refreshTokenJwt(), REFRESH_COOKIE_MAX_AGE_SECONDS);
        return ResponseEntity.ok(ApiResponse.of(result.body()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String refreshCookie,
            HttpServletResponse response) {
        authService.logout(refreshCookie);
        clearRefreshCookie(response);
        return ResponseEntity.noContent().build();
    }

    private void setRefreshCookie(HttpServletResponse response, String value, long maxAgeSeconds) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE_NAME, value)
                .httpOnly(true)
                .secure(cookieProperties.secure())
                .sameSite("Strict")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(maxAgeSeconds)
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(cookieProperties.secure())
                .sameSite("Strict")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(0)
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }
}

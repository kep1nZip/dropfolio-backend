package com.dropfolio.user.controller;

import com.dropfolio.common.envelope.ApiResponse;
import com.dropfolio.common.security.CookieProperties;
import com.dropfolio.user.dto.ChangePasswordRequest;
import com.dropfolio.user.dto.DeleteAccountRequest;
import com.dropfolio.user.dto.UpdateProfileRequest;
import com.dropfolio.user.dto.UserProfileResponse;
import com.dropfolio.user.service.UserSelfService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API_CONTRACT.md §2 - Domain: Users. Every endpoint is Auth: Required, Ownership: self - the
 * caller's own userId comes only from the JWT principal, never a path variable, so there is no
 * ownership-mismatch case to defend against here (unlike AlertController/NotificationController's
 * /{id} routes).
 *
 * Controller only binds DTOs, calls one service method, wraps the response - no business
 * logic. The refresh-token cookie clear on logout-all/DELETE is transport plumbing, same
 * rationale AuthController already documents for its own cookie handling - duplicated here in
 * miniature (name/path constants) rather than extracting a shared helper, since that would be
 * an unrelated refactor of AuthController outside M10's locked scope.
 */
@RestController
@RequestMapping("/api/v1/users/me")
public class UserController {

    private static final String REFRESH_COOKIE_NAME = "dropfolio_rt";
    private static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

    private final UserSelfService userSelfService;
    private final CookieProperties cookieProperties;

    public UserController(UserSelfService userSelfService, CookieProperties cookieProperties) {
        this.userSelfService = userSelfService;
        this.cookieProperties = cookieProperties;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<UserProfileResponse>> getProfile(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.of(userSelfService.getProfile(userId)));
    }

    @PatchMapping
    public ResponseEntity<ApiResponse<UserProfileResponse>> updateProfile(
            @AuthenticationPrincipal Long userId, @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(ApiResponse.of(userSelfService.updateProfile(userId, request)));
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(
            @AuthenticationPrincipal Long userId, @Valid @RequestBody ChangePasswordRequest request) {
        userSelfService.changePassword(userId, request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(@AuthenticationPrincipal Long userId, HttpServletResponse response) {
        userSelfService.logoutAll(userId);
        clearRefreshCookie(response);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> deleteAccount(
            @AuthenticationPrincipal Long userId, @Valid @RequestBody DeleteAccountRequest request,
            HttpServletResponse response) {
        userSelfService.deleteAccount(userId, request);
        clearRefreshCookie(response);
        return ResponseEntity.noContent().build();
    }

    /** Same clearing convention as AuthController.clearRefreshCookie - both revoke server-side AND clear the browser cookie. */
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

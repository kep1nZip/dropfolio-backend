package com.dropfolio.user.controller;

import com.dropfolio.common.exception.EmailAlreadyRegisteredException;
import com.dropfolio.common.exception.InvalidCurrentPasswordException;
import com.dropfolio.common.ratelimit.RateLimitProperties;
import com.dropfolio.common.security.CookieProperties;
import com.dropfolio.common.security.JwtService;
import com.dropfolio.common.security.SecurityConfig;
import com.dropfolio.common.security.TokenVersionProvider;
import com.dropfolio.user.dto.UserProfileResponse;
import com.dropfolio.user.service.UserSelfService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract-level tests for the 5 locked /users/me endpoints - M10 Implementation
 * Authorization §13. Every endpoint requires only authentication (no role, no ownership
 * path-param - the resource is always "self") - SecurityConfig's default
 * anyRequest().authenticated() covers this, same as DropController/AlertController.
 */
@WebMvcTest(value = UserController.class, properties = "app.cors.allowed-origin=http://localhost:3000")
@Import(SecurityConfig.class)
class UserControllerTest {

    private static final Long USER_ID = 1L;

    @Autowired private MockMvc mockMvc;

    @MockBean private UserSelfService userSelfService;
    @MockBean private CookieProperties cookieProperties;
    @MockBean private JwtService jwtService;
    @MockBean private TokenVersionProvider tokenVersionProvider;
    @MockBean private RedisTemplate<String, Object> redisTemplate;

    @TestConfiguration
    static class RateLimitPropertiesTestConfig {
        @Bean
        RateLimitProperties rateLimitProperties() {
            return new RateLimitProperties(60, 60,
                    new RateLimitProperties.Scope(900, 10),
                    new RateLimitProperties.Scope(3600, 5));
        }
    }

    private static RequestPostProcessor asUser(Long userId) {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        return SecurityMockMvcRequestPostProcessors.authentication(auth);
    }

    private UserProfileResponse sampleProfile() {
        return new UserProfileResponse(USER_ID, "budi@example.com", "Budi", "ACTIVE",
                List.of("USER"), null, Instant.parse("2026-09-14T00:00:00Z"));
    }

    @Test
    void getProfile_authenticated_returns200() throws Exception {
        when(userSelfService.getProfile(USER_ID)).thenReturn(sampleProfile());

        mockMvc.perform(get("/api/v1/users/me").with(asUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("budi@example.com"))
                .andExpect(jsonPath("$.data.steamIntegration").isEmpty());
    }

    @Test
    void getProfile_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void updateProfile_validPartialUpdate_returns200() throws Exception {
        when(userSelfService.updateProfile(eq(USER_ID), any())).thenReturn(sampleProfile());

        mockMvc.perform(patch("/api/v1/users/me").with(asUser(USER_ID))
                        .contentType("application/json")
                        .content("{\"displayName\":\"New Name\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void updateProfile_invalidEmail_returns422() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me").with(asUser(USER_ID))
                        .contentType("application/json")
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void updateProfile_emailConflict_returns409() throws Exception {
        when(userSelfService.updateProfile(eq(USER_ID), any()))
                .thenThrow(new EmailAlreadyRegisteredException("Email already registered"));

        mockMvc.perform(patch("/api/v1/users/me").with(asUser(USER_ID))
                        .contentType("application/json")
                        .content("{\"email\":\"taken@example.com\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void updateProfile_noToken_returns401() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changePassword_valid_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/users/me/password").with(asUser(USER_ID))
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"OldPass123!\",\"newPassword\":\"NewPass456!\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void changePassword_incorrectCurrentPassword_returns401() throws Exception {
        org.mockito.Mockito.doThrow(new InvalidCurrentPasswordException("Current password is incorrect"))
                .when(userSelfService).changePassword(eq(USER_ID), any());

        mockMvc.perform(post("/api/v1/users/me/password").with(asUser(USER_ID))
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"wrong\",\"newPassword\":\"NewPass456!\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CURRENT_PASSWORD"));
    }

    @Test
    void changePassword_weakNewPassword_returns422() throws Exception {
        mockMvc.perform(post("/api/v1/users/me/password").with(asUser(USER_ID))
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"OldPass123!\",\"newPassword\":\"weak\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void changePassword_noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/users/me/password")
                        .contentType("application/json").content("{\"newPassword\":\"NewPass456!\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutAll_authenticated_returns204_clearsRefreshCookie() throws Exception {
        mockMvc.perform(post("/api/v1/users/me/logout-all").with(asUser(USER_ID)))
                .andExpect(status().isNoContent())
                .andExpect(header().exists("Set-Cookie"));
    }

    @Test
    void logoutAll_noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/users/me/logout-all")).andExpect(status().isUnauthorized());
    }

    @Test
    void deleteAccount_correctConfirmation_returns204_clearsRefreshCookie() throws Exception {
        mockMvc.perform(delete("/api/v1/users/me").with(asUser(USER_ID))
                        .contentType("application/json")
                        .content("{\"confirmation\":\"DELETE\"}"))
                .andExpect(status().isNoContent())
                .andExpect(header().exists("Set-Cookie"));
    }

    @Test
    void deleteAccount_missingConfirmation_returns422() throws Exception {
        mockMvc.perform(delete("/api/v1/users/me").with(asUser(USER_ID))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void deleteAccount_noToken_returns401() throws Exception {
        mockMvc.perform(delete("/api/v1/users/me")
                        .contentType("application/json").content("{\"confirmation\":\"DELETE\"}"))
                .andExpect(status().isUnauthorized());
    }
}

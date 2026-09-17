package com.dropfolio.admin.controller;

import com.dropfolio.admin.dto.AdminUserDetailResponse;
import com.dropfolio.admin.dto.AdminUserResponse;
import com.dropfolio.admin.service.AdminUserService;
import com.dropfolio.common.exception.ResourceNotFoundException;
import com.dropfolio.common.exception.ValidationException;
import com.dropfolio.common.ratelimit.RateLimitProperties;
import com.dropfolio.common.security.CookieProperties;
import com.dropfolio.common.security.JwtService;
import com.dropfolio.common.security.SecurityConfig;
import com.dropfolio.common.security.TokenVersionProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contract-level tests for the 3 admin user endpoints — M9 Implementation Authorization §3/§4/§5/§14. */
@WebMvcTest(AdminUserController.class)
@Import(SecurityConfig.class)
class AdminUserControllerTest {

    private static final Long ADMIN_ID = 7L;

    @Autowired private MockMvc mockMvc;

    @MockBean private AdminUserService adminUserService;
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

    private static RequestPostProcessor asAdmin(Long userId) {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        return SecurityMockMvcRequestPostProcessors.authentication(auth);
    }

    private static RequestPostProcessor asUser(Long userId) {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        return SecurityMockMvcRequestPostProcessors.authentication(auth);
    }

    private AdminUserResponse sampleUser() {
        return new AdminUserResponse(1L, "user@example.com", "Budi", "ACTIVE", Instant.parse("2026-09-13T00:00:00Z"));
    }

    private AdminUserDetailResponse sampleDetail(String status) {
        return new AdminUserDetailResponse(1L, "user@example.com", "Budi", status,
                Instant.parse("2026-09-13T00:00:00Z"), Instant.parse("2026-09-13T00:00:00Z"), 7L, 3L, null);
    }

    // ---- list ----

    @Test
    void list_asAdmin_returns200_noPasswordHashInPayload() throws Exception {
        when(adminUserService.list(any(), any(), eq(1), eq(20)))
                .thenReturn(new PageImpl<>(List.of(sampleUser()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/admin/users").with(asAdmin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].email").value("user@example.com"))
                .andExpect(jsonPath("$.data[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$.meta.totalElements").value(1));
    }

    @Test
    void list_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users")).andExpect(status().isUnauthorized());
    }

    @Test
    void list_authenticatedNonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users").with(asUser(ADMIN_ID)))
                .andExpect(status().isForbidden());
    }

    // ---- getById ----

    @Test
    void getById_asAdmin_returns200() throws Exception {
        when(adminUserService.getById(1L)).thenReturn(sampleDetail("ACTIVE"));

        mockMvc.perform(get("/api/v1/admin/users/1").with(asAdmin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalDrops").value(7))
                .andExpect(jsonPath("$.data.totalAlerts").value(3))
                .andExpect(jsonPath("$.data.steamIntegration").isEmpty());
    }

    @Test
    void getById_nonexistent_returns404() throws Exception {
        when(adminUserService.getById(999L)).thenThrow(new ResourceNotFoundException("User not found"));

        mockMvc.perform(get("/api/v1/admin/users/999").with(asAdmin(ADMIN_ID)))
                .andExpect(status().isNotFound());
    }

    @Test
    void getById_authenticatedNonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users/1").with(asUser(ADMIN_ID)))
                .andExpect(status().isForbidden());
    }

    // ---- updateStatus ----

    @Test
    void updateStatus_asAdmin_returns200() throws Exception {
        when(adminUserService.updateStatus(eq(1L), any())).thenReturn(sampleDetail("DEACTIVATED"));

        mockMvc.perform(patch("/api/v1/admin/users/1/status").with(asAdmin(ADMIN_ID))
                        .contentType("application/json")
                        .content("{\"status\":\"DEACTIVATED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DEACTIVATED"));
    }

    @Test
    void updateStatus_deletedTarget_returns422() throws Exception {
        when(adminUserService.updateStatus(eq(1L), any())).thenThrow(new ValidationException("status must be ACTIVE or DEACTIVATED"));

        mockMvc.perform(patch("/api/v1/admin/users/1/status").with(asAdmin(ADMIN_ID))
                        .contentType("application/json")
                        .content("{\"status\":\"DELETED\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void updateStatus_noToken_returns401() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/users/1/status")
                        .contentType("application/json").content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateStatus_authenticatedNonAdmin_returns403() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/users/1/status").with(asUser(ADMIN_ID))
                        .contentType("application/json").content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isForbidden());
    }
}

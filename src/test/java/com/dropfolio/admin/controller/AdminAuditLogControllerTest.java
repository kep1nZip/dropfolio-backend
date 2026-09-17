package com.dropfolio.admin.controller;

import com.dropfolio.admin.dto.AuditLogResponse;
import com.dropfolio.admin.service.AdminAuditLogService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contract-level tests for GET /admin/audit-logs — M9 Implementation Authorization §7/§14. */
@WebMvcTest(AdminAuditLogController.class)
@Import(SecurityConfig.class)
class AdminAuditLogControllerTest {

    private static final Long ADMIN_ID = 7L;

    @Autowired private MockMvc mockMvc;

    @MockBean private AdminAuditLogService adminAuditLogService;
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

    @Test
    void list_asAdmin_returns200() throws Exception {
        AuditLogResponse row = new AuditLogResponse(1L, 7L, "ADMIN_UPDATE_USER_STATUS", "users", 1L, "{}", null,
                Instant.parse("2026-09-13T12:00:00Z"));
        when(adminAuditLogService.list(any(), any(), any(), any(), eq(1), eq(20)))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/admin/audit-logs").with(asAdmin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].action").value("ADMIN_UPDATE_USER_STATUS"))
                .andExpect(jsonPath("$.meta.totalElements").value(1));
    }

    @Test
    void list_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-logs")).andExpect(status().isUnauthorized());
    }

    @Test
    void list_authenticatedNonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-logs").with(asUser(ADMIN_ID)))
                .andExpect(status().isForbidden());
    }
}

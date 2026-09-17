package com.dropfolio.admin.controller;

import com.dropfolio.admin.dto.DashboardResponse;
import com.dropfolio.admin.dto.LastSyncRef;
import com.dropfolio.admin.service.AdminDashboardService;
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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contract-level tests for GET /admin/dashboard — M9 Implementation Authorization §2/§14. */
@WebMvcTest(AdminDashboardController.class)
@Import(SecurityConfig.class)
class AdminDashboardControllerTest {

    private static final Long ADMIN_ID = 7L;

    @Autowired private MockMvc mockMvc;

    @MockBean private AdminDashboardService adminDashboardService;
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
    void getDashboard_asAdmin_returns200() throws Exception {
        when(adminDashboardService.getDashboard()).thenReturn(new DashboardResponse(
                1204L, 980L, 15420L,
                new LastSyncRef("SUCCESS", Instant.parse("2026-09-13T12:00:00Z"), 118),
                1L, "HEALTHY"));

        mockMvc.perform(get("/api/v1/admin/dashboard").with(asAdmin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalUsers").value(1204))
                .andExpect(jsonPath("$.data.priceProviderHealth").value("HEALTHY"))
                .andExpect(jsonPath("$.data.lastPriceSync.status").value("SUCCESS"));
    }

    @Test
    void getDashboard_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/dashboard")).andExpect(status().isUnauthorized());
    }

    @Test
    void getDashboard_authenticatedNonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/dashboard").with(asUser(ADMIN_ID)))
                .andExpect(status().isForbidden());
    }
}

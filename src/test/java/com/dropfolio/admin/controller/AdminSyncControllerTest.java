package com.dropfolio.admin.controller;

import com.dropfolio.admin.dto.SyncJobResponse;
import com.dropfolio.admin.dto.TriggerSyncResponse;
import com.dropfolio.admin.service.AdminSyncService;
import com.dropfolio.common.exception.SyncAlreadyRunningException;
import com.dropfolio.common.ratelimit.RateLimitProperties;
import com.dropfolio.common.security.CookieProperties;
import com.dropfolio.common.security.JwtService;
import com.dropfolio.common.security.SecurityConfig;
import com.dropfolio.common.security.TokenVersionProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract-level tests for the 3 locked admin sync-job endpoints — M7 Implementation
 * Authorization §4/§22: {@code 202}, {@code 409 SYNC_ALREADY_RUNNING}, {@code 401}, {@code 403}
 * (non-ADMIN authenticated user), and the two GET endpoints. Service layer mocked — this only
 * verifies HTTP status/envelope/authorization wiring, not business logic (covered by
 * {@code AdminSyncServiceTest}).
 */
@WebMvcTest(AdminSyncController.class)
@Import(SecurityConfig.class)
class AdminSyncControllerTest {

    private static final Long ADMIN_ID = 7L;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private AdminSyncService adminSyncService;
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

    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubRedisForRateLimitFilter() {
        ValueOperations<String, Object> valueOperations = org.mockito.Mockito.mock(ValueOperations.class);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.increment(anyString())).thenReturn(1L);
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

    // ---- POST /admin/sync-jobs/price-sync ----

    @Test
    void triggerPriceSync_asAdmin_returns202() throws Exception {
        when(adminSyncService.triggerPriceSync(eq(ADMIN_ID), any()))
                .thenReturn(new TriggerSyncResponse(100L, "RUNNING"));

        mockMvc.perform(post("/api/v1/admin/sync-jobs/price-sync").with(asAdmin(ADMIN_ID)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.syncJobId").value(100))
                .andExpect(jsonPath("$.data.status").value("RUNNING"));
    }

    @Test
    void triggerPriceSync_alreadyRunning_returns409() throws Exception {
        when(adminSyncService.triggerPriceSync(eq(ADMIN_ID), any()))
                .thenThrow(new SyncAlreadyRunningException("A price sync job is already running"));

        mockMvc.perform(post("/api/v1/admin/sync-jobs/price-sync").with(asAdmin(ADMIN_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.category").value("CONFLICT"))
                .andExpect(jsonPath("$.error.code").value("SYNC_ALREADY_RUNNING"));
    }

    @Test
    void triggerPriceSync_noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/sync-jobs/price-sync"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void triggerPriceSync_authenticatedNonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/admin/sync-jobs/price-sync").with(asUser(ADMIN_ID)))
                .andExpect(status().isForbidden());
    }

    // ---- GET /admin/sync-jobs/{id} ----

    @Test
    void getById_asAdmin_returns200() throws Exception {
        SyncJobResponse response = new SyncJobResponse(1L, "PRICE_SYNC", "SUCCESS", "SCHEDULER",
                null, 42, null, Instant.parse("2026-09-10T00:00:00Z"), Instant.parse("2026-09-10T00:05:00Z"));
        when(adminSyncService.getById(1L)).thenReturn(response);

        mockMvc.perform(get("/api/v1/admin/sync-jobs/1").with(asAdmin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.itemsProcessed").value(42));
    }

    @Test
    void getById_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/sync-jobs/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getById_authenticatedNonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/sync-jobs/1").with(asUser(ADMIN_ID)))
                .andExpect(status().isForbidden());
    }

    // ---- GET /admin/sync-jobs ----

    @Test
    void list_asAdmin_returns200WithPaginationMeta() throws Exception {
        SyncJobResponse response = new SyncJobResponse(1L, "PRICE_SYNC", "SUCCESS", "SCHEDULER",
                null, 42, null, Instant.parse("2026-09-10T00:00:00Z"), Instant.parse("2026-09-10T00:05:00Z"));
        when(adminSyncService.list(any(), any(), eq(1), eq(20), any()))
                .thenReturn(new PageImpl<>(List.of(response), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/admin/sync-jobs").with(asAdmin(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.meta.totalElements").value(1));
    }

    @Test
    void list_authenticatedNonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/sync-jobs").with(asUser(ADMIN_ID)))
                .andExpect(status().isForbidden());
    }
}

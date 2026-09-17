package com.dropfolio.notification.controller;

import com.dropfolio.common.ratelimit.RateLimitProperties;
import com.dropfolio.common.security.CookieProperties;
import com.dropfolio.common.security.JwtService;
import com.dropfolio.common.security.SecurityConfig;
import com.dropfolio.common.security.TokenVersionProvider;
import com.dropfolio.notification.dto.NotificationPreferencesResponse;
import com.dropfolio.notification.dto.NotificationResponse;
import com.dropfolio.notification.service.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract-level tests for the 5 locked notification endpoints — M8 Implementation
 * Authorization §8. Service layer mocked — verifies HTTP status/envelope/auth wiring only.
 */
@WebMvcTest(NotificationController.class)
@Import(SecurityConfig.class)
class NotificationControllerTest {

    private static final Long USER_ID = 7L;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private NotificationService notificationService;
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

    private NotificationResponse sampleResponse() {
        return new NotificationResponse(1L, "PRICE_ALERT", "t", "m", null, Instant.parse("2026-09-11T00:00:00Z"));
    }

    @Test
    void list_authenticated_returns200WithUnreadCountMeta() throws Exception {
        when(notificationService.list(eq(USER_ID), eq(false), eq(1), eq(20)))
                .thenReturn(new PageImpl<>(List.of(sampleResponse()), PageRequest.of(0, 20), 1));
        when(notificationService.unreadCount(USER_ID)).thenReturn(4L);

        mockMvc.perform(get("/api/v1/notifications").with(asUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.meta.unreadCount").value(4));
    }

    @Test
    void list_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
    }

    @Test
    void markRead_authenticated_returns204() throws Exception {
        mockMvc.perform(patch("/api/v1/notifications/1/read").with(asUser(USER_ID)))
                .andExpect(status().isNoContent());
    }

    @Test
    void markRead_noToken_returns401() throws Exception {
        mockMvc.perform(patch("/api/v1/notifications/1/read")).andExpect(status().isUnauthorized());
    }

    @Test
    void markAllRead_authenticated_returns204() throws Exception {
        mockMvc.perform(patch("/api/v1/notifications/read-all").with(asUser(USER_ID)))
                .andExpect(status().isNoContent());
    }

    @Test
    void getPreferences_authenticated_returns200() throws Exception {
        when(notificationService.getPreferences(USER_ID))
                .thenReturn(new NotificationPreferencesResponse(true, true));

        mockMvc.perform(get("/api/v1/notifications/preferences").with(asUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emailEnabled").value(true))
                .andExpect(jsonPath("$.data.inAppEnabled").value(true));
    }

    @Test
    void getPreferences_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/notifications/preferences")).andExpect(status().isUnauthorized());
    }

    @Test
    void updatePreferences_authenticated_returns200() throws Exception {
        when(notificationService.updatePreferences(eq(USER_ID), any()))
                .thenReturn(new NotificationPreferencesResponse(false, true));

        mockMvc.perform(patch("/api/v1/notifications/preferences").with(asUser(USER_ID))
                        .contentType("application/json")
                        .content("{\"emailEnabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emailEnabled").value(false));
    }
}

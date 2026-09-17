package com.dropfolio.alert.controller;

import com.dropfolio.alert.dto.AlertItemRef;
import com.dropfolio.alert.dto.AlertResponse;
import com.dropfolio.alert.service.AlertService;
import com.dropfolio.common.exception.OwnershipMismatchException;
import com.dropfolio.common.ratelimit.RateLimitProperties;
import com.dropfolio.common.security.CookieProperties;
import com.dropfolio.common.security.JwtService;
import com.dropfolio.common.security.SecurityConfig;
import com.dropfolio.common.security.TokenVersionProvider;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract-level tests for the 5 locked alert endpoints — M8 Implementation Authorization §7.
 * Every endpoint requires only authentication (no role restriction) — {@code SecurityConfig}'s
 * default {@code anyRequest().authenticated()} handles this, same as {@code DropController}.
 * Service layer mocked — this verifies HTTP status/envelope/auth wiring only.
 */
@WebMvcTest(AlertController.class)
@Import(SecurityConfig.class)
class AlertControllerTest {

    private static final Long USER_ID = 7L;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private AlertService alertService;
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

    private AlertResponse sampleResponse() {
        return new AlertResponse(1L, new AlertItemRef(5L, "Revolution Case"), new BigDecimal("5.00"),
                true, true, "ACTIVE", Instant.parse("2026-09-11T00:00:00Z"), null);
    }

    @Test
    void create_authenticated_returns201() throws Exception {
        when(alertService.create(eq(USER_ID), any())).thenReturn(sampleResponse());

        mockMvc.perform(post("/api/v1/alerts").with(asUser(USER_ID))
                        .contentType("application/json")
                        .content("{\"itemId\":5,\"targetPriceUsd\":5.00,\"notifyEmail\":true,\"notifyInApp\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.item.name").value("Revolution Case"));
    }

    @Test
    void create_noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/alerts")
                        .contentType("application/json")
                        .content("{\"itemId\":5,\"targetPriceUsd\":5.00}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_missingRequiredField_returns422() throws Exception {
        mockMvc.perform(post("/api/v1/alerts").with(asUser(USER_ID))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void getById_authenticated_returns200() throws Exception {
        when(alertService.getById(USER_ID, 1L)).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/alerts/1").with(asUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1));
    }

    @Test
    void getById_ownershipMismatch_returns404NotForbidden() throws Exception {
        when(alertService.getById(USER_ID, 1L)).thenThrow(new OwnershipMismatchException("Alert not found"));

        mockMvc.perform(get("/api/v1/alerts/1").with(asUser(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.category").value("NOT_FOUND"));
    }

    @Test
    void getById_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/alerts/1")).andExpect(status().isUnauthorized());
    }

    @Test
    void update_authenticated_returns200() throws Exception {
        when(alertService.update(eq(USER_ID), eq(1L), any())).thenReturn(sampleResponse());

        mockMvc.perform(patch("/api/v1/alerts/1").with(asUser(USER_ID))
                        .contentType("application/json")
                        .content("{\"targetPriceUsd\":9.99}"))
                .andExpect(status().isOk());
    }

    @Test
    void update_ownershipMismatch_returns404() throws Exception {
        when(alertService.update(eq(USER_ID), eq(1L), any())).thenThrow(new OwnershipMismatchException("Alert not found"));

        mockMvc.perform(patch("/api/v1/alerts/1").with(asUser(USER_ID))
                        .contentType("application/json")
                        .content("{\"targetPriceUsd\":9.99}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_authenticated_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/alerts/1").with(asUser(USER_ID)))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_ownershipMismatch_returns404() throws Exception {
        org.mockito.Mockito.doThrow(new OwnershipMismatchException("Alert not found"))
                .when(alertService).delete(USER_ID, 1L);

        mockMvc.perform(delete("/api/v1/alerts/1").with(asUser(USER_ID)))
                .andExpect(status().isNotFound());
    }

    @Test
    void list_authenticated_returns200WithPaginationMeta() throws Exception {
        when(alertService.list(eq(USER_ID), any(), eq(1), eq(20), any()))
                .thenReturn(new PageImpl<>(List.of(sampleResponse()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/alerts").with(asUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.meta.totalElements").value(1));
    }

    @Test
    void list_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/alerts")).andExpect(status().isUnauthorized());
    }
}

package com.dropfolio.drop.controller;

import com.dropfolio.common.exception.OwnershipMismatchException;
import com.dropfolio.common.exception.ResourceNotFoundException;
import com.dropfolio.common.ratelimit.RateLimitProperties;
import com.dropfolio.common.security.CookieProperties;
import com.dropfolio.common.security.JwtService;
import com.dropfolio.common.security.SecurityConfig;
import com.dropfolio.common.security.TokenVersionProvider;
import com.dropfolio.drop.dto.DropItemRef;
import com.dropfolio.drop.dto.DropResponse;
import com.dropfolio.drop.service.DropService;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract test level (TECHNICAL_SPEC.md §16.1/§16.2) — status codes for the 5 drop/ endpoints
 * (API_CONTRACT.md §5 v2.1), plus implicit {@code 401} (no token) and {@code 404} ownership
 * mismatch (never {@code 403} — §0.9). Service layer mocked; verifies HTTP status, envelope
 * shape, {@code Location} header on 201, and that every endpoint requires authentication.
 */
@WebMvcTest(DropController.class)
@Import(SecurityConfig.class)
class DropControllerTest {

    private static final Long USER_ID = 1L;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private DropService dropService;
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

    /** Mirrors what {@code JwtAuthenticationFilter} actually puts in the SecurityContext. */
    private static RequestPostProcessor asUser(Long userId) {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        return SecurityMockMvcRequestPostProcessors.authentication(auth);
    }

    private static DropResponse sampleResponse() {
        return new DropResponse(101L,
                new DropItemRef(5L, "Revolution Case", "CASE", "https://example.com/icon.png"),
                "MANUAL", 3, LocalDate.of(2026, 8, 25),
                new BigDecimal("0.48"), new BigDecimal("0.52"), true);
    }

    // ---- GET /drops ----

    @Test
    void list_authenticated_returns200WithPaginationMeta() throws Exception {
        when(dropService.list(eq(USER_ID), any(), any(), any(), any(), any(), any(), any(),
                eq(1), eq(20), any()))
                .thenReturn(new PageImpl<>(List.of(sampleResponse()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/drops").with(asUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].id").value(101))
                .andExpect(jsonPath("$.data[0].item.name").value("Revolution Case"))
                .andExpect(jsonPath("$.meta.page").value(1))
                .andExpect(jsonPath("$.meta.totalElements").value(1));
    }

    @Test
    void list_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/drops"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void list_invalidSort_returns422() throws Exception {
        when(dropService.list(eq(USER_ID), any(), any(), any(), any(), any(), any(), any(),
                eq(1), eq(20), eq("quantity,asc")))
                .thenThrow(new com.dropfolio.common.exception.ValidationException("sort field not allowed: quantity"));

        mockMvc.perform(get("/api/v1/drops").param("sort", "quantity,asc").with(asUser(USER_ID)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.category").value("VALIDATION_ERROR"));
    }

    // ---- GET /drops/{id} ----

    @Test
    void getById_authenticated_returns200() throws Exception {
        when(dropService.getById(USER_ID, 101L)).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/drops/101").with(asUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(101));
    }

    @Test
    void getById_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/drops/101"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getById_ownershipMismatch_returns404NotForbidden() throws Exception {
        when(dropService.getById(USER_ID, 999L)).thenThrow(new OwnershipMismatchException("Drop not found"));

        mockMvc.perform(get("/api/v1/drops/999").with(asUser(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.category").value("NOT_FOUND"));
    }

    // ---- POST /drops ----

    @Test
    void create_valid_returns201WithLocationHeader() throws Exception {
        when(dropService.create(anyLong(), any())).thenReturn(sampleResponse());

        String body = """
                { "itemId": 5, "quantity": 3, "acquisitionDate": "2026-08-25", "acquisitionValueUsd": 0.48 }
                """;

        mockMvc.perform(post("/api/v1/drops").with(asUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/drops/101"))
                .andExpect(jsonPath("$.data.source").value("MANUAL"));
    }

    @Test
    void create_noToken_returns401() throws Exception {
        String body = """
                { "itemId": 5, "quantity": 3, "acquisitionDate": "2026-08-25" }
                """;

        mockMvc.perform(post("/api/v1/drops")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_itemNotFoundOrInactive_returns404() throws Exception {
        when(dropService.create(anyLong(), any()))
                .thenThrow(new ResourceNotFoundException("Item not found or inactive"));

        String body = """
                { "itemId": 999, "quantity": 1, "acquisitionDate": "2026-08-25" }
                """;

        mockMvc.perform(post("/api/v1/drops").with(asUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void create_invalidQuantity_returns422() throws Exception {
        String body = """
                { "itemId": 5, "quantity": 0, "acquisitionDate": "2026-08-25" }
                """;

        mockMvc.perform(post("/api/v1/drops").with(asUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.category").value("VALIDATION_ERROR"));
    }

    @Test
    void create_futureAcquisitionDate_returns422() throws Exception {
        String body = """
                { "itemId": 5, "quantity": 1, "acquisitionDate": "2099-01-01" }
                """;

        mockMvc.perform(post("/api/v1/drops").with(asUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---- PATCH /drops/{id} ----

    @Test
    void update_valid_returns200() throws Exception {
        when(dropService.update(anyLong(), eq(101L), any())).thenReturn(sampleResponse());

        String body = """
                { "quantity": 5 }
                """;

        mockMvc.perform(patch("/api/v1/drops/101").with(asUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(101));
    }

    @Test
    void update_noToken_returns401() throws Exception {
        mockMvc.perform(patch("/api/v1/drops/101")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void update_ownershipMismatch_returns404NotForbidden() throws Exception {
        when(dropService.update(anyLong(), eq(999L), any()))
                .thenThrow(new OwnershipMismatchException("Drop not found"));

        mockMvc.perform(patch("/api/v1/drops/999").with(asUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.category").value("NOT_FOUND"));
    }

    @Test
    void update_invalidQuantity_returns422() throws Exception {
        String body = """
                { "quantity": -1 }
                """;

        mockMvc.perform(patch("/api/v1/drops/101").with(asUser(USER_ID))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---- DELETE /drops/{id} ----

    @Test
    void delete_valid_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/drops/101").with(asUser(USER_ID)))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_noToken_returns401() throws Exception {
        mockMvc.perform(delete("/api/v1/drops/101"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void delete_ownershipMismatch_returns404NotForbidden() throws Exception {
        org.mockito.Mockito.doThrow(new OwnershipMismatchException("Drop not found"))
                .when(dropService).delete(USER_ID, 999L);

        mockMvc.perform(delete("/api/v1/drops/999").with(asUser(USER_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.category").value("NOT_FOUND"));
    }
}

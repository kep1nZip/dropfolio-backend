package com.dropfolio.item.controller;

import com.dropfolio.common.exception.ItemMarketHashNameConflictException;
import com.dropfolio.common.exception.ResourceNotFoundException;
import com.dropfolio.common.ratelimit.RateLimitProperties;
import com.dropfolio.common.security.CookieProperties;
import com.dropfolio.common.security.SecurityConfig;
import com.dropfolio.common.security.JwtService;
import com.dropfolio.common.security.TokenVersionProvider;
import com.dropfolio.item.dto.CreateItemRequest;
import com.dropfolio.item.dto.ItemResponse;
import com.dropfolio.item.dto.UpdateItemRequest;
import com.dropfolio.item.service.ItemService;
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
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract test level (TECHNICAL_SPEC.md §16.1/§16.2) — status codes explicitly listed for the
 * 4 item/ endpoints in API_CONTRACT.md §4, plus the implicit 401/403 (§0.9.1) for the two
 * ADMIN-only endpoints. Service layer mocked; verifies HTTP status, envelope shape,
 * authorization enforcement, and the Location header on 201 — not business logic (covered by
 * ItemServiceTest).
 */
@WebMvcTest(ItemController.class)
@Import(SecurityConfig.class)
class ItemControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private ItemService itemService;
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

    // --- GET /items ---

    @Test
    void list_public_noAuthNeeded_returns200WithPaginationMeta() throws Exception {
        ItemResponse item = new ItemResponse(5L, "Revolution Case", "CASE", "Revolution Case", null, true);
        var page = new PageImpl<>(List.of(item), PageRequest.of(0, 20), 1);
        when(itemService.list(any(), any(), anyInt(), anyInt(), any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].name").value("Revolution Case"))
                .andExpect(jsonPath("$.meta.page").value(1))
                .andExpect(jsonPath("$.meta.totalElements").value(1));
    }

    // --- GET /items/{id} ---

    @Test
    void getById_found_returns200() throws Exception {
        when(itemService.getById(5L)).thenReturn(
                new ItemResponse(5L, "Revolution Case", "CASE", "Revolution Case", null, true));

        mockMvc.perform(get("/api/v1/items/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(5));
    }

    @Test
    void getById_notFound_returns404() throws Exception {
        when(itemService.getById(99L)).thenThrow(new ResourceNotFoundException("Item not found"));

        mockMvc.perform(get("/api/v1/items/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.category").value("NOT_FOUND"));
    }

    // --- POST /items (ADMIN only) ---

    @Test
    void create_withoutAuth_returns401() throws Exception {
        String body = objectMapper.writeValueAsString(
                new CreateItemRequest("Revolution Case", com.dropfolio.item.entity.ItemType.CASE, "Revolution Case", null));

        mockMvc.perform(post("/api/v1/items").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_authenticatedButNotAdmin_returns403() throws Exception {
        String body = objectMapper.writeValueAsString(
                new CreateItemRequest("Revolution Case", com.dropfolio.item.entity.ItemType.CASE, "Revolution Case", null));

        mockMvc.perform(post("/api/v1/items")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .user("1").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_asAdmin_returns201WithLocationHeader() throws Exception {
        String body = objectMapper.writeValueAsString(
                new CreateItemRequest("Revolution Case", com.dropfolio.item.entity.ItemType.CASE, "Revolution Case", null));
        when(itemService.create(any())).thenReturn(
                new ItemResponse(5L, "Revolution Case", "CASE", "Revolution Case", null, true));

        mockMvc.perform(post("/api/v1/items")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .user("1").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/items/5"))
                .andExpect(jsonPath("$.data.id").value(5));
    }

    @Test
    void create_duplicateMarketHashName_returns409WithGenericConflictCode() throws Exception {
        String body = objectMapper.writeValueAsString(
                new CreateItemRequest("Revolution Case", com.dropfolio.item.entity.ItemType.CASE, "Revolution Case", null));
        when(itemService.create(any())).thenThrow(new ItemMarketHashNameConflictException("already exists"));

        mockMvc.perform(post("/api/v1/items")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .user("1").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.category").value("CONFLICT"))
                .andExpect(jsonPath("$.error.code").value("CONFLICT")); // no domain-specific code exists — §0.6.2 fallback rule
    }

    @Test
    void create_invalidEnumValue_returns422NotFive_hundred() throws Exception {
        String badJson = """
                { "name": "X", "type": "WEAPON", "marketHashName": "X" }
                """;

        mockMvc.perform(post("/api/v1/items")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .user("1").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(badJson))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.category").value("VALIDATION_ERROR"));
    }

    // --- PATCH /items/{id} (ADMIN only) ---

    @Test
    void update_withoutAuth_returns401() throws Exception {
        String body = objectMapper.writeValueAsString(new UpdateItemRequest(null, null, false));

        mockMvc.perform(patch("/api/v1/items/5").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void update_asAdmin_returns200() throws Exception {
        String body = objectMapper.writeValueAsString(new UpdateItemRequest(null, null, false));
        when(itemService.update(org.mockito.ArgumentMatchers.eq(5L), any())).thenReturn(
                new ItemResponse(5L, "Revolution Case", "CASE", "Revolution Case", null, false));

        mockMvc.perform(patch("/api/v1/items/5")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .user("1").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isActive").value(false));
    }

    @Test
    void update_notFound_returns404() throws Exception {
        String body = objectMapper.writeValueAsString(new UpdateItemRequest(null, null, false));
        when(itemService.update(org.mockito.ArgumentMatchers.eq(99L), any()))
                .thenThrow(new ResourceNotFoundException("Item not found"));

        mockMvc.perform(patch("/api/v1/items/99")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .user("1").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }
}

package com.dropfolio.pricing.controller;

import com.dropfolio.common.exception.ResourceNotFoundException;
import com.dropfolio.common.exception.ValidationException;
import com.dropfolio.common.ratelimit.RateLimitProperties;
import com.dropfolio.common.security.CookieProperties;
import com.dropfolio.common.security.JwtService;
import com.dropfolio.common.security.SecurityConfig;
import com.dropfolio.common.security.TokenVersionProvider;
import com.dropfolio.pricing.dto.PriceHistoryPointResponse;
import com.dropfolio.pricing.dto.PriceHistoryResponse;
import com.dropfolio.pricing.service.PriceHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract test for {@code GET /api/v1/prices/{itemId}/history}. Same scaffolding as
 * {@link PriceControllerTest}: real {@link SecurityConfig} (so the public matcher is exercised —
 * no auth header is sent anywhere below), service mocked.
 */
@WebMvcTest(PriceHistoryController.class)
@Import(SecurityConfig.class)
class PriceHistoryControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private PriceHistoryService priceHistoryService;
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

    @Test
    void getHistory_public_withPoints_returns200AndContractShape() throws Exception {
        when(priceHistoryService.getHistory(4L, "30d", null)).thenReturn(new PriceHistoryResponse(
                4L, "STEAM_MARKET", "30d", List.of(
                new PriceHistoryPointResponse(new BigDecimal("34.1200"), Instant.parse("2026-09-01T10:00:00Z")),
                new PriceHistoryPointResponse(new BigDecimal("35.2000"), Instant.parse("2026-09-02T10:00:00Z")))));

        mockMvc.perform(get("/api/v1/prices/4/history?range=30d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.itemId").value(4))
                .andExpect(jsonPath("$.data.provider").value("STEAM_MARKET"))
                .andExpect(jsonPath("$.data.range").value("30d"))
                .andExpect(jsonPath("$.data.points", hasSize(2)))
                .andExpect(jsonPath("$.data.points[0].priceUsd").value(34.12))
                .andExpect(jsonPath("$.data.points[0].fetchedAt").exists())
                .andExpect(jsonPath("$.data.points[0].id").doesNotExist())
                .andExpect(jsonPath("$.data.points[0].createdAt").doesNotExist());
    }

    @Test
    void getHistory_emptyHistory_returns200WithEmptyPoints() throws Exception {
        when(priceHistoryService.getHistory(6L, "7d", null))
                .thenReturn(new PriceHistoryResponse(6L, null, "7d", List.of()));

        mockMvc.perform(get("/api/v1/prices/6/history?range=7d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.points", hasSize(0)))
                .andExpect(jsonPath("$.data.provider").value(nullValue()));
    }

    @Test
    void getHistory_rangeOmitted_defaultsTo30d_andLimitIsForwarded() throws Exception {
        when(priceHistoryService.getHistory(4L, "30d", 50))
                .thenReturn(new PriceHistoryResponse(4L, null, "30d", List.of()));

        mockMvc.perform(get("/api/v1/prices/4/history?limit=50"))
                .andExpect(status().isOk());

        verify(priceHistoryService).getHistory(4L, "30d", 50);
    }

    @Test
    void getHistory_itemNotInCatalog_returns404() throws Exception {
        when(priceHistoryService.getHistory(99L, "30d", null))
                .thenThrow(new ResourceNotFoundException("Item not found"));

        mockMvc.perform(get("/api/v1/prices/99/history"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.category").value("NOT_FOUND"));
    }

    @Test
    void getHistory_invalidRange_returns422ValidationError() throws Exception {
        when(priceHistoryService.getHistory(4L, "2w", null))
                .thenThrow(new ValidationException("range must be one of: 7d, 30d, 90d, 1y, all"));

        mockMvc.perform(get("/api/v1/prices/4/history?range=2w"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.category").value("VALIDATION_ERROR"));
    }

    @Test
    void getHistory_nonNumericLimit_returns422ValidationError() throws Exception {
        mockMvc.perform(get("/api/v1/prices/4/history?limit=abc"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.category").value("VALIDATION_ERROR"));
    }
}

package com.dropfolio.pricing.controller;

import com.dropfolio.common.exception.ResourceNotFoundException;
import com.dropfolio.common.ratelimit.RateLimitProperties;
import com.dropfolio.common.security.CookieProperties;
import com.dropfolio.common.security.JwtService;
import com.dropfolio.common.security.SecurityConfig;
import com.dropfolio.common.security.TokenVersionProvider;
import com.dropfolio.pricing.dto.PriceResponse;
import com.dropfolio.pricing.service.PricingService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract test level (same convention as ItemControllerTest) for
 * {@code GET /api/v1/prices/{itemId}} — API_CONTRACT.md §8. Service layer mocked; verifies
 * HTTP status, public access (no auth needed), and response shape.
 */
@WebMvcTest(PriceController.class)
@Import(SecurityConfig.class)
class PriceControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private PricingService pricingService;
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
    void getPrice_public_noAuthNeeded_priceAvailable_returns200() throws Exception {
        when(pricingService.getPrice(5L)).thenReturn(
                new PriceResponse(5L, new BigDecimal("0.52"), true, "STEAM_MARKET",
                        Instant.parse("2026-08-31T09:55:00Z")));

        mockMvc.perform(get("/api/v1/prices/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.itemId").value(5))
                .andExpect(jsonPath("$.data.priceUsd").value(0.52))
                .andExpect(jsonPath("$.data.priceAvailable").value(true))
                .andExpect(jsonPath("$.data.provider").value("STEAM_MARKET"));
    }

    @Test
    void getPrice_public_noAuthNeeded_priceUnavailable_returns200() throws Exception {
        when(pricingService.getPrice(6L)).thenReturn(
                new PriceResponse(6L, null, false, null, null));

        mockMvc.perform(get("/api/v1/prices/6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.itemId").value(6))
                .andExpect(jsonPath("$.data.priceUsd").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.priceAvailable").value(false))
                .andExpect(jsonPath("$.data.provider").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void getPrice_itemNotInCatalog_returns404() throws Exception {
        when(pricingService.getPrice(99L)).thenThrow(new ResourceNotFoundException("Item not found"));

        mockMvc.perform(get("/api/v1/prices/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.category").value("NOT_FOUND"));
    }
}

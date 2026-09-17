package com.dropfolio.portfolio.controller;

import com.dropfolio.common.exception.ValidationException;
import com.dropfolio.common.ratelimit.RateLimitProperties;
import com.dropfolio.common.security.CookieProperties;
import com.dropfolio.common.security.JwtService;
import com.dropfolio.common.security.SecurityConfig;
import com.dropfolio.common.security.TokenVersionProvider;
import com.dropfolio.portfolio.dto.HighestValueItemRef;
import com.dropfolio.portfolio.dto.LatestDropRef;
import com.dropfolio.portfolio.dto.PortfolioBreakdownItemResponse;
import com.dropfolio.portfolio.dto.PortfolioSummaryResponse;
import com.dropfolio.portfolio.dto.WeeklyDropStats;
import com.dropfolio.portfolio.service.PortfolioService;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract test level (TECHNICAL_SPEC.md §16.1/§16.2) for the 3 portfolio/ endpoints
 * (API_CONTRACT.md §7). No ownership-mismatch tests here — not applicable, see
 * MILESTONE_6_ASSESSMENT_REPORT.md §5 (no per-resource ID exists for any of these endpoints).
 */
@WebMvcTest(PortfolioController.class)
@Import(SecurityConfig.class)
class PortfolioControllerTest {

    private static final Long USER_ID = 1L;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private PortfolioService portfolioService;
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

    // ---- GET /portfolio/summary ----

    @Test
    void summary_authenticated_returns200() throws Exception {
        PortfolioSummaryResponse response = new PortfolioSummaryResponse(
                new BigDecimal("125.42"), 47,
                new HighestValueItemRef(5L, "Revolution Case", new BigDecimal("12.30")),
                new LatestDropRef(8L, "USP-S Skin", LocalDate.of(2026, 8, 30)),
                new WeeklyDropStats(1, 1, new BigDecimal("3.21")),
                2);
        when(portfolioService.getSummary(USER_ID)).thenReturn(response);

        mockMvc.perform(get("/api/v1/portfolio/summary").with(asUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalValueUsd").value(125.42))
                .andExpect(jsonPath("$.data.totalItems").value(47))
                .andExpect(jsonPath("$.data.highestValueItem.itemId").value(5))
                .andExpect(jsonPath("$.data.latestDrop.itemId").value(8))
                .andExpect(jsonPath("$.data.weeklyDrop.caseCount").value(1))
                .andExpect(jsonPath("$.data.itemsWithUnavailablePrice").value(2));
    }

    @Test
    void summary_emptyPortfolio_highestValueItemAndLatestDropAreNull() throws Exception {
        PortfolioSummaryResponse response = new PortfolioSummaryResponse(
                BigDecimal.ZERO, 0, null, null, new WeeklyDropStats(0, 0, BigDecimal.ZERO), 0);
        when(portfolioService.getSummary(USER_ID)).thenReturn(response);

        mockMvc.perform(get("/api/v1/portfolio/summary").with(asUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.highestValueItem").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.latestDrop").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void summary_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/portfolio/summary"))
                .andExpect(status().isUnauthorized());
    }

    // ---- GET /portfolio/breakdown ----

    @Test
    void breakdown_authenticated_returns200WithPaginationMeta() throws Exception {
        PortfolioBreakdownItemResponse row = new PortfolioBreakdownItemResponse(
                5L, "Revolution Case", "CASE", 10, new BigDecimal("0.52"), true, new BigDecimal("5.20"));
        when(portfolioService.getBreakdown(eq(USER_ID), any(), any(), eq(1), eq(20), any()))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/portfolio/breakdown").with(asUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].itemId").value(5))
                .andExpect(jsonPath("$.data[0].totalQuantity").value(10))
                .andExpect(jsonPath("$.meta.page").value(1))
                .andExpect(jsonPath("$.meta.totalElements").value(1));
    }

    @Test
    void breakdown_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/portfolio/breakdown"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void breakdown_invalidSort_returns422() throws Exception {
        when(portfolioService.getBreakdown(eq(USER_ID), any(), any(), eq(1), eq(20), eq("acquisitionDate,asc")))
                .thenThrow(new ValidationException("sort field not allowed: acquisitionDate"));

        mockMvc.perform(get("/api/v1/portfolio/breakdown")
                        .param("sort", "acquisitionDate,asc").with(asUser(USER_ID)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.category").value("VALIDATION_ERROR"));
    }

    @Test
    void breakdown_priceUnavailable_totalValueUsdIsNullInResponse() throws Exception {
        PortfolioBreakdownItemResponse row = new PortfolioBreakdownItemResponse(
                6L, "Unpriced Skin", "SKIN", 3, null, false, null);
        when(portfolioService.getBreakdown(eq(USER_ID), any(), any(), eq(1), eq(20), any()))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/portfolio/breakdown").with(asUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].priceAvailable").value(false))
                .andExpect(jsonPath("$.data[0].totalValueUsd").value(org.hamcrest.Matchers.nullValue()));
    }

    // ---- GET /portfolio/export ----

    @Test
    void export_withData_returns200WithCsvContentType() throws Exception {
        String csv = "Item,Type,Acquired Date,Quantity,Acquisition Price,Current Price,Current Value\r\n"
                + "Revolution Case,CASE,2026-08-25,3,0.48,0.52,1.56\r\n";
        when(portfolioService.exportCsv(USER_ID)).thenReturn(Optional.of(csv));

        mockMvc.perform(get("/api/v1/portfolio/export").with(asUser(USER_ID)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"dropfolio-export.csv\""));
    }

    @Test
    void export_noData_returns204() throws Exception {
        when(portfolioService.exportCsv(USER_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/portfolio/export").with(asUser(USER_ID)))
                .andExpect(status().isNoContent());
    }

    @Test
    void export_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/portfolio/export"))
                .andExpect(status().isUnauthorized());
    }
}

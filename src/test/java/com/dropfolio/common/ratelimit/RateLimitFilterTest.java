package com.dropfolio.common.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private FilterChain filterChain;

    private ObjectMapper objectMapper;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        filter = new RateLimitFilter(
                redisTemplate,
                objectMapper,
                "login",
                "POST",
                "/api/v1/auth/login",
                900,
                10
        );
    }

    @Test
    void nonMatchingPath_passesThroughWithoutTouchingRedis() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/v1/auth/register");
        request.setServletPath("/api/v1/auth/register");

        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(redisTemplate, never()).opsForValue();
        verify(valueOperations, never())
                .increment(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void underLimit_incrementsCounter_setsExpiryOnFirstHit_andPassesThrough() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setServletPath("/api/v1/auth/login");
        request.setRemoteAddr("203.0.113.7");

        MockHttpServletResponse response = new MockHttpServletResponse();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("ratelimit:login:203.0.113.7"))
                .thenReturn(1L);

        filter.doFilter(request, response, filterChain);

        verify(valueOperations)
                .increment("ratelimit:login:203.0.113.7");

        verify(redisTemplate)
                .expire(
                        "ratelimit:login:203.0.113.7",
                        Duration.ofSeconds(900)
                );

        verify(filterChain).doFilter(request, response);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void overLimit_returns429WithRetryAfter_andDoesNotCallChain() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setServletPath("/api/v1/auth/login");
        request.setRemoteAddr("203.0.113.7");

        MockHttpServletResponse response = new MockHttpServletResponse();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("ratelimit:login:203.0.113.7"))
                .thenReturn(11L);

        when(redisTemplate.getExpire("ratelimit:login:203.0.113.7"))
                .thenReturn(45L);

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("45");
        assertThat(response.getContentAsString())
                .contains("RATE_LIMITED");

        verify(filterChain, never())
                .doFilter(request, response);
    }

    @Test
    void identifiesClientByXForwardedFor_whenPresent() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setServletPath("/api/v1/auth/login");
        request.setRemoteAddr("10.0.0.1");

        request.addHeader(
                "X-Forwarded-For",
                "198.51.100.23, 10.0.0.1"
        );

        MockHttpServletResponse response = new MockHttpServletResponse();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("ratelimit:login:198.51.100.23"))
                .thenReturn(1L);

        filter.doFilter(request, response, filterChain);

        verify(valueOperations)
                .increment("ratelimit:login:198.51.100.23");
    }
}
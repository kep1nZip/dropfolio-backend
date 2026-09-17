package com.dropfolio.common.ratelimit;

import com.dropfolio.common.cache.CacheKeys;
import com.dropfolio.common.envelope.ApiError;
import com.dropfolio.common.envelope.ApiErrorResponse;
import com.dropfolio.common.envelope.ErrorCategory;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Fixed-window rate limit counter in Redis — TECHNICAL_SPEC.md §13.
 *
 * One instance = one (scope, path, limit) triple, e.g. one for POST /auth/login (scope
 * "login") and a separate one for POST /auth/register (scope "register") — §13 mandates these
 * are checked at filter level (public, IP-based, before authentication), each with its own
 * window/limit from API_CONTRACT.md §0.13. Requests to any other path pass through untouched
 * ({@link #shouldNotFilter}), so multiple instances can be chained in SecurityConfig without
 * interfering with each other or with authenticated endpoints.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final String bucket;
    private final String matchPath;
    private final String matchMethod;
    private final int windowSeconds;
    private final int maxRequests;

    public RateLimitFilter(RedisTemplate<String, Object> redisTemplate, ObjectMapper objectMapper,
                            String bucket, String matchMethod, String matchPath,
                            int windowSeconds, int maxRequests) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.bucket = bucket;
        this.matchMethod = matchMethod;
        this.matchPath = matchPath;
        this.windowSeconds = windowSeconds;
        this.maxRequests = maxRequests;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(matchMethod.equalsIgnoreCase(request.getMethod()) && matchPath.equals(request.getServletPath()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String identifier = clientIp(request);
        String key = CacheKeys.rateLimit(bucket, identifier);

        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, Duration.ofSeconds(windowSeconds));
        }

        if (count != null && count > maxRequests) {
            Long ttl = redisTemplate.getExpire(key);
            long retryAfter = ttl != null && ttl > 0 ? ttl : windowSeconds;
            response.setStatus(ErrorCategory.RATE_LIMITED.httpStatus().value());
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            response.setContentType("application/json");
            var body = ApiErrorResponse.of(ApiError.of(ErrorCategory.RATE_LIMITED,
                    "Too many requests. Try again in " + retryAfter + " seconds."));
            response.getWriter().write(objectMapper.writeValueAsString(body));
            return;
        }

        chain.doFilter(request, response);
    }

    /** X-Forwarded-For aware — relevant behind a reverse proxy/load balancer in Azure. */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

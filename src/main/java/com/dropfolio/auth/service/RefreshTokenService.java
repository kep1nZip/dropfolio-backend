package com.dropfolio.auth.service;

import com.dropfolio.common.cache.CacheKeys;
import com.dropfolio.common.exception.InvalidRefreshTokenException;
import com.dropfolio.common.exception.UpstreamUnavailableException;
import com.dropfolio.common.security.JwtService;
import com.dropfolio.user.repository.UserRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.jsonwebtoken.JwtException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Set;

/**
 * Refresh token lifecycle — CLAUDE_CONTEXT.md §6.1 / TECHNICAL_SPEC.md §6.1 (Redis key
 * {@code auth:refresh:{userId}:{tokenFamilyId}}, value = current valid {@code jti}, TTL 30 days).
 *
 * Rotation model:
 * - Every successful refresh issues a NEW jti and overwrites the Redis value (same key, same
 *   family, TTL reset to the full refresh TTL — a "rolling" session as long as it's actively used).
 * - If the jti presented doesn't match what's stored (i.e. an already-rotated-away token is
 *   reused — classic replay of a stolen refresh token), the ENTIRE session is revoked: the
 *   Redis key is deleted AND {@code token_version} is bumped so every outstanding access token
 *   is invalidated too (CLAUDE_CONTEXT.md §6.1: "reuse token lama → revoke SELURUH sesi user").
 * - If the Redis key is simply absent (expired, or already logged out), that's a plain invalid
 *   token — no reuse implied, just reject.
 *
 * Wrapped with the {@code redisCache} circuit breaker (TECHNICAL_SPEC.md §7) — refresh tokens
 * have no DB fallback by design (§6.1 table), so a Redis outage during login/refresh surfaces
 * as 503 UPSTREAM_UNAVAILABLE rather than a raw 500 or a silent security bypass.
 */
@Service
public class RefreshTokenService {

    private static final Duration REFRESH_TTL = Duration.ofDays(30);

    private final RedisTemplate<String, Object> redisTemplate;
    private final JwtService jwtService;
    private final UserRepository userRepository;

    public RefreshTokenService(RedisTemplate<String, Object> redisTemplate, JwtService jwtService,
                                UserRepository userRepository) {
        this.redisTemplate = redisTemplate;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    /** Issues a brand new refresh token (fresh login) — new familyId every time. */
    @CircuitBreaker(name = "redisCache", fallbackMethod = "issueFallback")
    public String issue(Long userId) {
        String familyId = jwtService.newFamilyId();
        String jti = jwtService.newJti();
        String key = CacheKeys.refreshToken(userId, familyId);
        redisTemplate.opsForValue().set(key, jti, REFRESH_TTL);
        return jwtService.issueRefreshToken(userId, familyId, jti);
    }

    @SuppressWarnings("unused")
    private String issueFallback(Long userId, Throwable t) {
        throw redisFailure(t);
    }

    /**
     * Validates + rotates a presented refresh token JWT.
     *
     * @return the new refresh token JWT (same family, new jti) plus the userId it belongs to —
     *         caller (AuthService) uses the userId to re-derive a fresh access token, since
     *         that requires a current DB read of roles/status that doesn't belong in this
     *         Redis-focused service.
     * @throws InvalidRefreshTokenException if the token is malformed/expired, the session no
     *                                       longer exists, or reuse was detected (in which case
     *                                       the whole session is revoked as a side effect before
     *                                       this is thrown).
     */
    @Transactional
    @CircuitBreaker(name = "redisCache", fallbackMethod = "rotateFallback")
    public RotationResult rotate(String presentedRefreshTokenJwt) {
        JwtService.ParsedRefreshToken parsed;
        try {
            parsed = jwtService.parseRefreshToken(presentedRefreshTokenJwt);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new InvalidRefreshTokenException("Refresh token invalid or expired");
        }

        String key = CacheKeys.refreshToken(parsed.userId(), parsed.familyId());
        Object storedJti = redisTemplate.opsForValue().get(key);

        if (storedJti == null) {
            throw new InvalidRefreshTokenException("Refresh session no longer exists");
        }

        if (!storedJti.toString().equals(parsed.jti())) {
            // Reuse of an already-rotated-away token — treat as compromise, nuke the session.
            revokeAll(parsed.userId());
            throw new InvalidRefreshTokenException("Refresh token reuse detected — all sessions revoked");
        }

        String newJti = jwtService.newJti();
        redisTemplate.opsForValue().set(key, newJti, REFRESH_TTL);
        String newToken = jwtService.issueRefreshToken(parsed.userId(), parsed.familyId(), newJti);
        return new RotationResult(parsed.userId(), newToken);
    }

    @SuppressWarnings("unused")
    private RotationResult rotateFallback(String presentedRefreshTokenJwt, Throwable t) {
        if (t instanceof InvalidRefreshTokenException e) {
            throw e;
        }
        throw redisFailure(t);
    }

    public record RotationResult(Long userId, String refreshTokenJwt) {
    }

    /** Revokes only the session tied to this specific refresh token (single-device logout). */
    @CircuitBreaker(name = "redisCache", fallbackMethod = "revokeFallback")
    public void revoke(String presentedRefreshTokenJwt) {
        if (presentedRefreshTokenJwt == null || presentedRefreshTokenJwt.isBlank()) {
            return; // nothing to revoke — logout stays idempotent
        }
        try {
            JwtService.ParsedRefreshToken parsed = jwtService.parseRefreshToken(presentedRefreshTokenJwt);
            redisTemplate.delete(CacheKeys.refreshToken(parsed.userId(), parsed.familyId()));
        } catch (JwtException | IllegalArgumentException ex) {
            // Already invalid/expired — nothing meaningful to revoke, logout still succeeds.
        }
    }

    @SuppressWarnings("unused")
    private void revokeFallback(String presentedRefreshTokenJwt, Throwable t) {
        // Best-effort: if Redis is down we can't clear server-side state, but logout must not
        // hard-fail the user out of clearing their own cookie client-side. No token_version
        // bump happens here anyway (single-device logout, CLAUDE_CONTEXT.md §6.1), so silently
        // no-op is safe — the token will simply expire naturally at its TTL.
    }

    /**
     * Revokes every refresh session of a user (logout-all-devices, reuse-detection response,
     * password change) AND bumps {@code token_version} so outstanding access tokens die too.
     */
    @Transactional
    public void revokeAll(Long userId) {
        Set<String> keys = redisTemplate.keys(CacheKeys.refreshTokenUserPattern(userId));
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        userRepository.findById(userId).ifPresent(u -> {
            u.setTokenVersion(u.getTokenVersion() + 1);
            userRepository.save(u);
        });
    }

    private UpstreamUnavailableException redisFailure(Throwable t) {
        if (t instanceof RedisConnectionFailureException || t.getCause() instanceof RedisConnectionFailureException) {
            return new UpstreamUnavailableException("Session storage temporarily unavailable");
        }
        return new UpstreamUnavailableException("Session storage temporarily unavailable");
    }
}

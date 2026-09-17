package com.dropfolio.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Central JWT issuance/parsing — SYSTEM_ARCHITECTURE.md §3.3 / CLAUDE_CONTEXT.md §6.1.
 *
 * Two distinct token shapes, both HS256, both signed with the same secret:
 * - Access token: {@code sub=userId, roles=[...], tokenVersion, iat, exp} — short TTL.
 * - Refresh token: {@code sub=userId, familyId, jti, iat, exp} — long TTL, opaque to the
 *   client, never carries roles/tokenVersion (rotation/reuse-detection state lives in Redis,
 *   see auth/service/RefreshTokenService, not in the token's own claims).
 *
 * Kept in {@code common/security} (not {@code auth/}) because {@link JwtAuthenticationFilter}
 * — a cross-cutting filter with no business-module dependency — also needs to parse access
 * tokens.
 */
@Component
public class JwtService {

    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_TOKEN_VERSION = "tokenVersion";
    private static final String CLAIM_FAMILY_ID = "familyId";
    private static final String TOKEN_TYPE_CLAIM = "typ";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final JwtProperties props;

    public JwtService(JwtProperties props) {
        this.props = props;
        this.key = Keys.hmacShaKeyFor(props.secret().getBytes());
    }

    public String issueAccessToken(Long userId, List<String> roles, int tokenVersion) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_ROLES, roles)
                .claim(CLAIM_TOKEN_VERSION, tokenVersion)
                .claim(TOKEN_TYPE_CLAIM, TYPE_ACCESS)
                .issuedAt(java.util.Date.from(now))
                .expiration(java.util.Date.from(now.plus(props.accessTokenTtlSeconds(), ChronoUnit.SECONDS)))
                .signWith(key)
                .compact();
    }

    /**
     * @param familyId stable across rotations within one login session; new familyId only on
     *                 a fresh login. jti changes every rotation (used for reuse detection —
     *                 see RefreshTokenService).
     */
    public String issueRefreshToken(Long userId, String familyId, String jti) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_FAMILY_ID, familyId)
                .id(jti)
                .claim(TOKEN_TYPE_CLAIM, TYPE_REFRESH)
                .issuedAt(java.util.Date.from(now))
                .expiration(java.util.Date.from(now.plus(props.refreshTokenTtlSeconds(), ChronoUnit.SECONDS)))
                .signWith(key)
                .compact();
    }

    public String newFamilyId() {
        return UUID.randomUUID().toString();
    }

    public String newJti() {
        return UUID.randomUUID().toString();
    }

    /** @throws JwtException if signature invalid, expired, or not an access token. */
    public ParsedAccessToken parseAccessToken(String token) {
        Claims claims = parseClaims(token);
        if (!TYPE_ACCESS.equals(claims.get(TOKEN_TYPE_CLAIM, String.class))) {
            throw new JwtException("Not an access token");
        }
        @SuppressWarnings("unchecked")
        List<String> roles = claims.get(CLAIM_ROLES, List.class);
        Integer tokenVersion = claims.get(CLAIM_TOKEN_VERSION, Integer.class);
        return new ParsedAccessToken(Long.valueOf(claims.getSubject()), roles, tokenVersion);
    }

    /** @throws JwtException if signature invalid, expired, or not a refresh token. */
    public ParsedRefreshToken parseRefreshToken(String token) {
        Claims claims = parseClaims(token);
        if (!TYPE_REFRESH.equals(claims.get(TOKEN_TYPE_CLAIM, String.class))) {
            throw new JwtException("Not a refresh token");
        }
        String familyId = claims.get(CLAIM_FAMILY_ID, String.class);
        return new ParsedRefreshToken(Long.valueOf(claims.getSubject()), familyId, claims.getId());
    }

    private Claims parseClaims(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    public record ParsedAccessToken(Long userId, List<String> roles, Integer tokenVersion) {}

    public record ParsedRefreshToken(Long userId, String familyId, String jti) {}
}

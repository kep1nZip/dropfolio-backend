package com.dropfolio.common.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Verifies JWT access token (signature + expiry + tokenVersion) and populates
 * SecurityContext. Per TECHNICAL_SPEC.md §4: JWT never appears in URL/query string — only
 * the Authorization header (Bearer scheme) is read here.
 *
 * tokenVersion check (SYSTEM_ARCHITECTURE.md §3.3 instant-revoke mechanism) is delegated to
 * {@link TokenVersionProvider} — implemented by user/ module, injected here via interface so
 * common/ stays free of module dependencies (CLAUDE_CONTEXT.md §3).
 *
 * A missing/invalid/stale token does not throw — the request proceeds unauthenticated, and
 * Spring Security's authorization rules (SecurityConfig) reject it with 401 at the
 * endpoint-matcher level, surfaced via RestAuthenticationEntryPoint's standard error envelope.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final TokenVersionProvider tokenVersionProvider;

    public JwtAuthenticationFilter(JwtService jwtService, TokenVersionProvider tokenVersionProvider) {
        this.jwtService = jwtService;
        this.tokenVersionProvider = tokenVersionProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                JwtService.ParsedAccessToken parsed = jwtService.parseAccessToken(token);
                Optional<Integer> currentVersion = tokenVersionProvider.currentTokenVersion(parsed.userId());
                if (currentVersion.isPresent() && currentVersion.get().equals(parsed.tokenVersion())) {
                    List<SimpleGrantedAuthority> authorities = parsed.roles().stream()
                            .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                            .toList();
                    var auth = new UsernamePasswordAuthenticationToken(parsed.userId(), token, authorities);
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
                // else: user gone/deleted OR tokenVersion stale (revoked via logout-all /
                // password change / refresh-reuse detection) → stay anonymous, same as an
                // invalid token. This is the instant-revoke mechanism from §3.3.
            } catch (JwtException | IllegalArgumentException ex) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}

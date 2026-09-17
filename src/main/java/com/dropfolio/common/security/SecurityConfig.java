package com.dropfolio.common.security;

import com.dropfolio.common.ratelimit.RateLimitFilter;
import com.dropfolio.common.ratelimit.RateLimitProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.List;

/**
 * Stateless JWT-based security filter chain — TECHNICAL_SPEC.md §4.
 *
 * MILESTONE-2 UPDATE: wires the real {@link JwtAuthenticationFilter} (needs {@link JwtService}
 * + {@link TokenVersionProvider}, the latter implemented by {@code user/security}), the two
 * public-scope {@link RateLimitFilter} instances for {@code POST /auth/login} and
 * {@code POST /auth/register} (§13 TECHNICAL_SPEC — checked before authentication, IP-based),
 * and the first concrete path matchers for the 4 auth/ endp oints landing this milestone.
 * Matchers for endpoints outside this milestone's scope (items, drops, admin, ...) are
 * intentionally NOT added yet — they land with their own controller per module, per
 * CLAUDE_CONTEXT.md §12 sequencing. Default posture for any other {@code /api/v1/**} path
 * remains "authenticated" (safe default) until its module wires its own matcher.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(CorsProperties.class)
public class SecurityConfig {

    private final ObjectMapper objectMapper;
    private final JwtService jwtService;
    private final TokenVersionProvider tokenVersionProvider;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RateLimitProperties rateLimitProperties;
    private final CorsProperties corsProperties;

    public SecurityConfig(ObjectMapper objectMapper, JwtService jwtService,
                           TokenVersionProvider tokenVersionProvider,
                           RedisTemplate<String, Object> redisTemplate,
                           RateLimitProperties rateLimitProperties,
                           CorsProperties corsProperties) {
        this.objectMapper = objectMapper;
        this.jwtService = jwtService;
        this.tokenVersionProvider = tokenVersionProvider;
        this.redisTemplate = redisTemplate;
        this.rateLimitProperties = rateLimitProperties;
        this.corsProperties = corsProperties;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * M10 — SYSTEM_ARCHITECTURE.md §10. One exact origin from {@link CorsProperties}, never a
     * wildcard. Allows exactly the methods this API actually uses and the two headers a JWT
     * bearer + JSON client needs ({@code Authorization}, {@code Content-Type}) —
     * {@code allowCredentials(true)} is required for the browser to send the
     * {@code dropfolio_rt} cookie cross-origin, which is precisely why a wildcard origin is
     * disallowed by the browser spec itself when credentials are involved, not just a
     * project-policy choice.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(corsProperties.allowedOrigin()));
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        RateLimitFilter loginRateLimit = new RateLimitFilter(redisTemplate, objectMapper, "login",
                "POST", "/api/v1/auth/login",
                rateLimitProperties.login().windowSeconds(), rateLimitProperties.login().maxRequests());
        RateLimitFilter registerRateLimit = new RateLimitFilter(redisTemplate, objectMapper, "register",
                "POST", "/api/v1/auth/register",
                rateLimitProperties.register().windowSeconds(), rateLimitProperties.register().maxRequests());

        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(new RestAuthenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(new RestAccessDeniedHandler(objectMapper)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()
                                .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                                .requestMatchers("/api/v1/auth/logout").authenticated()
                                // Domain: Items (Catalog) — API_CONTRACT.md §4. GET public, POST/PATCH ADMIN-only.
                                .requestMatchers(HttpMethod.GET, "/api/v1/items", "/api/v1/items/*").permitAll()
                                .requestMatchers(HttpMethod.POST, "/api/v1/items").hasRole("ADMIN")
                                .requestMatchers(HttpMethod.PATCH, "/api/v1/items/*").hasRole("ADMIN")
                                // Domain: Prices — API_CONTRACT.md §8. GET /prices/{itemId} is PUBLIC (PM Decision).
                                .requestMatchers(HttpMethod.GET, "/api/v1/prices/*").permitAll()
                                // Domain: Admin sync-jobs (M7) — API_CONTRACT.md §11. EXACTLY these 3 endpoints
                                // (M7 Implementation Authorization §4); no other /admin/** matcher is added here.
                                .requestMatchers(HttpMethod.POST, "/api/v1/admin/sync-jobs/price-sync").hasRole("ADMIN")
                                .requestMatchers(HttpMethod.GET, "/api/v1/admin/sync-jobs", "/api/v1/admin/sync-jobs/*").hasRole("ADMIN")
                                // M9 — Admin Panel Completion (API_CONTRACT.md §11), additive, same pattern as M7's 3 matchers above.
                                .requestMatchers(HttpMethod.GET, "/api/v1/admin/dashboard").hasRole("ADMIN")
                                .requestMatchers(HttpMethod.GET, "/api/v1/admin/users", "/api/v1/admin/users/*").hasRole("ADMIN")
                                .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/users/*/status").hasRole("ADMIN")
                                .requestMatchers(HttpMethod.GET, "/api/v1/admin/audit-logs").hasRole("ADMIN")
                                .anyRequest().authenticated())
                .addFilterBefore(loginRateLimit, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(registerRateLimit, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new JwtAuthenticationFilter(jwtService, tokenVersionProvider),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}

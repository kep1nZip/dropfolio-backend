package com.dropfolio.auth.controller;

import com.dropfolio.auth.dto.LoginRequest;
import com.dropfolio.auth.dto.LoginResponse;
import com.dropfolio.auth.dto.RegisterRequest;
import com.dropfolio.auth.dto.RegisterResponse;
import com.dropfolio.auth.dto.UserSummary;
import com.dropfolio.auth.service.AuthService;
import com.dropfolio.auth.service.RefreshTokenService;
import com.dropfolio.common.exception.AccountDeactivatedException;
import com.dropfolio.common.exception.EmailAlreadyRegisteredException;
import com.dropfolio.common.exception.InvalidCredentialsException;
import com.dropfolio.common.exception.InvalidRefreshTokenException;
import com.dropfolio.common.ratelimit.RateLimitProperties;
import com.dropfolio.common.security.CookieProperties;
import com.dropfolio.common.security.JwtService;
import com.dropfolio.common.security.SecurityConfig;
import com.dropfolio.common.security.TokenVersionProvider;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract test level (TECHNICAL_SPEC.md §16.1/§16.2) — every status code explicitly listed
 * for the 4 auth/ endpoints in API_CONTRACT.md §1. Service layer is mocked (@MockBean); this
 * verifies HTTP status, envelope shape, and cookie behavior, not business logic (covered by
 * AuthServiceTest / RefreshTokenServiceTest).
 *
 * {@code @Import(SecurityConfig.class)} is required — plain {@code @Configuration} classes are
 * NOT auto-included by the {@code @WebMvcTest} slice by default, and without the real filter
 * chain the {@code logout_withoutAuth_returns401} assertion would be meaningless (nothing would
 * actually enforce authentication).
 */
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private AuthService authService;
    @MockBean private RefreshTokenService refreshTokenService;
    @MockBean private CookieProperties cookieProperties;
    @MockBean private JwtService jwtService;
    @MockBean private TokenVersionProvider tokenVersionProvider;
    @MockBean private RedisTemplate<String, Object> redisTemplate;

    // RateLimitProperties is a plain record read directly (rateLimitProperties.login().windowSeconds())
    // by SecurityConfig.filterChain() during context startup — an un-stubbed @MockBean would
    // NPE at that point (no when() has run yet), so a real instance is supplied instead.
    @TestConfiguration
    static class RateLimitPropertiesTestConfig {
        @Bean
        RateLimitProperties rateLimitProperties() {
            return new RateLimitProperties(60, 60,
                    new RateLimitProperties.Scope(900, 10),
                    new RateLimitProperties.Scope(3600, 5));
        }
    }

    /**
     * RateLimitFilter runs in front of /auth/register and /auth/login (real filter chain,
     * imported above) and unconditionally calls redisTemplate.opsForValue() — stub it to stay
     * safely under every scope's limit so these HTTP-status/envelope-shape tests aren't
     * incidentally flaky against rate limiting (that behavior has its own dedicated test).
     */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void stubRedisForRateLimitFilter() {
        ValueOperations<String, Object> valueOperations = org.mockito.Mockito.mock(ValueOperations.class);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.increment(org.mockito.ArgumentMatchers.anyString())).thenReturn(1L);
    }

    // --- POST /auth/register ---

    @Test
    void register_validBody_returns201WithEnvelope() throws Exception {
        RegisterRequest req = new RegisterRequest("new@example.com", "Passw0rd1", "Budi");
        when(authService.register(any())).thenReturn(new RegisterResponse(42L, "new@example.com", "Budi"));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value(42))
                .andExpect(jsonPath("$.data.email").value("new@example.com"));
    }

    @Test
    void register_duplicateEmail_returns409Conflict() throws Exception {
        RegisterRequest req = new RegisterRequest("taken@example.com", "Passw0rd1", "Budi");
        when(authService.register(any())).thenThrow(new EmailAlreadyRegisteredException("Email already registered"));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.category").value("CONFLICT"))
                .andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void register_invalidBody_returns422ValidationError() throws Exception {
        // password too short and missing digit, displayName blank, email malformed.
        String badJson = """
                { "email": "not-an-email", "password": "abc", "displayName": "" }
                """;

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badJson))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.category").value("VALIDATION_ERROR"));
    }

    // --- POST /auth/login ---

    @Test
    void login_validCredentials_returns200_andSetsRefreshCookie() throws Exception {
        LoginRequest req = new LoginRequest("user@example.com", "Passw0rd1");
        LoginResponse body = new LoginResponse("access.jwt", 900, new UserSummary(1L, "Budi", List.of("USER")));
        when(authService.login(any())).thenReturn(new AuthService.LoginResult(body, "refresh.jwt"));
        when(cookieProperties.secure()).thenReturn(true);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("access.jwt"))
                .andExpect(jsonPath("$.data.expiresIn").value(900))
                .andExpect(jsonPath("$.data.user.roles[0]").value("USER"))
                .andExpect(cookie().exists("dropfolio_rt"))
                .andExpect(cookie().httpOnly("dropfolio_rt", true));
    }

    @Test
    void login_invalidCredentials_returns401() throws Exception {
        LoginRequest req = new LoginRequest("user@example.com", "wrongpass1");
        when(authService.login(any())).thenThrow(new InvalidCredentialsException("Invalid email or password"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void login_deactivatedAccount_returns401WithAccountDeactivatedCode() throws Exception {
        LoginRequest req = new LoginRequest("user@example.com", "Passw0rd1");
        when(authService.login(any())).thenThrow(new AccountDeactivatedException("Account has been deactivated"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_DEACTIVATED"));
    }

    @Test
    void login_invalidBody_returns422() throws Exception {
        String badJson = """
                { "email": "not-an-email", "password": "" }
                """;

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badJson))
                .andExpect(status().isUnprocessableEntity());
    }

    // --- POST /auth/refresh ---

    @Test
    void refresh_validCookie_returns200_andRotatesCookie() throws Exception {
        LoginResponse body = new LoginResponse("new.access.jwt", 900, new UserSummary(1L, "Budi", List.of("USER")));
        when(authService.refresh(any())).thenReturn(new AuthService.LoginResult(body, "new.refresh.jwt"));
        when(cookieProperties.secure()).thenReturn(true);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("dropfolio_rt", "old.refresh.jwt")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("new.access.jwt"))
                .andExpect(cookie().value("dropfolio_rt", "new.refresh.jwt"));
    }

    @Test
    void refresh_invalidOrMissingCookie_returns401() throws Exception {
        when(authService.refresh(any())).thenThrow(new InvalidRefreshTokenException("Refresh token invalid or expired"));

        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));
    }

    // --- POST /auth/logout ---

    @Test
    void logout_returns204_andClearsCookie() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .user("1").roles("USER"))
                        .cookie(new jakarta.servlet.http.Cookie("dropfolio_rt", "device-a.jwt")))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("dropfolio_rt", 0));
    }

    @Test
    void logout_withoutAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isUnauthorized());
    }
}

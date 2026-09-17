package com.dropfolio.auth.service;

import com.dropfolio.common.exception.InvalidRefreshTokenException;
import com.dropfolio.common.security.JwtService;
import com.dropfolio.user.entity.User;
import com.dropfolio.user.entity.UserStatus;
import com.dropfolio.user.repository.UserRepository;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private JwtService jwtService;

    @Mock
    private UserRepository userRepository;

    private RefreshTokenService refreshTokenService;

    @BeforeEach
    void setUp() {
        refreshTokenService =
                new RefreshTokenService(redisTemplate, jwtService, userRepository);
    }

    @Test
    void issue_storesJtiInRedisWithFamilyKey_andReturnsSignedToken() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(jwtService.newFamilyId()).thenReturn("family-1");
        when(jwtService.newJti()).thenReturn("jti-1");
        when(jwtService.issueRefreshToken(7L, "family-1", "jti-1"))
                .thenReturn("refresh.jwt");

        String token = refreshTokenService.issue(7L);

        assertThat(token).isEqualTo("refresh.jwt");

        verify(valueOperations).set(
                eq("auth:refresh:7:family-1"),
                eq("jti-1"),
                eq(Duration.ofDays(30))
        );
    }

    @Test
    void rotate_validPresentedToken_matchesStoredJti_issuesNewJtiAndToken() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        var parsed =
                new JwtService.ParsedRefreshToken(7L, "family-1", "jti-1");

        when(jwtService.parseRefreshToken("old.jwt"))
                .thenReturn(parsed);

        when(valueOperations.get("auth:refresh:7:family-1"))
                .thenReturn("jti-1");

        when(jwtService.newJti()).thenReturn("jti-2");

        when(jwtService.issueRefreshToken(
                7L,
                "family-1",
                "jti-2"
        )).thenReturn("new.jwt");

        RefreshTokenService.RotationResult result =
                refreshTokenService.rotate("old.jwt");

        assertThat(result.userId()).isEqualTo(7L);
        assertThat(result.refreshTokenJwt()).isEqualTo("new.jwt");

        verify(valueOperations).set(
                eq("auth:refresh:7:family-1"),
                eq("jti-2"),
                eq(Duration.ofDays(30))
        );

        verify(redisTemplate, never()).delete(anyString());
        verify(redisTemplate, never()).keys(anyString());
    }

    @Test
    void rotate_reusedOldJti_revokesAllSessions_andBumpsTokenVersion() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        var parsed =
                new JwtService.ParsedRefreshToken(
                        7L,
                        "family-1",
                        "STALE-jti"
                );

        when(jwtService.parseRefreshToken("stolen.jwt"))
                .thenReturn(parsed);

        when(valueOperations.get("auth:refresh:7:family-1"))
                .thenReturn("jti-2");

        when(redisTemplate.keys("auth:refresh:7:*"))
                .thenReturn(Set.of("auth:refresh:7:family-1"));

        User user = User.builder()
                .id(7L)
                .tokenVersion(3)
                .status(UserStatus.ACTIVE)
                .build();

        when(userRepository.findById(7L))
                .thenReturn(Optional.of(user));

        assertThatThrownBy(
                () -> refreshTokenService.rotate("stolen.jwt")
        ).isInstanceOf(InvalidRefreshTokenException.class);

        verify(redisTemplate)
                .delete(Set.of("auth:refresh:7:family-1"));

        verify(userRepository).save(user);

        assertThat(user.getTokenVersion()).isEqualTo(4);
    }

    @Test
    void rotate_sessionNoLongerExists_throwsInvalidRefreshToken_withoutTouchingUser() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        var parsed =
                new JwtService.ParsedRefreshToken(
                        7L,
                        "family-1",
                        "jti-1"
                );

        when(jwtService.parseRefreshToken("expired.jwt"))
                .thenReturn(parsed);

        when(valueOperations.get("auth:refresh:7:family-1"))
                .thenReturn(null);

        assertThatThrownBy(
                () -> refreshTokenService.rotate("expired.jwt")
        ).isInstanceOf(InvalidRefreshTokenException.class);

        verify(userRepository, never()).findById(any());
    }

    @Test
    void rotate_malformedToken_throwsInvalidRefreshToken() {
        when(jwtService.parseRefreshToken("garbage"))
                .thenThrow(new JwtException("bad signature"));

        assertThatThrownBy(
                () -> refreshTokenService.rotate("garbage")
        ).isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void revoke_deletesOnlyThatFamilyKey_singleDeviceLogout() {
        var parsed =
                new JwtService.ParsedRefreshToken(
                        7L,
                        "family-1",
                        "jti-1"
                );

        when(jwtService.parseRefreshToken("device-a.jwt"))
                .thenReturn(parsed);

        refreshTokenService.revoke("device-a.jwt");

        verify(redisTemplate)
                .delete("auth:refresh:7:family-1");

        verify(userRepository, never()).save(any());
    }

    @Test
    void revoke_nullOrBlankToken_isNoOp_logoutStaysIdempotent() {
        refreshTokenService.revoke(null);
        refreshTokenService.revoke("");

        verify(jwtService, never()).parseRefreshToken(anyString());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void revoke_alreadyInvalidToken_doesNotThrow_logoutStillSucceeds() {
        when(jwtService.parseRefreshToken("already-expired"))
                .thenThrow(new JwtException("expired"));

        refreshTokenService.revoke("already-expired");

        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void revokeAll_deletesEveryFamilyKeyForUser_andBumpsTokenVersionOnce() {
        when(redisTemplate.keys("auth:refresh:7:*"))
                .thenReturn(Set.of(
                        "auth:refresh:7:family-1",
                        "auth:refresh:7:family-2"
                ));

        User user = User.builder()
                .id(7L)
                .tokenVersion(0)
                .status(UserStatus.ACTIVE)
                .build();

        when(userRepository.findById(7L))
                .thenReturn(Optional.of(user));

        refreshTokenService.revokeAll(7L);

        verify(redisTemplate, times(1))
                .delete(Set.of(
                        "auth:refresh:7:family-1",
                        "auth:refresh:7:family-2"
                ));

        assertThat(user.getTokenVersion()).isEqualTo(1);
    }
}
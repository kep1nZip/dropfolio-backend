package com.dropfolio.auth.service;

import com.dropfolio.auth.dto.LoginRequest;
import com.dropfolio.auth.dto.RegisterRequest;
import com.dropfolio.common.exception.AccountDeactivatedException;
import com.dropfolio.common.exception.EmailAlreadyRegisteredException;
import com.dropfolio.common.exception.InvalidCredentialsException;
import com.dropfolio.common.security.JwtProperties;
import com.dropfolio.common.security.JwtService;
import com.dropfolio.notification.entity.NotificationPreferences;
import com.dropfolio.notification.repository.NotificationPreferencesRepository;
import com.dropfolio.user.entity.Role;
import com.dropfolio.user.entity.RoleName;
import com.dropfolio.user.entity.User;
import com.dropfolio.user.entity.UserStatus;
import com.dropfolio.user.repository.RoleRepository;
import com.dropfolio.user.repository.UserRepository;
import com.dropfolio.user.repository.UserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for AuthService — TECHNICAL_SPEC.md §16.1 (service layer, mocked repository).
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private NotificationPreferencesRepository notificationPreferencesRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private RefreshTokenService refreshTokenService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties("test-secret-value-not-real", 900, 2592000);
        authService = new AuthService(userRepository, roleRepository, userRoleRepository,
                notificationPreferencesRepository, passwordEncoder, jwtService, jwtProperties, refreshTokenService);
    }

    @Test
    void register_success_createsUserWithDefaultRole() {
        RegisterRequest request = new RegisterRequest("new@example.com", "Passw0rd1", "Budi");
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(passwordEncoder.encode("Passw0rd1")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(42L);
            return u;
        });
        Role userRole = new Role(1L, RoleName.USER);
        when(roleRepository.findByName(RoleName.USER)).thenReturn(Optional.of(userRole));

        var response = authService.register(request);

        assertThat(response.userId()).isEqualTo(42L);
        assertThat(response.email()).isEqualTo("new@example.com");
        assertThat(response.displayName()).isEqualTo("Budi");
        verify(userRoleRepository).save(any());
    }

    /**
     * M8 Implementation Authorization §14 / ERD.md §2.10: registration must create a default
     * {@code notification_preferences} row (both channels enabled), created automatically —
     * never lazy-created — and never duplicated for the same user.
     */
    @Test
    void register_success_createsDefaultNotificationPreferences_emailAndInAppEnabled() {
        RegisterRequest request = new RegisterRequest("new@example.com", "Passw0rd1", "Budi");
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(passwordEncoder.encode("Passw0rd1")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(42L);
            return u;
        });
        when(roleRepository.findByName(RoleName.USER)).thenReturn(Optional.of(new Role(1L, RoleName.USER)));

        authService.register(request);

        var captor = org.mockito.ArgumentCaptor.forClass(NotificationPreferences.class);
        verify(notificationPreferencesRepository, org.mockito.Mockito.times(1)).save(captor.capture());
        NotificationPreferences saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(42L);
        assertThat(saved.getEmailEnabled()).isTrue();
        assertThat(saved.getInAppEnabled()).isTrue();
    }

    @Test
    void register_duplicateEmail_throwsConflict_neverCreatesNotificationPreferences() {
        RegisterRequest request = new RegisterRequest("taken@example.com", "Passw0rd1", "Budi");
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(EmailAlreadyRegisteredException.class);

        verify(userRepository, never()).save(any());
        verify(notificationPreferencesRepository, never()).save(any());
    }

    @Test
    void login_wrongPassword_throwsInvalidCredentials_notLeakingReason() {
        User user = activeUserWithPassword("hashed");
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("user@example.com", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void login_unknownEmail_throwsInvalidCredentials_sameAsWrongPassword() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("ghost@example.com", "whatever1")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void login_steamOnlyAccount_noPasswordHash_throwsInvalidCredentials() {
        User steamOnly = User.builder().id(1L).email("steam@example.com").passwordHash(null)
                .displayName("Steam User").tokenVersion(0).status(UserStatus.ACTIVE).build();
        when(userRepository.findByEmail("steam@example.com")).thenReturn(Optional.of(steamOnly));

        assertThatThrownBy(() -> authService.login(new LoginRequest("steam@example.com", "anything1")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void login_deactivatedAccount_afterValidPassword_throwsAccountDeactivated() {
        User user = User.builder().id(1L).email("user@example.com").passwordHash("hashed")
                .displayName("Budi").tokenVersion(0).status(UserStatus.DEACTIVATED).build();
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Passw0rd1", "hashed")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(new LoginRequest("user@example.com", "Passw0rd1")))
                .isInstanceOf(AccountDeactivatedException.class);
    }

    @Test
    void login_success_issuesAccessAndRefreshToken() {
        User user = activeUserWithPassword("hashed");
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Passw0rd1", "hashed")).thenReturn(true);
        when(userRoleRepository.findRoleNamesByUserId(1L)).thenReturn(List.of(RoleName.USER));
        when(jwtService.issueAccessToken(anyLong(), any(), anyInt())).thenReturn("access.jwt.token");
        when(refreshTokenService.issue(1L)).thenReturn("refresh.jwt.token");

        AuthService.LoginResult result = authService.login(new LoginRequest("user@example.com", "Passw0rd1"));

        assertThat(result.body().accessToken()).isEqualTo("access.jwt.token");
        assertThat(result.body().expiresIn()).isEqualTo(900);
        assertThat(result.body().user().roles()).containsExactly("USER");
        assertThat(result.refreshTokenJwt()).isEqualTo("refresh.jwt.token");
    }

    @Test
    void logout_delegatesToRefreshTokenServiceRevoke_singleDeviceOnly() {
        authService.logout("some-refresh-cookie-value");

        verify(refreshTokenService).revoke("some-refresh-cookie-value");
        // Single-device logout must never touch token_version — verified structurally: logout()
        // has no dependency capable of bumping it (no userRepository.save call path here).
        verify(userRepository, never()).save(any());
    }

    private User activeUserWithPassword(String hash) {
        return User.builder().id(1L).email("user@example.com").passwordHash(hash)
                .displayName("Budi").tokenVersion(0).status(UserStatus.ACTIVE).build();
    }
}

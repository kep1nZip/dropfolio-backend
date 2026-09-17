package com.dropfolio.auth.service;

import com.dropfolio.auth.dto.LoginRequest;
import com.dropfolio.auth.dto.LoginResponse;
import com.dropfolio.auth.dto.RegisterRequest;
import com.dropfolio.auth.dto.RegisterResponse;
import com.dropfolio.auth.mapper.UserSummaryMapper;
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
import com.dropfolio.user.entity.UserRole;
import com.dropfolio.user.entity.UserStatus;
import com.dropfolio.user.repository.RoleRepository;
import com.dropfolio.user.repository.UserRepository;
import com.dropfolio.user.repository.UserRoleRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Auth business logic — SYSTEM_ARCHITECTURE.md §3.1A, API_CONTRACT.md §1.
 * Password login only; Steam login/linking (§3.1B, §3.2) is out of this milestone's scope.
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final NotificationPreferencesRepository notificationPreferencesRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final RefreshTokenService refreshTokenService;

    public AuthService(UserRepository userRepository, RoleRepository roleRepository,
                        UserRoleRepository userRoleRepository,
                        NotificationPreferencesRepository notificationPreferencesRepository,
                        PasswordEncoder passwordEncoder,
                        JwtService jwtService, JwtProperties jwtProperties,
                        RefreshTokenService refreshTokenService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.notificationPreferencesRepository = notificationPreferencesRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.refreshTokenService = refreshTokenService;
    }

    /** API_CONTRACT.md POST /auth/register — no token issued here, by design (§1). */
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyRegisteredException("Email already registered");
        }

        User user = User.builder()
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .displayName(request.displayName())
                .tokenVersion(0)
                .status(UserStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();
        user = userRepository.save(user);

        Role userRole = roleRepository.findByName(RoleName.USER)
                .orElseThrow(() -> new IllegalStateException("USER role not seeded — check DB seed data"));
        userRoleRepository.save(UserRole.of(user, userRole));

        // M8 Implementation Authorization §14 / ERD.md §2.10: created automatically at
        // registration, never lazy-created. No other auth behavior changed (§4).
        NotificationPreferences preferences = NotificationPreferences.builder()
                .userId(user.getId())
                .emailEnabled(true)
                .inAppEnabled(true)
                .createdAt(Instant.now())
                .build();
        notificationPreferencesRepository.save(preferences);

        return new RegisterResponse(user.getId(), user.getEmail(), user.getDisplayName());
    }

    /**
     * API_CONTRACT.md POST /auth/login.
     *
     * @return the response body plus the raw refresh token JWT to be set as the httpOnly
     *         cookie value — kept out of {@link LoginResponse} itself (never in the body).
     */
    @Transactional
    public LoginResult login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));

        // Steam-only accounts (no password set yet) and wrong passwords both fail generically —
        // never reveal which reason, to avoid leaking account existence/type.
        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        // ACCOUNT_DEACTIVATED cross-referenced from API_CONTRACT.md §0.6.2 registry row
        // ("Login ditolak karena admin men-deactivate akun") — checked only after credentials
        // are confirmed correct, so we don't leak deactivation status to a wrong-password guess.
        if (user.getStatus() == UserStatus.DEACTIVATED) {
            throw new AccountDeactivatedException("Account has been deactivated");
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            // Defensive: DELETED accounts have email nulled on delete (ERD.md §6.1) so this
            // branch shouldn't be reachable via email lookup — kept as a generic-safe fallback.
            throw new InvalidCredentialsException("Invalid email or password");
        }

        List<RoleName> roles = userRoleRepository.findRoleNamesByUserId(user.getId());
        String accessToken = jwtService.issueAccessToken(user.getId(),
                roles.stream().map(Enum::name).toList(), user.getTokenVersion());
        String refreshToken = refreshTokenService.issue(user.getId());

        LoginResponse body = new LoginResponse(accessToken, (int) jwtProperties.accessTokenTtlSeconds(),
                UserSummaryMapper.toSummary(user, roles));

        return new LoginResult(body, refreshToken);
    }

    /**
     * API_CONTRACT.md POST /auth/refresh — rotates the refresh token (delegated to
     * {@link RefreshTokenService#rotate}) then re-derives a fresh access token for the same
     * response shape as /auth/login.
     *
     * SECURITY-HARDENING NOTE (disclosed, see Milestone 2 completion report): the per-endpoint
     * status-code list for /auth/refresh in API_CONTRACT.md §1 only names
     * {@code INVALID_REFRESH_TOKEN}. This method additionally rejects a DEACTIVATED account
     * here too (reusing the already-locked {@code ACCOUNT_DEACTIVATED} code from the registry,
     * §0.6.2) — without this check, an admin-deactivated user could keep silently minting valid
     * access tokens via refresh even though their existing access tokens were just invalidated
     * by the token_version bump (PATCH /admin/users/{id}/status), defeating the "instant revoke"
     * intent of SYSTEM_ARCHITECTURE.md §3.3. No new endpoint/field/error-code was added — this
     * is flagged for PM confirmation, not silently assumed.
     */
    @Transactional
    public LoginResult refresh(String presentedRefreshTokenJwt) {
        RefreshTokenService.RotationResult rotation = refreshTokenService.rotate(presentedRefreshTokenJwt);

        User user = userRepository.findById(rotation.userId())
                .orElseThrow(() -> new InvalidCredentialsException("Session no longer valid"));

        if (user.getStatus() == UserStatus.DEACTIVATED || user.getStatus() == UserStatus.DELETED) {
            refreshTokenService.revokeAll(user.getId());
            throw new AccountDeactivatedException("Account has been deactivated");
        }

        List<RoleName> roles = userRoleRepository.findRoleNamesByUserId(user.getId());
        String accessToken = jwtService.issueAccessToken(user.getId(),
                roles.stream().map(Enum::name).toList(), user.getTokenVersion());

        LoginResponse body = new LoginResponse(accessToken, (int) jwtProperties.accessTokenTtlSeconds(),
                UserSummaryMapper.toSummary(user, roles));

        return new LoginResult(body, rotation.refreshTokenJwt());
    }

    /** API_CONTRACT.md POST /auth/logout — single device only, does NOT bump token_version. */
    public void logout(String refreshTokenCookieValue) {
        refreshTokenService.revoke(refreshTokenCookieValue);
    }

    public record LoginResult(LoginResponse body, String refreshTokenJwt) {
    }
}

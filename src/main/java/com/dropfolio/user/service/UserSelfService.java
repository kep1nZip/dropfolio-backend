package com.dropfolio.user.service;

import com.dropfolio.auth.service.RefreshTokenService;
import com.dropfolio.common.exception.EmailAlreadyRegisteredException;
import com.dropfolio.common.exception.InvalidCurrentPasswordException;
import com.dropfolio.common.exception.ResourceNotFoundException;
import com.dropfolio.common.exception.ValidationException;
import com.dropfolio.user.dto.ChangePasswordRequest;
import com.dropfolio.user.dto.DeleteAccountRequest;
import com.dropfolio.user.dto.UpdateProfileRequest;
import com.dropfolio.user.dto.UserProfileResponse;
import com.dropfolio.user.entity.User;
import com.dropfolio.user.entity.UserStatus;
import com.dropfolio.user.mapper.UserProfileMapper;
import com.dropfolio.user.repository.UserRepository;
import com.dropfolio.user.repository.UserRoleRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.List;

/**
 * API_CONTRACT.md §2 - Domain: Users (self-service). Every method is scoped to the
 * authenticated caller's own userId - ownership is "self" by construction here (the
 * controller only ever passes the authenticated principal's id, never a path variable), unlike
 * AlertService/NotificationService's explicit findByIdAndUserId pattern (there's no separate
 * "id" to compare against - the resource IS the caller).
 */
@Service
public class UserSelfService {

    private static final String DELETED_EMAIL_DOMAIN = "@dropfolio.invalid";
    private static final String DELETED_DISPLAY_NAME = "Deleted User";
    private static final String DELETE_CONFIRMATION_PHRASE = "DELETE";

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final Clock clock;

    public UserSelfService(UserRepository userRepository,
                            UserRoleRepository userRoleRepository,
                            PasswordEncoder passwordEncoder,
                            RefreshTokenService refreshTokenService,
                            Clock clock) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(Long userId) {
        User user = requireUser(userId);
        return UserProfileMapper.toResponse(user, roleNames(userId));
    }

    @Transactional
    public UserProfileResponse updateProfile(Long userId, UpdateProfileRequest request) {
        User user = requireUser(userId);
        if (request.displayName() != null) {
            user.setDisplayName(request.displayName());
        }
        if (request.email() != null && !request.email().equalsIgnoreCase(user.getEmail())) {
            if (userRepository.existsByEmail(request.email())) {
                throw new EmailAlreadyRegisteredException("Email already registered");
            }
            user.setEmail(request.email());
        }
        user.setUpdatedAt(clock.instant());
        User saved = userRepository.save(user);
        return UserProfileMapper.toResponse(saved, roleNames(userId));
    }

    /**
     * API_CONTRACT.md §2: currentPassword is required and validated UNLESS password_hash IS
     * NULL (a Steam-only account that never set a password - no registration path in this
     * product currently produces one, since there is no Steam login here; the branch exists
     * for contract fidelity, not because it's reachable today).
     */
    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = requireUser(userId);
        if (user.getPasswordHash() != null) {
            if (request.currentPassword() == null
                    || !passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
                throw new InvalidCurrentPasswordException("Current password is incorrect");
            }
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        bumpTokenVersionAndRevokeSessions(user);
    }

    /** API_CONTRACT.md §2: bump token_version + revoke the active refresh token - invalidates every device. */
    @Transactional
    public void logoutAll(Long userId) {
        User user = requireUser(userId);
        bumpTokenVersionAndRevokeSessions(user);
    }

    /**
     * ERD.md §6.1 soft-delete sequence, with M10 Implementation Authorization §2's locked
     * anonymization method: the original email is replaced with a deterministic, one-way
     * SHA-256 hash - never NULL (would need a nullable-safe unique index for no real benefit)
     * and never a visible placeholder containing the original address. Hashing is
     * deterministic specifically so the ORIGINAL email becomes free for a new registration
     * (the hash occupies the unique constraint instead of the real address), which is the
     * "uniqueness stays meaningful, no accidental conflict" requirement §2 asks for.
     * drops/price_alerts/notifications are untouched - no cascade, no additional deletion -
     * exactly ERD.md §6.1's "histori dipertahankan."
     */
    @Transactional
    public void deleteAccount(Long userId, DeleteAccountRequest request) {
        if (!DELETE_CONFIRMATION_PHRASE.equals(request.confirmation())) {
            throw new ValidationException("confirmation must be exactly \"" + DELETE_CONFIRMATION_PHRASE + "\"");
        }
        User user = requireUser(userId);
        if (user.getStatus() == UserStatus.DELETED) {
            throw new ValidationException("Account is already deleted");
        }

        user.setEmail(hashEmail(user.getEmail()));
        user.setPasswordHash(null);
        user.setDisplayName(DELETED_DISPLAY_NAME);
        user.setStatus(UserStatus.DELETED);
        user.setDeletedAt(clock.instant());
        bumpTokenVersionAndRevokeSessions(user);
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private List<String> roleNames(Long userId) {
        return userRoleRepository.findRoleNamesByUserId(userId).stream()
                .map(Enum::name)
                .toList();
    }

    /** Shared tail for every operation that must kill existing sessions: bump + persist + revoke refresh tokens. */
    private void bumpTokenVersionAndRevokeSessions(User user) {
        user.setTokenVersion(user.getTokenVersion() + 1);
        user.setUpdatedAt(clock.instant());
        userRepository.save(user);
        refreshTokenService.revokeAll(user.getId());
    }

    private String hashEmail(String originalEmail) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(originalEmail.toLowerCase().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return "deleted+" + hex + DELETED_EMAIL_DOMAIN;
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JDK algorithm (JLS/JCA baseline) - this branch is
            // unreachable on any conforming JVM, kept only to satisfy the checked exception.
            throw new IllegalStateException("SHA-256 MessageDigest unavailable", e);
        }
    }
}

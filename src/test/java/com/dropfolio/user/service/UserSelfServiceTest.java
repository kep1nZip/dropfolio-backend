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
import com.dropfolio.user.entity.RoleName;
import com.dropfolio.user.entity.User;
import com.dropfolio.user.entity.UserStatus;
import com.dropfolio.user.repository.UserRepository;
import com.dropfolio.user.repository.UserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for UserSelfService — M10 Implementation Authorization §13. */
@ExtendWith(MockitoExtension.class)
class UserSelfServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-14T12:00:00Z");
    private static final Long USER_ID = 1L;

    @Mock private UserRepository userRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private RefreshTokenService refreshTokenService;

    private Clock clock;
    private UserSelfService userSelfService;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        userSelfService = new UserSelfService(userRepository, userRoleRepository, passwordEncoder, refreshTokenService, clock);
    }

    private User user(String email, String passwordHash, UserStatus status, int tokenVersion) {
        return User.builder().id(USER_ID).email(email).passwordHash(passwordHash).displayName("Budi")
                .status(status).tokenVersion(tokenVersion).createdAt(FIXED_INSTANT).build();
    }

    @Test
    void getProfile_returnsOwnDataOnly_neverExposesPasswordHash() {
        User u = user("budi@example.com", "hashed", UserStatus.ACTIVE, 0);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));
        when(userRoleRepository.findRoleNamesByUserId(USER_ID)).thenReturn(List.of(RoleName.USER));

        UserProfileResponse response = userSelfService.getProfile(USER_ID);

        assertThat(response.email()).isEqualTo("budi@example.com");
        assertThat(response.roles()).containsExactly("USER");
        assertThat(UserProfileResponse.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("passwordHash", "tokenVersion");
    }

    @Test
    void getProfile_steamIntegrationAlwaysNull() {
        User u = user("budi@example.com", "hashed", UserStatus.ACTIVE, 0);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));
        when(userRoleRepository.findRoleNamesByUserId(USER_ID)).thenReturn(List.of(RoleName.USER));

        assertThat(userSelfService.getProfile(USER_ID).steamIntegration()).isNull();
    }

    @Test
    void getProfile_nonexistentUser_throws404() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userSelfService.getProfile(999L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateProfile_partialDisplayNameOnly_emailUnchanged() {
        User u = user("budi@example.com", "hashed", UserStatus.ACTIVE, 0);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRoleRepository.findRoleNamesByUserId(USER_ID)).thenReturn(List.of(RoleName.USER));

        UserProfileResponse response = userSelfService.updateProfile(USER_ID, new UpdateProfileRequest("New Name", null));

        assertThat(response.displayName()).isEqualTo("New Name");
        assertThat(response.email()).isEqualTo("budi@example.com");
    }

    @Test
    void updateProfile_emailChangedToAvailableAddress_succeeds() {
        User u = user("budi@example.com", "hashed", UserStatus.ACTIVE, 0);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRoleRepository.findRoleNamesByUserId(USER_ID)).thenReturn(List.of(RoleName.USER));

        UserProfileResponse response = userSelfService.updateProfile(USER_ID, new UpdateProfileRequest(null, "new@example.com"));

        assertThat(response.email()).isEqualTo("new@example.com");
    }

    @Test
    void updateProfile_emailAlreadyTaken_throwsConflict() {
        User u = user("budi@example.com", "hashed", UserStatus.ACTIVE, 0);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userSelfService.updateProfile(USER_ID, new UpdateProfileRequest(null, "taken@example.com")))
                .isInstanceOf(EmailAlreadyRegisteredException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateProfile_emailUnchangedSameValue_noUniquenessCheckNeeded() {
        User u = user("budi@example.com", "hashed", UserStatus.ACTIVE, 0);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRoleRepository.findRoleNamesByUserId(USER_ID)).thenReturn(List.of(RoleName.USER));

        userSelfService.updateProfile(USER_ID, new UpdateProfileRequest(null, "budi@example.com"));

        verify(userRepository, never()).existsByEmail(any());
    }

    @Test
    void changePassword_correctCurrentPassword_succeeds_bumpsTokenVersion_revokesRefreshTokens() {
        User u = user("budi@example.com", "oldHash", UserStatus.ACTIVE, 5);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("oldPass123!", "oldHash")).thenReturn(true);
        when(passwordEncoder.encode("newPass456!")).thenReturn("newHash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        userSelfService.changePassword(USER_ID, new ChangePasswordRequest("oldPass123!", "newPass456!"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("newHash");
        assertThat(captor.getValue().getTokenVersion()).isEqualTo(6);
        verify(refreshTokenService).revokeAll(USER_ID);
    }

    @Test
    void changePassword_incorrectCurrentPassword_throwsInvalidCurrentPassword() {
        User u = user("budi@example.com", "oldHash", UserStatus.ACTIVE, 5);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("wrongPass", "oldHash")).thenReturn(false);

        assertThatThrownBy(() -> userSelfService.changePassword(USER_ID, new ChangePasswordRequest("wrongPass", "newPass456!")))
                .isInstanceOf(InvalidCurrentPasswordException.class);
        verify(userRepository, never()).save(any());
        verify(refreshTokenService, never()).revokeAll(anyLong());
    }

    @Test
    void changePassword_missingCurrentPassword_whenPasswordHashExists_rejected() {
        User u = user("budi@example.com", "oldHash", UserStatus.ACTIVE, 5);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));

        assertThatThrownBy(() -> userSelfService.changePassword(USER_ID, new ChangePasswordRequest(null, "newPass456!")))
                .isInstanceOf(InvalidCurrentPasswordException.class);
    }

    @Test
    void changePassword_passwordHashIsNull_currentPasswordNotRequired() {
        User u = user("budi@example.com", null, UserStatus.ACTIVE, 0);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));
        when(passwordEncoder.encode("newPass456!")).thenReturn("newHash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        userSelfService.changePassword(USER_ID, new ChangePasswordRequest(null, "newPass456!"));

        verify(userRepository).save(any(User.class));
    }

    @Test
    void logoutAll_bumpsTokenVersion_revokesRefreshTokens() {
        User u = user("budi@example.com", "hash", UserStatus.ACTIVE, 3);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        userSelfService.logoutAll(USER_ID);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getTokenVersion()).isEqualTo(4);
        verify(refreshTokenService).revokeAll(USER_ID);
    }

    @Test
    void deleteAccount_correctConfirmation_anonymizesEmail_deterministicHash() {
        User u = user("budi@example.com", "hash", UserStatus.ACTIVE, 0);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        userSelfService.deleteAccount(USER_ID, new DeleteAccountRequest("DELETE"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getEmail()).startsWith("deleted+").endsWith("@dropfolio.invalid");
        assertThat(saved.getEmail()).doesNotContain("budi@example.com");
        assertThat(saved.getPasswordHash()).isNull();
        assertThat(saved.getDisplayName()).isEqualTo("Deleted User");
        assertThat(saved.getStatus()).isEqualTo(UserStatus.DELETED);
        assertThat(saved.getDeletedAt()).isEqualTo(FIXED_INSTANT);
        verify(refreshTokenService).revokeAll(USER_ID);
    }

    @Test
    void deleteAccount_hashIsDeterministic_originalEmailBecomesReusable() {
        User u1 = user("same@example.com", "hash", UserStatus.ACTIVE, 0);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u1));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<User> captor1 = ArgumentCaptor.forClass(User.class);

        userSelfService.deleteAccount(USER_ID, new DeleteAccountRequest("DELETE"));
        verify(userRepository).save(captor1.capture());
        String firstHash = captor1.getValue().getEmail();

        User u2 = user("same@example.com", "hash", UserStatus.ACTIVE, 0);
        when(userRepository.findById(2L)).thenReturn(Optional.of(u2));
        ArgumentCaptor<User> captor2 = ArgumentCaptor.forClass(User.class);
        userSelfService.deleteAccount(2L, new DeleteAccountRequest("DELETE"));
        verify(userRepository, org.mockito.Mockito.times(2)).save(captor2.capture());

        assertThat(captor2.getAllValues().get(1).getEmail()).isEqualTo(firstHash);
    }

    @Test
    void deleteAccount_wrongConfirmation_rejected() {
        assertThatThrownBy(() -> userSelfService.deleteAccount(USER_ID, new DeleteAccountRequest("delete")))
                .isInstanceOf(ValidationException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void deleteAccount_alreadyDeleted_rejected() {
        User u = user("budi@example.com", null, UserStatus.DELETED, 0);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));

        assertThatThrownBy(() -> userSelfService.deleteAccount(USER_ID, new DeleteAccountRequest("DELETE")))
                .isInstanceOf(ValidationException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void deleteAccount_doesNotTouchDropsAlertsNotifications_noRepositoryForThemInjected() {
        User u = user("budi@example.com", "hash", UserStatus.ACTIVE, 0);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        userSelfService.deleteAccount(USER_ID, new DeleteAccountRequest("DELETE"));
    }

    @Test
    void deleteAccount_nonexistentUser_throws404() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userSelfService.deleteAccount(999L, new DeleteAccountRequest("DELETE")))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}

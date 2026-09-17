package com.dropfolio.admin.service;

import com.dropfolio.admin.dto.AdminUserDetailResponse;
import com.dropfolio.admin.dto.AdminUserResponse;
import com.dropfolio.admin.dto.UpdateUserStatusRequest;
import com.dropfolio.alert.repository.PriceAlertRepository;
import com.dropfolio.auth.service.RefreshTokenService;
import com.dropfolio.common.audit.AuditLogService;
import com.dropfolio.common.exception.ResourceNotFoundException;
import com.dropfolio.common.exception.ValidationException;
import com.dropfolio.drop.repository.DropRepository;
import com.dropfolio.user.entity.User;
import com.dropfolio.user.entity.UserStatus;
import com.dropfolio.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminUserService} — M9 Implementation Authorization §3/§4/§5/§14.
 * Note on scope: the actual consequence of deactivation (next login rejected with
 * ACCOUNT_DEACTIVATED) is already covered by AuthServiceTest's
 * login_deactivatedAccount_afterValidPassword_throwsAccountDeactivated (M2) — these tests
 * verify THIS service correctly sets the state that makes that check fire (status +
 * token_version + refresh-token revocation), not the login flow itself again.
 */
@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-13T12:00:00Z");

    @Mock private UserRepository userRepository;
    @Mock private DropRepository dropRepository;
    @Mock private PriceAlertRepository priceAlertRepository;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private AuditLogService auditLogService;

    private Clock clock;
    private AdminUserService adminUserService;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        adminUserService = new AdminUserService(
                userRepository, dropRepository, priceAlertRepository, refreshTokenService, auditLogService, clock);
    }

    private User user(Long id, UserStatus status, int tokenVersion) {
        return User.builder().id(id).email("user" + id + "@example.com").displayName("User " + id)
                .status(status).tokenVersion(tokenVersion).createdAt(FIXED_INSTANT).build();
    }

    @Test
    void list_invalidPage_throwsValidation() {
        assertThatThrownBy(() -> adminUserService.list(null, null, 0, 20))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void list_invalidSize_throwsValidation() {
        assertThatThrownBy(() -> adminUserService.list(null, null, 1, 0))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void list_neverExposesPasswordHash() {
        User u = user(1L, UserStatus.ACTIVE, 0);
        u.setPasswordHash("$2a$10$shouldNeverAppear");
        when(userRepository.findAll(org.mockito.ArgumentMatchers.<Specification<User>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(u)));

        Page<AdminUserResponse> result = adminUserService.list(null, null, 1, 20);

        assertThat(result.getContent()).hasSize(1);
        assertThat(AdminUserResponse.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("passwordHash", "tokenVersion");
    }

    @Test
    void list_emptyResult_returnsEmptyPage() {
        when(userRepository.findAll(org.mockito.ArgumentMatchers.<Specification<User>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        Page<AdminUserResponse> result = adminUserService.list("nobody", null, 1, 20);

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    void getById_found_includesTotalDropsAndTotalAlerts() {
        User u = user(1L, UserStatus.ACTIVE, 0);
        when(userRepository.findById(1L)).thenReturn(Optional.of(u));
        when(dropRepository.countByUserIdAndDeletedAtIsNull(1L)).thenReturn(7L);
        when(priceAlertRepository.countByUserId(1L)).thenReturn(3L);

        AdminUserDetailResponse response = adminUserService.getById(1L);

        assertThat(response.totalDrops()).isEqualTo(7L);
        assertThat(response.totalAlerts()).isEqualTo(3L);
    }

    @Test
    void getById_steamIntegrationAlwaysNull_neverImpliesRealIntegration() {
        User u = user(1L, UserStatus.ACTIVE, 0);
        when(userRepository.findById(1L)).thenReturn(Optional.of(u));
        when(dropRepository.countByUserIdAndDeletedAtIsNull(1L)).thenReturn(0L);
        when(priceAlertRepository.countByUserId(1L)).thenReturn(0L);

        AdminUserDetailResponse response = adminUserService.getById(1L);

        assertThat(response.steamIntegration()).isNull();
    }

    @Test
    void getById_nonexistentUser_throws404() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminUserService.getById(999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateStatus_activeToDeactivated_bumpsTokenVersion_revokesRefreshTokens_writesAudit() {
        User u = user(1L, UserStatus.ACTIVE, 5);
        when(userRepository.findById(1L)).thenReturn(Optional.of(u));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(dropRepository.countByUserIdAndDeletedAtIsNull(1L)).thenReturn(0L);
        when(priceAlertRepository.countByUserId(1L)).thenReturn(0L);

        AdminUserDetailResponse response = adminUserService.updateStatus(1L, new UpdateUserStatusRequest("DEACTIVATED"));

        assertThat(response.status()).isEqualTo("DEACTIVATED");
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getTokenVersion()).isEqualTo(6);
        verify(refreshTokenService).revokeAll(1L);
        verify(auditLogService).record(eq(null), eq("ADMIN_UPDATE_USER_STATUS"), eq("users"), eq(1L), any(), eq(null));
    }

    @Test
    void updateStatus_deactivatedToActive_doesNotBumpTokenVersion_doesNotRevoke() {
        User u = user(1L, UserStatus.DEACTIVATED, 5);
        when(userRepository.findById(1L)).thenReturn(Optional.of(u));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(dropRepository.countByUserIdAndDeletedAtIsNull(1L)).thenReturn(0L);
        when(priceAlertRepository.countByUserId(1L)).thenReturn(0L);

        AdminUserDetailResponse response = adminUserService.updateStatus(1L, new UpdateUserStatusRequest("ACTIVE"));

        assertThat(response.status()).isEqualTo("ACTIVE");
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getTokenVersion()).isEqualTo(5);
        verify(refreshTokenService, never()).revokeAll(anyLong());
        verify(auditLogService).record(any(), eq("ADMIN_UPDATE_USER_STATUS"), any(), eq(1L), any(), any());
    }

    @Test
    void updateStatus_targetDeleted_rejected_locked() {
        User u = user(1L, UserStatus.ACTIVE, 0);

        assertThatThrownBy(() -> adminUserService.updateStatus(1L, new UpdateUserStatusRequest("DELETED")))
                .isInstanceOf(ValidationException.class);
        verify(userRepository, never()).save(any());
        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateStatus_invalidStatusValue_rejected() {
        User u = user(1L, UserStatus.ACTIVE, 0);

        assertThatThrownBy(() -> adminUserService.updateStatus(1L, new UpdateUserStatusRequest("BANNED")))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void updateStatus_currentlyDeletedUser_cannotBeAdministrativelyChanged() {
        User u = user(1L, UserStatus.DELETED, 0);
        when(userRepository.findById(1L)).thenReturn(Optional.of(u));

        assertThatThrownBy(() -> adminUserService.updateStatus(1L, new UpdateUserStatusRequest("ACTIVE")))
                .isInstanceOf(ValidationException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void updateStatus_nonexistentUser_throws404() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminUserService.updateStatus(999L, new UpdateUserStatusRequest("ACTIVE")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateStatus_createsExactlyOneAuditRecord() {
        User u = user(1L, UserStatus.ACTIVE, 0);
        when(userRepository.findById(1L)).thenReturn(Optional.of(u));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(dropRepository.countByUserIdAndDeletedAtIsNull(1L)).thenReturn(0L);
        when(priceAlertRepository.countByUserId(1L)).thenReturn(0L);

        adminUserService.updateStatus(1L, new UpdateUserStatusRequest("DEACTIVATED"));

        verify(auditLogService, org.mockito.Mockito.times(1))
                .record(any(), eq("ADMIN_UPDATE_USER_STATUS"), any(), any(), any(), any());
    }
}

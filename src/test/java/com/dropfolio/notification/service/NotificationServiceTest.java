package com.dropfolio.notification.service;

import com.dropfolio.common.exception.OwnershipMismatchException;
import com.dropfolio.common.exception.ValidationException;
import com.dropfolio.notification.dto.NotificationPreferencesResponse;
import com.dropfolio.notification.dto.NotificationResponse;
import com.dropfolio.notification.dto.UpdateNotificationPreferencesRequest;
import com.dropfolio.notification.entity.Notification;
import com.dropfolio.notification.entity.NotificationPreferences;
import com.dropfolio.notification.entity.NotificationType;
import com.dropfolio.notification.repository.NotificationPreferencesRepository;
import com.dropfolio.notification.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link NotificationService} — M8 Implementation Authorization §18 Notification. */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-11T00:00:00Z");
    private static final Long USER_ID = 7L;

    @Mock private NotificationRepository notificationRepository;
    @Mock private NotificationPreferencesRepository preferencesRepository;

    private Clock clock;
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        notificationService = new NotificationService(notificationRepository, preferencesRepository, clock);
    }

    private Notification notification(Long id, Instant readAt) {
        return Notification.builder().id(id).userId(USER_ID).alertId(1L).type(NotificationType.PRICE_ALERT)
                .title("t").message("m").readAt(readAt).createdAt(FIXED_INSTANT).build();
    }

    // ---- list ----

    @Test
    void list_invalidPage_throwsValidation() {
        assertThatThrownBy(() -> notificationService.list(USER_ID, false, 0, 20))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void list_invalidSize_throwsValidation() {
        assertThatThrownBy(() -> notificationService.list(USER_ID, false, 1, 0))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void list_unreadOnlyFalse_delegatesToFindByUserId() {
        when(notificationRepository.findByUserId(eq(USER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(notification(1L, null))));

        Page<NotificationResponse> result = notificationService.list(USER_ID, false, 1, 20);

        assertThat(result.getContent()).hasSize(1);
        verify(notificationRepository, never()).findByUserIdAndReadAtIsNull(any(), any());
    }

    @Test
    void list_unreadOnlyTrue_delegatesToFindByUserIdAndReadAtIsNull() {
        when(notificationRepository.findByUserIdAndReadAtIsNull(eq(USER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(notification(1L, null))));

        notificationService.list(USER_ID, true, 1, 20);

        verify(notificationRepository).findByUserIdAndReadAtIsNull(eq(USER_ID), any(Pageable.class));
        verify(notificationRepository, never()).findByUserId(any(), any());
    }

    @Test
    void unreadCount_delegatesToRepository() {
        when(notificationRepository.countByUserIdAndReadAtIsNull(USER_ID)).thenReturn(3L);

        assertThat(notificationService.unreadCount(USER_ID)).isEqualTo(3L);
    }

    // ---- markRead ----

    @Test
    void markRead_ownedAndUnread_setsReadAt() {
        Notification n = notification(1L, null);
        when(notificationRepository.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(n));

        notificationService.markRead(USER_ID, 1L);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        assertThat(captor.getValue().getReadAt()).isEqualTo(FIXED_INSTANT);
    }

    @Test
    void markRead_alreadyRead_idempotent_doesNotResave() {
        Notification n = notification(1L, FIXED_INSTANT.minusSeconds(60));
        when(notificationRepository.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(n));

        notificationService.markRead(USER_ID, 1L);

        verify(notificationRepository, never()).save(any());
    }

    @Test
    void markRead_ownershipMismatch_throws404() {
        when(notificationRepository.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.markRead(USER_ID, 1L))
                .isInstanceOf(OwnershipMismatchException.class);
    }

    // ---- markAllRead ----

    @Test
    void markAllRead_delegatesToBulkUpdate() {
        notificationService.markAllRead(USER_ID);

        verify(notificationRepository).markAllReadForUser(USER_ID, FIXED_INSTANT);
    }

    // ---- preferences ----

    @Test
    void getPreferences_existingRow_returnsIt() {
        NotificationPreferences prefs = NotificationPreferences.builder().id(1L).userId(USER_ID)
                .emailEnabled(true).inAppEnabled(false).createdAt(FIXED_INSTANT).build();
        when(preferencesRepository.findByUserId(USER_ID)).thenReturn(Optional.of(prefs));

        NotificationPreferencesResponse response = notificationService.getPreferences(USER_ID);

        assertThat(response.emailEnabled()).isTrue();
        assertThat(response.inAppEnabled()).isFalse();
    }

    @Test
    void getPreferences_missingRow_throwsIllegalState_notSilentlyLazyCreated() {
        when(preferencesRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.getPreferences(USER_ID))
                .isInstanceOf(IllegalStateException.class);
        verify(preferencesRepository, never()).save(any());
    }

    @Test
    void updatePreferences_partial_onlyChangesProvidedField() {
        NotificationPreferences prefs = NotificationPreferences.builder().id(1L).userId(USER_ID)
                .emailEnabled(true).inAppEnabled(true).createdAt(FIXED_INSTANT).build();
        when(preferencesRepository.findByUserId(USER_ID)).thenReturn(Optional.of(prefs));
        when(preferencesRepository.save(any(NotificationPreferences.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateNotificationPreferencesRequest request = new UpdateNotificationPreferencesRequest(false, null);
        NotificationPreferencesResponse response = notificationService.updatePreferences(USER_ID, request);

        assertThat(response.emailEnabled()).isFalse();
        assertThat(response.inAppEnabled()).isTrue(); // unchanged
    }

    // ---- used by AlertEvaluationService ----

    @Test
    void isInAppEnabled_missingRow_defaultsTrue_forBackgroundPathOnly() {
        when(preferencesRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        assertThat(notificationService.isInAppEnabled(USER_ID)).isTrue();
    }

    @Test
    void isEmailEnabled_existingRowFalse_returnsFalse() {
        NotificationPreferences prefs = NotificationPreferences.builder().id(1L).userId(USER_ID)
                .emailEnabled(false).inAppEnabled(true).createdAt(FIXED_INSTANT).build();
        when(preferencesRepository.findByUserId(USER_ID)).thenReturn(Optional.of(prefs));

        assertThat(notificationService.isEmailEnabled(USER_ID)).isFalse();
    }

    @Test
    void createInApp_persistsWithPriceAlertType() {
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));

        Notification created = notificationService.createInApp(USER_ID, 1L, "title", "message");

        assertThat(created.getType()).isEqualTo(NotificationType.PRICE_ALERT);
        assertThat(created.getUserId()).isEqualTo(USER_ID);
        assertThat(created.getAlertId()).isEqualTo(1L);
        assertThat(created.getCreatedAt()).isEqualTo(FIXED_INSTANT);
    }
}

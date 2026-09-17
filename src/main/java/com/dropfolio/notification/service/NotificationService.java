package com.dropfolio.notification.service;

import com.dropfolio.common.exception.OwnershipMismatchException;
import com.dropfolio.common.exception.ValidationException;
import com.dropfolio.notification.dto.NotificationPreferencesResponse;
import com.dropfolio.notification.dto.NotificationResponse;
import com.dropfolio.notification.dto.UpdateNotificationPreferencesRequest;
import com.dropfolio.notification.entity.Notification;
import com.dropfolio.notification.entity.NotificationPreferences;
import com.dropfolio.notification.entity.NotificationType;
import com.dropfolio.notification.mapper.NotificationMapper;
import com.dropfolio.notification.repository.NotificationPreferencesRepository;
import com.dropfolio.notification.repository.NotificationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Owns both the user-facing read/preferences endpoints AND the two narrow methods
 * {@code AlertEvaluationService} calls to check/create notifications — TECHNICAL_SPEC.md §9.3.
 *
 * {@link #isInAppEnabled}/{@link #isEmailEnabled} default to {@code true} when a
 * {@code notification_preferences} row is somehow missing — a defensive fallback for the
 * background evaluation path only (never lets a missing row cascade into failing an entire
 * price-sync item), consistent with how M7 treats a failed Redis refresh as best-effort rather
 * than fatal. The two REST preference endpoints ({@link #getPreferences}/{@link
 * #updatePreferences}) do NOT fall back silently — a missing row there is a genuine backend
 * inconsistency (every user should have one via {@code AuthService.register()} or the V9
 * migration backfill) and is surfaced as an error rather than masked, per ERD.md §2.10's
 * explicit "not lazy-created" design intent.
 */
@Service
public class NotificationService {

    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationRepository notificationRepository;
    private final NotificationPreferencesRepository preferencesRepository;
    private final Clock clock;

    public NotificationService(NotificationRepository notificationRepository,
                                NotificationPreferencesRepository preferencesRepository,
                                Clock clock) {
        this.notificationRepository = notificationRepository;
        this.preferencesRepository = preferencesRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<NotificationResponse> list(Long userId, boolean unreadOnly, int page, int size) {
        if (page < 1) {
            throw new ValidationException("page must be >= 1");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ValidationException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        PageRequest pageRequest = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Notification> result = unreadOnly
                ? notificationRepository.findByUserIdAndReadAtIsNull(userId, pageRequest)
                : notificationRepository.findByUserId(userId, pageRequest);
        return result.map(NotificationMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return notificationRepository.countByUserIdAndReadAtIsNull(userId);
    }

    /** Idempotent — calling this on an already-read notification is a no-op, not an error. */
    @Transactional
    public void markRead(Long userId, Long id) {
        Notification notification = notificationRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new OwnershipMismatchException("Notification not found"));
        if (notification.getReadAt() == null) {
            notification.setReadAt(clock.instant());
            notificationRepository.save(notification);
        }
    }

    @Transactional
    public void markAllRead(Long userId) {
        notificationRepository.markAllReadForUser(userId, clock.instant());
    }

    @Transactional(readOnly = true)
    public NotificationPreferencesResponse getPreferences(Long userId) {
        NotificationPreferences prefs = requirePreferences(userId);
        return new NotificationPreferencesResponse(prefs.getEmailEnabled(), prefs.getInAppEnabled());
    }

    @Transactional
    public NotificationPreferencesResponse updatePreferences(Long userId, UpdateNotificationPreferencesRequest request) {
        NotificationPreferences prefs = requirePreferences(userId);
        if (request.emailEnabled() != null) {
            prefs.setEmailEnabled(request.emailEnabled());
        }
        if (request.inAppEnabled() != null) {
            prefs.setInAppEnabled(request.inAppEnabled());
        }
        prefs.setUpdatedAt(clock.instant());
        NotificationPreferences saved = preferencesRepository.save(prefs);
        return new NotificationPreferencesResponse(saved.getEmailEnabled(), saved.getInAppEnabled());
    }

    private NotificationPreferences requirePreferences(Long userId) {
        return preferencesRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "notification_preferences missing for user " + userId
                                + " — expected to always exist (AuthService.register() / V9 migration backfill)"));
    }

    // ---- Called by AlertEvaluationService (alert/) — TECHNICAL_SPEC.md §9.3 ----

    @Transactional(readOnly = true)
    public boolean isInAppEnabled(Long userId) {
        return preferencesRepository.findByUserId(userId)
                .map(p -> Boolean.TRUE.equals(p.getInAppEnabled()))
                .orElse(true);
    }

    @Transactional(readOnly = true)
    public boolean isEmailEnabled(Long userId) {
        return preferencesRepository.findByUserId(userId)
                .map(p -> Boolean.TRUE.equals(p.getEmailEnabled()))
                .orElse(true);
    }

    @Transactional
    public Notification createInApp(Long userId, Long alertId, String title, String message) {
        Notification notification = Notification.builder()
                .userId(userId)
                .alertId(alertId)
                .type(NotificationType.PRICE_ALERT)
                .title(title)
                .message(message)
                .createdAt(clock.instant())
                .build();
        return notificationRepository.save(notification);
    }
}

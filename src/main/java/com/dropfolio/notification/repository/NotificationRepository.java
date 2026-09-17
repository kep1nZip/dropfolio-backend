package com.dropfolio.notification.repository;

import com.dropfolio.notification.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** Mandatory ownership-scoped lookup — never {@code findById()} + a manual check. */
    Optional<Notification> findByIdAndUserId(Long id, Long userId);

    Page<Notification> findByUserId(Long userId, Pageable pageable);

    Page<Notification> findByUserIdAndReadAtIsNull(Long userId, Pageable pageable);

    /** API_CONTRACT.md §10 GET /notifications — {@code meta.unreadCount}. */
    long countByUserIdAndReadAtIsNull(Long userId);

    /**
     * Bulk update for {@code PATCH /notifications/read-all} — a single statement rather than
     * loading every unread row into memory to set {@code readAt} one by one.
     */
    @Modifying
    @Query("UPDATE Notification n SET n.readAt = :now WHERE n.userId = :userId AND n.readAt IS NULL")
    int markAllReadForUser(@Param("userId") Long userId, @Param("now") Instant now);

    /**
     * M8 Postman-testing gap fix: {@code notifications.alert_id} has {@code ON DELETE NO
     * ACTION} at the DB level (not {@code SET NULL}) — SQL Server rejected {@code SET NULL}
     * here as a multiple-cascade-path conflict with {@code users -> notifications} (V12
     * migration comment has the full detail). This method replicates the original SET-NULL
     * intent at the application layer: {@code AlertService.delete()} calls this BEFORE hard-
     * deleting the {@code price_alerts} row, so existing notifications are preserved (not
     * deleted) with their {@code alertId} cleared, and the subsequent alert delete never hits
     * an FK violation from a dangling reference.
     */
    @Modifying
    @Query("UPDATE Notification n SET n.alertId = NULL WHERE n.alertId = :alertId")
    int detachFromAlert(@Param("alertId") Long alertId);
}

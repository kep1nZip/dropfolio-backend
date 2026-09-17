package com.dropfolio.alert.service;

import com.dropfolio.alert.entity.AlertStatus;
import com.dropfolio.alert.entity.PriceAlert;
import com.dropfolio.alert.repository.PriceAlertRepository;
import com.dropfolio.item.entity.Item;
import com.dropfolio.item.entity.ItemType;
import com.dropfolio.notification.entity.Notification;
import com.dropfolio.notification.service.EmailService;
import com.dropfolio.notification.service.NotificationService;
import com.dropfolio.pricing.entity.ItemPrice;
import com.dropfolio.user.entity.User;
import com.dropfolio.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AlertEvaluationService} — the embedded {@code AlertEvaluationJob}
 * logic, M8 Implementation Authorization §18 "Alert evaluation" + §13 notification matrix.
 */
@ExtendWith(MockitoExtension.class)
class AlertEvaluationServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-11T00:00:00Z");
    private static final Long USER_ID = 7L;

    @Mock private PriceAlertRepository priceAlertRepository;
    @Mock private NotificationService notificationService;
    @Mock private EmailService emailService;
    @Mock private UserRepository userRepository;

    private Clock clock;
    private AlertEvaluationService alertEvaluationService;

    private Item item;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        alertEvaluationService = new AlertEvaluationService(
                priceAlertRepository, notificationService, emailService, userRepository, clock);
        item = Item.builder().id(5L).name("Revolution Case").type(ItemType.SKIN)
                .marketHashName("Revolution Case").isActive(true).build();
    }

    private PriceAlert alert(BigDecimal target, boolean notifyEmail, boolean notifyInApp) {
        return PriceAlert.builder().id(1L).userId(USER_ID).itemId(5L).targetPriceUsd(target)
                .notifyEmail(notifyEmail).notifyInApp(notifyInApp).status(AlertStatus.ACTIVE)
                .createdAt(FIXED_INSTANT).build();
    }

    private ItemPrice price(BigDecimal price, boolean available) {
        return ItemPrice.builder().itemId(5L).provider("STEAM_MARKET").priceUsd(price)
                .priceAvailable(available).fetchedAt(FIXED_INSTANT).createdAt(FIXED_INSTANT).build();
    }

    @Test
    void evaluate_noMatchingAlerts_doesNothing() {
        when(priceAlertRepository.findByItemIdAndStatus(5L, AlertStatus.ACTIVE)).thenReturn(List.of());

        alertEvaluationService.evaluate(item, price(new BigDecimal("5.00"), true));

        verifyNoInteractions(notificationService, emailService);
    }

    @Test
    void evaluate_priceUnavailable_skipsEvaluationEntirely() {
        alertEvaluationService.evaluate(item, price(null, false));

        verifyNoInteractions(priceAlertRepository, notificationService, emailService);
    }

    @Test
    void evaluate_activeAlert_priceMeetsCondition_triggersAndPersists() {
        PriceAlert a = alert(new BigDecimal("5.00"), true, true);
        when(priceAlertRepository.findByItemIdAndStatus(5L, AlertStatus.ACTIVE)).thenReturn(List.of(a));
        when(notificationService.isInAppEnabled(USER_ID)).thenReturn(true);
        when(notificationService.isEmailEnabled(USER_ID)).thenReturn(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(userWithEmail("user@example.com")));

        alertEvaluationService.evaluate(item, price(new BigDecimal("5.00"), true));

        ArgumentCaptor<PriceAlert> captor = ArgumentCaptor.forClass(PriceAlert.class);
        verify(priceAlertRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(AlertStatus.TRIGGERED);
        assertThat(captor.getValue().getTriggeredAt()).isEqualTo(FIXED_INSTANT);
    }

    @Test
    void evaluate_activeAlert_priceAboveTarget_alsoTriggers_conditionIsGreaterOrEqual() {
        PriceAlert a = alert(new BigDecimal("5.00"), true, true);
        when(priceAlertRepository.findByItemIdAndStatus(5L, AlertStatus.ACTIVE)).thenReturn(List.of(a));
        when(notificationService.isInAppEnabled(USER_ID)).thenReturn(true);

        alertEvaluationService.evaluate(item, price(new BigDecimal("6.00"), true));

        verify(priceAlertRepository).save(any(PriceAlert.class));
    }

    @Test
    void evaluate_priceBelowTarget_doesNotTrigger() {
        PriceAlert a = alert(new BigDecimal("5.00"), true, true);
        when(priceAlertRepository.findByItemIdAndStatus(5L, AlertStatus.ACTIVE)).thenReturn(List.of(a));

        alertEvaluationService.evaluate(item, price(new BigDecimal("4.99"), true));

        verify(priceAlertRepository, never()).save(any());
        verifyNoInteractions(notificationService, emailService);
    }

    /** M8 §2 lock: no auto re-arm. A TRIGGERED alert is never returned by the ACTIVE-only query, so it can never be re-evaluated. */
    @Test
    void evaluate_alreadyTriggeredAlert_neverAppearsInActiveQuery_soNeverRetriggered() {
        when(priceAlertRepository.findByItemIdAndStatus(5L, AlertStatus.ACTIVE)).thenReturn(List.of());

        alertEvaluationService.evaluate(item, price(new BigDecimal("999.00"), true));

        verify(priceAlertRepository, never()).save(any());
        verifyNoInteractions(notificationService, emailService);
    }

    // ---- notification matrix: notifyInApp x notifyEmail x inAppEnabled x emailEnabled ----

    @Test
    void trigger_inAppTrue_userInAppEnabledTrue_createsInAppNotification() {
        PriceAlert a = alert(new BigDecimal("5.00"), false, true);
        when(priceAlertRepository.findByItemIdAndStatus(5L, AlertStatus.ACTIVE)).thenReturn(List.of(a));
        when(notificationService.isInAppEnabled(USER_ID)).thenReturn(true);

        alertEvaluationService.evaluate(item, price(new BigDecimal("5.00"), true));

        verify(notificationService).createInApp(eq(USER_ID), eq(1L), any(), any());
        verifyNoInteractions(emailService);
    }

    @Test
    void trigger_inAppTrue_userInAppEnabledFalse_doesNotCreateInAppNotification() {
        PriceAlert a = alert(new BigDecimal("5.00"), false, true);
        when(priceAlertRepository.findByItemIdAndStatus(5L, AlertStatus.ACTIVE)).thenReturn(List.of(a));
        when(notificationService.isInAppEnabled(USER_ID)).thenReturn(false);

        alertEvaluationService.evaluate(item, price(new BigDecimal("5.00"), true));

        verify(notificationService, never()).createInApp(any(), any(), any(), any());
    }

    @Test
    void trigger_alertNotifyInAppFalse_neverCreatesInAppRegardlessOfUserPreference() {
        PriceAlert a = alert(new BigDecimal("5.00"), true, false);
        when(priceAlertRepository.findByItemIdAndStatus(5L, AlertStatus.ACTIVE)).thenReturn(List.of(a));
        when(notificationService.isEmailEnabled(USER_ID)).thenReturn(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(userWithEmail("user@example.com")));

        alertEvaluationService.evaluate(item, price(new BigDecimal("5.00"), true));

        verify(notificationService, never()).createInApp(any(), any(), any(), any());
        verify(emailService).enqueue(eq(null), eq("user@example.com"), any(), any());
    }

    @Test
    void trigger_alertNotifyEmailTrue_userEmailEnabledTrue_enqueuesEmail() {
        PriceAlert a = alert(new BigDecimal("5.00"), true, false);
        when(priceAlertRepository.findByItemIdAndStatus(5L, AlertStatus.ACTIVE)).thenReturn(List.of(a));
        when(notificationService.isEmailEnabled(USER_ID)).thenReturn(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(userWithEmail("user@example.com")));

        alertEvaluationService.evaluate(item, price(new BigDecimal("5.00"), true));

        verify(emailService).enqueue(eq(null), eq("user@example.com"), any(), any());
    }

    @Test
    void trigger_alertNotifyEmailTrue_userEmailEnabledFalse_doesNotEnqueueEmail() {
        PriceAlert a = alert(new BigDecimal("5.00"), true, false);
        when(priceAlertRepository.findByItemIdAndStatus(5L, AlertStatus.ACTIVE)).thenReturn(List.of(a));
        when(notificationService.isEmailEnabled(USER_ID)).thenReturn(false);

        alertEvaluationService.evaluate(item, price(new BigDecimal("5.00"), true));

        verifyNoInteractions(emailService);
    }

    @Test
    void trigger_alertNotifyEmailFalse_neverEnqueuesRegardlessOfUserPreference() {
        PriceAlert a = alert(new BigDecimal("5.00"), false, true);
        when(priceAlertRepository.findByItemIdAndStatus(5L, AlertStatus.ACTIVE)).thenReturn(List.of(a));
        when(notificationService.isInAppEnabled(USER_ID)).thenReturn(true);

        alertEvaluationService.evaluate(item, price(new BigDecimal("5.00"), true));

        verifyNoInteractions(emailService);
    }

    @Test
    void trigger_bothChannelsAllowed_emailLinksToTheCreatedNotificationId() {
        PriceAlert a = alert(new BigDecimal("5.00"), true, true);
        when(priceAlertRepository.findByItemIdAndStatus(5L, AlertStatus.ACTIVE)).thenReturn(List.of(a));
        when(notificationService.isInAppEnabled(USER_ID)).thenReturn(true);
        when(notificationService.isEmailEnabled(USER_ID)).thenReturn(true);
        Notification created = Notification.builder().id(55L).userId(USER_ID).alertId(1L).build();
        when(notificationService.createInApp(any(), any(), any(), any())).thenReturn(created);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(userWithEmail("user@example.com")));

        alertEvaluationService.evaluate(item, price(new BigDecimal("5.00"), true));

        verify(emailService).enqueue(eq(55L), eq("user@example.com"), any(), any());
    }

    @Test
    void trigger_bothChannelsDisallowed_createsNothing() {
        PriceAlert a = alert(new BigDecimal("5.00"), true, true);
        when(priceAlertRepository.findByItemIdAndStatus(5L, AlertStatus.ACTIVE)).thenReturn(List.of(a));
        when(notificationService.isInAppEnabled(USER_ID)).thenReturn(false);
        when(notificationService.isEmailEnabled(USER_ID)).thenReturn(false);

        alertEvaluationService.evaluate(item, price(new BigDecimal("5.00"), true));

        verifyNoInteractions(emailService);
        verify(notificationService, never()).createInApp(any(), any(), any(), any());
        verify(priceAlertRepository).save(any(PriceAlert.class));
    }

    @Test
    void trigger_userHasNoEmail_skipsEmailEnqueue_doesNotThrow() {
        PriceAlert a = alert(new BigDecimal("5.00"), true, false);
        when(priceAlertRepository.findByItemIdAndStatus(5L, AlertStatus.ACTIVE)).thenReturn(List.of(a));
        when(notificationService.isEmailEnabled(USER_ID)).thenReturn(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        alertEvaluationService.evaluate(item, price(new BigDecimal("5.00"), true));

        verifyNoInteractions(emailService);
    }

    private User userWithEmail(String email) {
        User u = new User();
        u.setId(USER_ID);
        u.setEmail(email);
        return u;
    }
}

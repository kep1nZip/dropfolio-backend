package com.dropfolio.alert.service;

import com.dropfolio.alert.entity.AlertStatus;
import com.dropfolio.alert.entity.PriceAlert;
import com.dropfolio.alert.repository.PriceAlertRepository;
import com.dropfolio.item.entity.Item;
import com.dropfolio.notification.entity.Notification;
import com.dropfolio.notification.service.EmailService;
import com.dropfolio.notification.service.NotificationService;
import com.dropfolio.pricing.entity.ItemPrice;
import com.dropfolio.user.entity.User;
import com.dropfolio.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.RoundingMode;
import java.time.Clock;
import java.util.List;

/**
 * {@code AlertEvaluationJob} — embedded, NOT a separate scheduled job (M8 Implementation
 * Authorization §9, TECHNICAL_SPEC.md §9.3). Called by {@code PriceSyncService.syncItem(...)}
 * for every item that just got a successful price, inside the SAME per-item transaction as the
 * {@code item_prices} insert (M8 §10) — see {@code PriceSyncService} for the transaction
 * boundary itself; this class only contains the evaluation logic.
 *
 * NO AUTO RE-ARM (M8 §2, locked): once {@code TRIGGERED}, an alert is never flipped back to
 * {@code ACTIVE} by this class — only {@link AlertService#update} (explicit user action) can
 * do that. This is why {@link #evaluate} only ever queries {@code status = ACTIVE} alerts —
 * a {@code TRIGGERED} alert simply never appears in that query again, so it can't be
 * re-triggered without user action, with no extra "already triggered" check needed.
 */
@Service
public class AlertEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluationService.class);

    private final PriceAlertRepository priceAlertRepository;
    private final NotificationService notificationService;
    private final EmailService emailService;
    private final UserRepository userRepository;
    private final Clock clock;

    public AlertEvaluationService(PriceAlertRepository priceAlertRepository,
                                   NotificationService notificationService,
                                   EmailService emailService,
                                   UserRepository userRepository,
                                   Clock clock) {
        this.priceAlertRepository = priceAlertRepository;
        this.notificationService = notificationService;
        this.emailService = emailService;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    /**
     * @param item     the item that was just priced.
     * @param newPrice the just-persisted {@code item_prices} row for it. If the price wasn't
     *                 actually available ({@code priceAvailable=false}), there is nothing to
     *                 evaluate a "price reached target" condition against, so this is a no-op.
     */
    public void evaluate(Item item, ItemPrice newPrice) {
        if (!Boolean.TRUE.equals(newPrice.getPriceAvailable()) || newPrice.getPriceUsd() == null) {
            return;
        }
        List<PriceAlert> activeAlerts = priceAlertRepository.findByItemIdAndStatus(item.getId(), AlertStatus.ACTIVE);
        for (PriceAlert alert : activeAlerts) {
            if (newPrice.getPriceUsd().compareTo(alert.getTargetPriceUsd()) >= 0) {
                trigger(alert, item, newPrice);
            }
        }
    }

    private void trigger(PriceAlert alert, Item item, ItemPrice newPrice) {
        alert.setStatus(AlertStatus.TRIGGERED);
        alert.setTriggeredAt(clock.instant());
        priceAlertRepository.save(alert);

        String priceText = "$" + newPrice.getPriceUsd().setScale(2, RoundingMode.HALF_UP);
        String title = item.getName() + " reached " + priceText;
        String message = "Your " + item.getName() + " alert reached the target price of $"
                + alert.getTargetPriceUsd().setScale(2, RoundingMode.HALF_UP) + ".";

        // Both channels are gated by the SAME two-level rule (alert-level flag AND user-level
        // preference) — TECHNICAL_SPEC.md §9.3 states this explicitly for email; extended here
        // to in-app too since `notifyInApp` is a real, independently-validated per-alert field
        // (API_CONTRACT.md §9: "minimal salah satu dari notifyEmail/notifyInApp harus true"),
        // which would be meaningless if it never actually gated anything. Flagged as an
        // interpretation note in the M8 completion report, not silently assumed.
        Notification notification = null;
        boolean inAppAllowed = Boolean.TRUE.equals(alert.getNotifyInApp()) && notificationService.isInAppEnabled(alert.getUserId());
        if (inAppAllowed) {
            notification = notificationService.createInApp(alert.getUserId(), alert.getId(), title, message);
        }

        boolean emailAllowed = Boolean.TRUE.equals(alert.getNotifyEmail()) && notificationService.isEmailEnabled(alert.getUserId());
        if (emailAllowed) {
            User user = userRepository.findById(alert.getUserId()).orElse(null);
            if (user != null && user.getEmail() != null) {
                Long notificationId = notification != null ? notification.getId() : null;
                emailService.enqueue(notificationId, user.getEmail(), title, message);
            } else {
                log.warn("alert {} triggered but user {} has no resolvable email — skipping email enqueue",
                        alert.getId(), alert.getUserId());
            }
        }
    }
}

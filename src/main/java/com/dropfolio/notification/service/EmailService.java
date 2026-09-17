package com.dropfolio.notification.service;

import com.dropfolio.notification.entity.EmailJob;
import com.dropfolio.notification.entity.EmailJobStatus;
import com.dropfolio.notification.repository.EmailJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Enqueue-only — M8 Implementation Authorization §11/§13: "Jangan mengirim email secara
 * langsung dari request thread." Actual SMTP send happens later, asynchronously, in
 * {@link EmailWorker}. This keeps {@code enqueue(...)} a plain, fast DB insert that
 * participates cleanly in the same per-item transaction {@code PriceSyncService}/{@code
 * AlertEvaluationService} run under (M8 §10) — no network call anywhere near that transaction.
 */
@Service
public class EmailService {

    private final EmailJobRepository emailJobRepository;
    private final Clock clock;

    public EmailService(EmailJobRepository emailJobRepository, Clock clock) {
        this.emailJobRepository = emailJobRepository;
        this.clock = clock;
    }

    @Transactional
    public EmailJob enqueue(Long notificationId, String recipientEmail, String subject, String body) {
        EmailJob job = EmailJob.builder()
                .notificationId(notificationId)
                .recipientEmail(recipientEmail)
                .subject(subject)
                .body(body)
                .status(EmailJobStatus.PENDING)
                .attemptCount(0)
                .maxAttempts(3)
                .createdAt(clock.instant())
                .build();
        return emailJobRepository.save(job);
    }
}

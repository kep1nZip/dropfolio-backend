package com.dropfolio.notification.service;

import com.dropfolio.notification.entity.EmailJob;
import com.dropfolio.notification.entity.EmailJobStatus;
import com.dropfolio.notification.repository.EmailJobRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;

/**
 * Polls {@code email_jobs} for {@code PENDING} rows and sends them via the existing
 * {@code spring-boot-starter-mail}/{@code spring.mail.*} infrastructure — M8 Implementation
 * Authorization §11: no new email provider, locked {@code @Scheduled(fixedDelay=30000)}.
 *
 * <b>Two-level retry (interpretation note, flagged not silently assumed):</b> each individual
 * send attempt is wrapped in the SAME resilience4j pattern M7 established for
 * {@code priceProvider} — Retry (named instance {@code emailSmtp}, outer) wrapping
 * CircuitBreaker (also {@code emailSmtp}, inner, NOT {@code steamApiClient}/{@code
 * priceProvider} — M8 §12), configured as 1 initial call + 3 retries with 1s/2s/4s backoff
 * (same {@code max-attempts: 4} reading as M7's {@code priceProvider} instance). That covers a
 * single poll tick's send attempt. Separately, {@code email_jobs.attempt_count}/{@code
 * max_attempts} (ERD.md §2.12) track an OUTER ceiling across poll ticks: if a job still fails
 * after exhausting the inner resilience4j burst, it is left {@code PENDING} (not immediately
 * {@code FAILED}) so the NEXT poll tick, 30s later, tries again — only once {@code
 * attemptCount >= maxAttempts} (3 outer ticks) does the job become permanently {@code FAILED}.
 * This reconciles the M7-pattern backoff language with the ERD's own attempt-tracking columns,
 * which would otherwise be redundant with the inner retry.
 */
@Component
public class EmailWorker {

    private static final Logger log = LoggerFactory.getLogger(EmailWorker.class);
    private static final String EMAIL_SMTP_INSTANCE = "emailSmtp";
    private static final int BATCH_SIZE = 20;
    private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;

    private final EmailJobRepository emailJobRepository;
    private final JavaMailSender mailSender;
    private final CircuitBreaker emailSmtpCircuitBreaker;
    private final Retry emailSmtpRetry;
    private final Clock clock;

    public EmailWorker(EmailJobRepository emailJobRepository,
                        JavaMailSender mailSender,
                        CircuitBreakerRegistry circuitBreakerRegistry,
                        RetryRegistry retryRegistry,
                        Clock clock) {
        this.emailJobRepository = emailJobRepository;
        this.mailSender = mailSender;
        this.emailSmtpCircuitBreaker = circuitBreakerRegistry.circuitBreaker(EMAIL_SMTP_INSTANCE);
        this.emailSmtpRetry = retryRegistry.retry(EMAIL_SMTP_INSTANCE);
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 30000)
    public void pollAndSend() {
        List<EmailJob> pending = emailJobRepository.findByStatusOrderByCreatedAtAsc(
                EmailJobStatus.PENDING, PageRequest.of(0, BATCH_SIZE));
        for (EmailJob job : pending) {
            sendOne(job);
        }
    }

    private void sendOne(EmailJob job) {
        try {
            Supplier<Void> call = () -> {
                sendMail(job);
                return null;
            };
            Supplier<Void> resilient =
                    Retry.decorateSupplier(emailSmtpRetry, CircuitBreaker.decorateSupplier(emailSmtpCircuitBreaker, call));
            resilient.get();
            job.setStatus(EmailJobStatus.SENT);
            job.setSentAt(clock.instant());
            job.setErrorMessage(null);
        } catch (Exception e) {
            job.setAttemptCount(job.getAttemptCount() + 1);
            job.setLastAttemptAt(clock.instant());
            job.setErrorMessage(truncate(e.getMessage()));
            if (job.getAttemptCount() >= job.getMaxAttempts()) {
                job.setStatus(EmailJobStatus.FAILED);
                log.error("email job {} permanently failed after {} outer attempts", job.getId(), job.getAttemptCount());
            } else {
                log.warn("email job {} failed (outer attempt {}/{}), will retry next poll tick",
                        job.getId(), job.getAttemptCount(), job.getMaxAttempts());
            }
        } finally {
            emailJobRepository.save(job);
        }
    }

    private void sendMail(EmailJob job) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(job.getRecipientEmail());
        message.setSubject(job.getSubject());
        message.setText(job.getBody());
        mailSender.send(message);
    }

    private String truncate(String message) {
        if (message == null) {
            return "Unknown error";
        }
        return message.length() > MAX_ERROR_MESSAGE_LENGTH ? message.substring(0, MAX_ERROR_MESSAGE_LENGTH) : message;
    }
}

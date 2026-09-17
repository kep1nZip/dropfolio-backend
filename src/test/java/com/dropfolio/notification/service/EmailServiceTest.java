package com.dropfolio.notification.service;

import com.dropfolio.notification.entity.EmailJob;
import com.dropfolio.notification.entity.EmailJobStatus;
import com.dropfolio.notification.repository.EmailJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EmailService} — M8 Implementation Authorization §18 Email "enqueue".
 * Also documents, by construction, that enqueue is a plain repository save with no
 * {@code JavaMailSender}/SMTP dependency anywhere in this class — i.e. it is structurally
 * impossible for {@link EmailService} to send mail directly from the request thread (§13).
 */
@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-11T00:00:00Z");

    @Mock private EmailJobRepository emailJobRepository;

    private EmailService emailService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        emailService = new EmailService(emailJobRepository, clock);
    }

    @Test
    void enqueue_createsPendingJobWithZeroAttempts() {
        when(emailJobRepository.save(any(EmailJob.class))).thenAnswer(inv -> inv.getArgument(0));

        EmailJob job = emailService.enqueue(10L, "user@example.com", "Subject", "Body");

        ArgumentCaptor<EmailJob> captor = ArgumentCaptor.forClass(EmailJob.class);
        verify(emailJobRepository).save(captor.capture());
        EmailJob saved = captor.getValue();
        assertThat(saved.getNotificationId()).isEqualTo(10L);
        assertThat(saved.getRecipientEmail()).isEqualTo("user@example.com");
        assertThat(saved.getSubject()).isEqualTo("Subject");
        assertThat(saved.getBody()).isEqualTo("Body");
        assertThat(saved.getStatus()).isEqualTo(EmailJobStatus.PENDING);
        assertThat(saved.getAttemptCount()).isEqualTo(0);
        assertThat(saved.getMaxAttempts()).isEqualTo(3);
        assertThat(saved.getCreatedAt()).isEqualTo(FIXED_INSTANT);
        assertThat(job.getStatus()).isEqualTo(EmailJobStatus.PENDING);
    }

    @Test
    void enqueue_nullNotificationId_allowed() {
        when(emailJobRepository.save(any(EmailJob.class))).thenAnswer(inv -> inv.getArgument(0));

        EmailJob job = emailService.enqueue(null, "user@example.com", "Subject", "Body");

        assertThat(job.getNotificationId()).isNull();
    }
}

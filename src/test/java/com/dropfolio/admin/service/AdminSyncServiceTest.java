package com.dropfolio.admin.service;

import com.dropfolio.admin.dto.SyncJobResponse;
import com.dropfolio.admin.dto.TriggerSyncResponse;
import com.dropfolio.common.audit.AuditLogService;
import com.dropfolio.common.exception.RateLimitedException;
import com.dropfolio.common.exception.ResourceNotFoundException;
import com.dropfolio.common.exception.SyncAlreadyRunningException;
import com.dropfolio.common.exception.ValidationException;
import com.dropfolio.common.ratelimit.AdminSyncRateLimitProperties;
import com.dropfolio.scheduler.PriceSyncJob;
import com.dropfolio.scheduler.entity.JobStatus;
import com.dropfolio.scheduler.entity.JobType;
import com.dropfolio.scheduler.entity.SyncJob;
import com.dropfolio.scheduler.entity.TriggeredBy;
import com.dropfolio.scheduler.lock.SchedulerLockService;
import com.dropfolio.scheduler.repository.SyncJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminSyncService} — M7 Implementation Authorization §22, verifying the
 * exact ordering locked by PM Decision M7 §9: rate limit -> lock -> (create job + audit,
 * atomically) -> async dispatch -> 202-shape response; and the 409/rollback paths that must
 * NOT create a job row or an audit row.
 */
@ExtendWith(MockitoExtension.class)
class AdminSyncServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-10T00:00:00Z");
    private static final Long ADMIN_ID = 7L;
    private static final String CLIENT_IP = "203.0.113.5";

    @Mock private SchedulerLockService lockService;
    @Mock private SyncJobRepository syncJobRepository;
    @Mock private AuditLogService auditLogService;
    @Mock private PriceSyncJob priceSyncJob;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> valueOperations;

    private TransactionOperations transactionOperations;
    private AdminSyncRateLimitProperties rateLimitProperties;
    private Clock clock;
    private AdminSyncService adminSyncService;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        rateLimitProperties = new AdminSyncRateLimitProperties(60, 1);
        // Synchronous "transaction" stub — just runs the callback immediately, no real datasource.
        transactionOperations = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
        adminSyncService = new AdminSyncService(lockService, syncJobRepository, auditLogService,
                priceSyncJob, transactionOperations, redisTemplate, rateLimitProperties, clock);
    }

    private void stubRateLimitUnderLimit() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(any())).thenReturn(1L);
    }

    @Test
    void triggerPriceSync_success_createsJobAndAudit_dispatchesAsync_returns202Shape() {
        stubRateLimitUnderLimit();
        when(lockService.tryAcquire(PriceSyncJob.PRICE_SYNC_LOCK_NAME)).thenReturn("owner-1");
        when(syncJobRepository.save(any(SyncJob.class))).thenAnswer(inv -> {
            SyncJob job = inv.getArgument(0);
            job.setId(100L);
            return job;
        });

        TriggerSyncResponse response = adminSyncService.triggerPriceSync(ADMIN_ID, CLIENT_IP);

        assertThat(response.syncJobId()).isEqualTo(100L);
        assertThat(response.status()).isEqualTo("RUNNING");
        verify(auditLogService).record(eq(ADMIN_ID), eq("ADMIN_TRIGGER_SYNC"), eq("sync_jobs"), eq(100L), any(), eq(CLIENT_IP));
        verify(priceSyncJob).runTriggeredByAdmin(100L, "owner-1");
        verify(lockService, never()).release(any(), any());
    }

    @Test
    void triggerPriceSync_savedJobHasCorrectFields() {
        stubRateLimitUnderLimit();
        when(lockService.tryAcquire(PriceSyncJob.PRICE_SYNC_LOCK_NAME)).thenReturn("owner-1");
        when(syncJobRepository.save(any(SyncJob.class))).thenAnswer(inv -> inv.getArgument(0));

        adminSyncService.triggerPriceSync(ADMIN_ID, CLIENT_IP);

        var captor = org.mockito.ArgumentCaptor.forClass(SyncJob.class);
        verify(syncJobRepository).save(captor.capture());
        SyncJob saved = captor.getValue();
        assertThat(saved.getJobType()).isEqualTo(JobType.PRICE_SYNC);
        assertThat(saved.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(saved.getTriggeredBy()).isEqualTo(TriggeredBy.ADMIN);
        assertThat(saved.getTriggeredByUserId()).isEqualTo(ADMIN_ID);
        assertThat(saved.getStartedAt()).isEqualTo(FIXED_INSTANT);
    }

    @Test
    void triggerPriceSync_lockUnavailable_throws409_noJobRow_noAuditRow() {
        stubRateLimitUnderLimit();
        when(lockService.tryAcquire(PriceSyncJob.PRICE_SYNC_LOCK_NAME)).thenReturn(null);

        assertThatThrownBy(() -> adminSyncService.triggerPriceSync(ADMIN_ID, CLIENT_IP))
                .isInstanceOf(SyncAlreadyRunningException.class);

        verify(syncJobRepository, never()).save(any());
        verifyNoInteractions(auditLogService);
        verify(priceSyncJob, never()).runTriggeredByAdmin(anyLong(), any());
    }

    @Test
    void triggerPriceSync_rateLimited_throwsBeforeTouchingLock() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(any())).thenReturn(2L); // exceeds maxRequests=1
        when(redisTemplate.getExpire(any(), any())).thenReturn(45L);

        assertThatThrownBy(() -> adminSyncService.triggerPriceSync(ADMIN_ID, CLIENT_IP))
                .isInstanceOf(RateLimitedException.class);

        verify(lockService, never()).tryAcquire(any());
        verifyNoInteractions(auditLogService);
    }

    @Test
    void triggerPriceSync_jobCreationFails_releasesLockImmediately_doesNotDispatch() {
        stubRateLimitUnderLimit();
        when(lockService.tryAcquire(PriceSyncJob.PRICE_SYNC_LOCK_NAME)).thenReturn("owner-1");
        when(syncJobRepository.save(any(SyncJob.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> adminSyncService.triggerPriceSync(ADMIN_ID, CLIENT_IP))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(lockService).release(PriceSyncJob.PRICE_SYNC_LOCK_NAME, "owner-1");
        verify(priceSyncJob, never()).runTriggeredByAdmin(anyLong(), any());
    }

    @Test
    void triggerPriceSync_auditFails_rollsBackJobCreation_releasesLock() {
        stubRateLimitUnderLimit();
        when(lockService.tryAcquire(PriceSyncJob.PRICE_SYNC_LOCK_NAME)).thenReturn("owner-1");
        when(syncJobRepository.save(any(SyncJob.class))).thenAnswer(inv -> inv.getArgument(0));
        when(auditLogService.record(any(), any(), any(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("audit insert failed"));

        assertThatThrownBy(() -> adminSyncService.triggerPriceSync(ADMIN_ID, CLIENT_IP))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(lockService).release(PriceSyncJob.PRICE_SYNC_LOCK_NAME, "owner-1");
        verify(priceSyncJob, never()).runTriggeredByAdmin(anyLong(), any());
    }

    @Test
    void getById_notFound_throwsResourceNotFound() {
        when(syncJobRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminSyncService.getById(999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getById_found_returnsMappedResponse() {
        SyncJob job = SyncJob.builder().id(1L).jobType(JobType.PRICE_SYNC).status(JobStatus.SUCCESS)
                .itemsProcessed(10).triggeredBy(TriggeredBy.SCHEDULER).startedAt(FIXED_INSTANT)
                .finishedAt(FIXED_INSTANT).build();
        when(syncJobRepository.findById(1L)).thenReturn(Optional.of(job));

        SyncJobResponse response = adminSyncService.getById(1L);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.itemsProcessed()).isEqualTo(10);
    }

    @Test
    void list_invalidPage_throwsValidationException() {
        assertThatThrownBy(() -> adminSyncService.list(null, null, 0, 20, null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void list_invalidSize_throwsValidationException() {
        assertThatThrownBy(() -> adminSyncService.list(null, null, 1, 0, null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void list_disallowedSortField_throwsValidationException() {
        assertThatThrownBy(() -> adminSyncService.list(null, null, 1, 20, "itemsProcessed,desc"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void list_defaultsAndDelegatesToRepository() {
        SyncJob job = SyncJob.builder().id(1L).jobType(JobType.PRICE_SYNC).status(JobStatus.SUCCESS)
                .itemsProcessed(5).triggeredBy(TriggeredBy.SCHEDULER).startedAt(FIXED_INSTANT).build();
        when(syncJobRepository.findAll(org.mockito.ArgumentMatchers.<org.springframework.data.jpa.domain.Specification<SyncJob>>any(),
                any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(job)));

        Page<SyncJobResponse> result = adminSyncService.list(null, null, 1, 20, null);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).id()).isEqualTo(1L);
    }
}

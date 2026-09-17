package com.dropfolio.admin.service;

import com.dropfolio.admin.dto.SyncJobResponse;
import com.dropfolio.admin.dto.TriggerSyncResponse;
import com.dropfolio.admin.mapper.SyncJobMapper;
import com.dropfolio.common.audit.AuditLogService;
import com.dropfolio.common.cache.CacheKeys;
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
import com.dropfolio.scheduler.repository.SyncJobSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Implements the exact ordering PM Decision M7 §9 locked:
 * <pre>
 * Authorization (SecurityConfig, before this class is ever reached)
 *   -> per-admin rate limit
 *   -> acquire global price-sync lock
 *        -> unavailable: 409 SYNC_ALREADY_RUNNING, no sync_jobs row, no audit row
 *        -> acquired:
 *             create RUNNING sync_jobs row + record ADMIN_TRIGGER_SYNC audit row,
 *             atomically (see {@link TransactionOperations} usage below)
 *             -> if that fails: release the lock immediately, propagate the error,
 *                do NOT leave a dangling RUNNING row or a "sync started" audit row
 *             -> if it succeeds: start background execution, return 202
 * </pre>
 */
@org.springframework.stereotype.Service
public class AdminSyncService {

    private static final Set<String> SORT_WHITELIST = Set.of("startedAt");
    private static final int MAX_PAGE_SIZE = 100;

    private final SchedulerLockService lockService;
    private final SyncJobRepository syncJobRepository;
    private final AuditLogService auditLogService;
    private final PriceSyncJob priceSyncJob;
    private final TransactionOperations transactionOperations;
    private final RedisTemplate<String, Object> redisTemplate;
    private final AdminSyncRateLimitProperties rateLimitProperties;
    private final Clock clock;

    public AdminSyncService(SchedulerLockService lockService,
                             SyncJobRepository syncJobRepository,
                             AuditLogService auditLogService,
                             PriceSyncJob priceSyncJob,
                             TransactionOperations transactionOperations,
                             RedisTemplate<String, Object> redisTemplate,
                             AdminSyncRateLimitProperties rateLimitProperties,
                             Clock clock) {
        this.lockService = lockService;
        this.syncJobRepository = syncJobRepository;
        this.auditLogService = auditLogService;
        this.priceSyncJob = priceSyncJob;
        this.transactionOperations = transactionOperations;
        this.redisTemplate = redisTemplate;
        this.rateLimitProperties = rateLimitProperties;
        this.clock = clock;
    }

    public TriggerSyncResponse triggerPriceSync(Long adminUserId, String clientIp) {
        enforceRateLimit(adminUserId);

        String ownerToken = lockService.tryAcquire(PriceSyncJob.PRICE_SYNC_LOCK_NAME);
        if (ownerToken == null) {
            throw new SyncAlreadyRunningException("A price sync job is already running");
        }

        SyncJob job;
        try {
            job = transactionOperations.execute(status -> {
                SyncJob created = SyncJob.builder()
                        .jobType(JobType.PRICE_SYNC)
                        .status(JobStatus.RUNNING)
                        .itemsProcessed(0)
                        .triggeredBy(TriggeredBy.ADMIN)
                        .triggeredByUserId(adminUserId)
                        .startedAt(clock.instant())
                        .build();
                created = syncJobRepository.save(created);
                auditLogService.record(adminUserId, "ADMIN_TRIGGER_SYNC", "sync_jobs", created.getId(), null, clientIp);
                return created;
            });
        } catch (RuntimeException creationFailure) {
            // Failure before the job was successfully created + audited — release the lock
            // immediately, never create an audit row implying a sync started (PM Decision §9).
            lockService.release(PriceSyncJob.PRICE_SYNC_LOCK_NAME, ownerToken);
            throw creationFailure;
        }

        priceSyncJob.runTriggeredByAdmin(job.getId(), ownerToken);

        return new TriggerSyncResponse(job.getId(), job.getStatus().name());
    }

    public SyncJobResponse getById(Long id) {
        SyncJob job = syncJobRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sync job not found"));
        return SyncJobMapper.toResponse(job);
    }

    public Page<SyncJobResponse> list(JobType jobType, JobStatus jobStatus, int page, int size, String sort) {
        if (page < 1) {
            throw new ValidationException("page must be >= 1");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ValidationException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        Sort springSort = parseSort(sort);
        PageRequest pageRequest = PageRequest.of(page - 1, size, springSort);
        var spec = SyncJobSpecifications.ofJobType(jobType).and(SyncJobSpecifications.ofStatus(jobStatus));
        return syncJobRepository.findAll(spec, pageRequest).map(SyncJobMapper::toResponse);
    }

    /**
     * API_CONTRACT.md §11: 1 request / 60 seconds / admin. Enforced here (service layer),
     * NOT via a {@code RateLimitFilter} instance, because identity (the admin's user id) is
     * only available after {@code JwtAuthenticationFilter} runs, which is later in the chain
     * than where the existing IP-keyed rate limit filters sit (see
     * {@link AdminSyncRateLimitProperties} javadoc for the full rationale). Reuses the same
     * fixed-window-via-INCR-and-EXPIRE algorithm as {@code RateLimitFilter}, keyed by admin
     * user id instead of client IP.
     */
    private void enforceRateLimit(Long adminUserId) {
        String key = CacheKeys.rateLimit("admin-sync-trigger", String.valueOf(adminUserId));
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, Duration.ofSeconds(rateLimitProperties.windowSeconds()));
        }
        if (count != null && count > rateLimitProperties.maxRequests()) {
            Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
            throw new RateLimitedException("Too many sync trigger requests, retry after "
                    + (ttl != null && ttl > 0 ? ttl : rateLimitProperties.windowSeconds()) + " seconds");
        }
    }

    private Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "startedAt");
        }
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        if (!SORT_WHITELIST.contains(field)) {
            throw new ValidationException("sort field not allowed: " + field + " (allowed: " + SORT_WHITELIST + ")");
        }
        Sort.Direction direction = parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim())
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        return Sort.by(direction, field);
    }
}

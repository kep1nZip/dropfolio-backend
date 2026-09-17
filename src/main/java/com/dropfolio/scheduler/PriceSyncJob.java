package com.dropfolio.scheduler;

import com.dropfolio.item.entity.Item;
import com.dropfolio.item.repository.ItemRepository;
import com.dropfolio.pricing.exception.ItemPriceSyncException;
import com.dropfolio.pricing.service.PriceSyncService;
import com.dropfolio.scheduler.entity.JobStatus;
import com.dropfolio.scheduler.entity.JobType;
import com.dropfolio.scheduler.entity.SyncJob;
import com.dropfolio.scheduler.entity.TriggeredBy;
import com.dropfolio.scheduler.lock.SchedulerLockService;
import com.dropfolio.scheduler.repository.SyncJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

import static com.dropfolio.scheduler.config.SchedulerAsyncConfig.PRICE_SYNC_EXECUTOR;

/**
 * Orchestrates one {@code PriceSyncJob} run — M7 Implementation Authorization §1 flow:
 * fetch price (active items only, §5) → persist {@code item_prices} → refresh Redis. No
 * {@code AlertEvaluationJob} step (§2, deferred).
 *
 * Two entry points share the same lock name and the same {@link #execute(SyncJob)} core:
 * <ul>
 *   <li>{@link #scheduledPriceSync()} — the periodic tick. Creates its own RUNNING row and
 *       skips SILENTLY (no row created at all) if the lock is already held (§14).</li>
 *   <li>{@link #runTriggeredByAdmin(Long, String)} — invoked by {@code AdminSyncService}
 *       AFTER it has already acquired the lock and created/committed the RUNNING row + audit
 *       row; this method only executes the batch and releases the lock in its {@code finally}.</li>
 * </ul>
 */
@Component
public class PriceSyncJob {

    private static final Logger log = LoggerFactory.getLogger(PriceSyncJob.class);
    public static final String PRICE_SYNC_LOCK_NAME = "price-sync";
    private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;

    private final SchedulerLockService lockService;
    private final SyncJobRepository syncJobRepository;
    private final ItemRepository itemRepository;
    private final PriceSyncService priceSyncService;
    private final Clock clock;

    public PriceSyncJob(SchedulerLockService lockService,
                         SyncJobRepository syncJobRepository,
                         ItemRepository itemRepository,
                         PriceSyncService priceSyncService,
                         Clock clock) {
        this.lockService = lockService;
        this.syncJobRepository = syncJobRepository;
        this.itemRepository = itemRepository;
        this.priceSyncService = priceSyncService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${scheduler.price-sync.fixed-delay-ms:900000}")
    public void scheduledPriceSync() {
        String ownerToken = lockService.tryAcquire(PRICE_SYNC_LOCK_NAME);
        if (ownerToken == null) {
            // Skip silently — no sync_jobs row for a tick that never acquired the lock (§14).
            log.debug("price-sync lock already held, skipping this scheduler tick");
            return;
        }
        try {
            SyncJob job = SyncJob.builder()
                    .jobType(JobType.PRICE_SYNC)
                    .status(JobStatus.RUNNING)
                    .itemsProcessed(0)
                    .triggeredBy(TriggeredBy.SCHEDULER)
                    .triggeredByUserId(null)
                    .startedAt(clock.instant())
                    .build();
            job = syncJobRepository.save(job);
            execute(job);
        } finally {
            lockService.release(PRICE_SYNC_LOCK_NAME, ownerToken);
        }
    }

    /**
     * Runs off the dedicated {@code priceSyncExecutor} pool, never the HTTP request thread
     * (§14). The lock and the RUNNING {@link SyncJob} row are already in place by the time
     * this is invoked — see {@code AdminSyncService#triggerPriceSync}.
     */
    @Async(PRICE_SYNC_EXECUTOR)
    public void runTriggeredByAdmin(Long syncJobId, String lockOwnerToken) {
        try {
            SyncJob job = syncJobRepository.findById(syncJobId)
                    .orElseThrow(() -> new IllegalStateException("sync job not found: " + syncJobId));
            execute(job);
        } finally {
            lockService.release(PRICE_SYNC_LOCK_NAME, lockOwnerToken);
        }
    }

    private void execute(SyncJob job) {
        try {
            List<Item> activeItems = itemRepository.findByIsActiveTrue();
            int processed = 0;
            for (Item item : activeItems) {
                try {
                    priceSyncService.syncItem(item);
                    processed++;
                } catch (ItemPriceSyncException itemFailure) {
                    // Item-level failure — last-known price stays as-is, batch continues (§6).
                    log.warn("price sync failed for item {} ({}); last-known price retained",
                            item.getId(), item.getMarketHashName(), itemFailure);
                }
            }
            job.setItemsProcessed(processed);
            job.setStatus(JobStatus.SUCCESS);
            job.setFinishedAt(clock.instant());
        } catch (Exception fatal) {
            // Anything other than ItemPriceSyncException reaching here (e.g. a DB failure
            // surfaced by PriceSyncService.persist, or the active-items query itself failing)
            // is a job-level failure (§7).
            log.error("price sync job {} failed fatally", job.getId(), fatal);
            job.setStatus(JobStatus.FAILED);
            job.setErrorMessage(truncate(fatal.getMessage()));
            job.setFinishedAt(clock.instant());
        } finally {
            syncJobRepository.save(job);
        }
    }

    private String truncate(String message) {
        if (message == null) {
            return "Unknown error";
        }
        return message.length() > MAX_ERROR_MESSAGE_LENGTH ? message.substring(0, MAX_ERROR_MESSAGE_LENGTH) : message;
    }
}

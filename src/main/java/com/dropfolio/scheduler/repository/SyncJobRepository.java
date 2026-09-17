package com.dropfolio.scheduler.repository;

import com.dropfolio.scheduler.entity.JobStatus;
import com.dropfolio.scheduler.entity.JobType;
import com.dropfolio.scheduler.entity.SyncJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.Optional;

/**
 * {@link JpaSpecificationExecutor} for the optional {@code jobType}/{@code status} filters on
 * {@code GET /admin/sync-jobs} (API_CONTRACT.md §11) — same pattern as {@code ItemRepository}.
 */
public interface SyncJobRepository extends JpaRepository<SyncJob, Long>, JpaSpecificationExecutor<SyncJob> {

    /** M9 — GET /admin/dashboard "lastPriceSync". */
    Optional<SyncJob> findFirstByJobTypeOrderByStartedAtDesc(JobType jobType);

    /** M9 — GET /admin/dashboard "failedSyncCount24h". */
    long countByJobTypeAndStatusAndStartedAtAfter(JobType jobType, JobStatus status, Instant since);
}

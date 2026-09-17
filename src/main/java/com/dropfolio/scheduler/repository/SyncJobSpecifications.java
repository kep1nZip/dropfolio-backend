package com.dropfolio.scheduler.repository;

import com.dropfolio.scheduler.entity.JobStatus;
import com.dropfolio.scheduler.entity.JobType;
import com.dropfolio.scheduler.entity.SyncJob;
import org.springframework.data.jpa.domain.Specification;

/** Optional-filter specifications for {@code GET /admin/sync-jobs} — mirrors {@code ItemSpecifications}. */
public final class SyncJobSpecifications {

    private SyncJobSpecifications() {
    }

    public static Specification<SyncJob> ofJobType(JobType jobType) {
        return (root, query, cb) -> jobType == null ? cb.conjunction() : cb.equal(root.get("jobType"), jobType);
    }

    public static Specification<SyncJob> ofStatus(JobStatus status) {
        return (root, query, cb) -> status == null ? cb.conjunction() : cb.equal(root.get("status"), status);
    }
}

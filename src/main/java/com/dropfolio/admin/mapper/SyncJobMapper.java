package com.dropfolio.admin.mapper;

import com.dropfolio.admin.dto.SyncJobResponse;
import com.dropfolio.scheduler.entity.SyncJob;

public final class SyncJobMapper {

    private SyncJobMapper() {
    }

    public static SyncJobResponse toResponse(SyncJob job) {
        return new SyncJobResponse(
                job.getId(),
                job.getJobType().name(),
                job.getStatus().name(),
                job.getTriggeredBy().name(),
                job.getTriggeredByUserId(),
                job.getItemsProcessed(),
                job.getErrorMessage(),
                job.getStartedAt(),
                job.getFinishedAt());
    }
}

package com.dropfolio.common.audit;

import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * Explicit, targeted audit persistence — deliberately NOT wired through {@link AuditAspect} /
 * {@link Auditable} (see {@link AuditLog} javadoc for why). Callers record exactly the actions
 * they are authorized to record; nothing here fires automatically off an annotation, so adding
 * a new {@code @Auditable} method elsewhere in the codebase can never silently start writing
 * rows through this class.
 */
@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final Clock clock;

    public AuditLogService(AuditLogRepository auditLogRepository, Clock clock) {
        this.auditLogRepository = auditLogRepository;
        this.clock = clock;
    }

    /**
     * @param actorUserId nullable — NULL means a system/non-human actor (not used by M7, kept
     *                     nullable for schema/entity symmetry with {@link AuditLog#getActorUserId()}).
     * @param action       one of the locked {@code audit_logs.action} values (ERD.md §2.13 examples).
     * @param entityType   nullable, e.g. {@code "sync_jobs"}.
     * @param entityId     nullable — id of the affected row, no FK enforced (ERD.md §2.13).
     * @param metadata     nullable free-form JSON string.
     * @param ipAddress    nullable, IPv4/IPv6.
     */
    public AuditLog record(Long actorUserId, String action, String entityType, Long entityId,
                            String metadata, String ipAddress) {
        AuditLog log = AuditLog.builder()
                .actorUserId(actorUserId)
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .metadata(metadata)
                .ipAddress(ipAddress)
                .createdAt(clock.instant())
                .build();
        return auditLogRepository.save(log);
    }
}

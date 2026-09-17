package com.dropfolio.common.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Immutable admin-action log row — ERD.md §2.13. M7 scope: written to only by
 * {@code admin/service/AdminSyncService} for {@code ADMIN_TRIGGER_SYNC}. Existing
 * {@code @Auditable}-annotated methods in {@code item/} (ADMIN_CREATE_ITEM, ADMIN_UPDATE_ITEM)
 * are deliberately NOT wired to persist here — {@link AuditAspect} is untouched by M7
 * (M7 Implementation Authorization §3: "Do NOT retroactively activate unrelated existing
 * @Auditable annotations in item/"). Persistence is done via an explicit
 * {@link AuditLogService} call at the exact point in the trigger flow the PM locked
 * (M7 PM Decision §9), not via the generic AOP aspect.
 *
 * Append-only by convention — no {@code updated_at}, no update/delete methods exposed from
 * {@link AuditLogRepository}.
 */
@Entity
@Table(name = "audit_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** NULL means a system action (e.g. scheduler) — not applicable to M7 (only ADMIN triggers are audited). */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "action", nullable = false, length = 100)
    private String action;

    @Column(name = "entity_type", length = 50)
    private String entityType;

    /** Deliberately not a FK — ERD.md §2.13 (target table varies, target row may be gone). */
    @Column(name = "entity_id")
    private Long entityId;

    @Column(name = "metadata", columnDefinition = "NVARCHAR(MAX)")
    private String metadata;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

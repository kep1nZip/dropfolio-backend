package com.dropfolio.common.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * M9 adds {@link JpaSpecificationExecutor} for {@code GET /admin/audit-logs}' actor/action/
 * date-range filters (API_CONTRACT.md §11) — the read side M7 deliberately deferred. No
 * write/update/delete method is added anywhere — audit_logs stays append-only (ERD.md §2.13).
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {
}

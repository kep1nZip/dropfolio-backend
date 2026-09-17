package com.dropfolio.common.audit;

import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;

/** M9 — GET /admin/audit-logs actor/action/date-range filters (API_CONTRACT.md §11). */
public final class AuditLogSpecifications {

    private AuditLogSpecifications() {
    }

    public static Specification<AuditLog> ofActor(Long actorUserId) {
        return (root, query, cb) -> actorUserId == null ? cb.conjunction() : cb.equal(root.get("actorUserId"), actorUserId);
    }

    public static Specification<AuditLog> ofAction(String action) {
        return (root, query, cb) -> (action == null || action.isBlank()) ? cb.conjunction() : cb.equal(root.get("action"), action);
    }

    public static Specification<AuditLog> fromDate(Instant dateFrom) {
        return (root, query, cb) -> dateFrom == null ? cb.conjunction() : cb.greaterThanOrEqualTo(root.get("createdAt"), dateFrom);
    }

    public static Specification<AuditLog> toDate(Instant dateTo) {
        return (root, query, cb) -> dateTo == null ? cb.conjunction() : cb.lessThanOrEqualTo(root.get("createdAt"), dateTo);
    }
}

package com.dropfolio.drop.repository;

import com.dropfolio.drop.entity.Drop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface DropRepository extends JpaRepository<Drop, Long>, JpaSpecificationExecutor<Drop> {

    /**
     * Mandatory ownership-scoped lookup — TECHNICAL_SPEC.md §5.3: every {@code drops} query
     * MUST include {@code user_id} (never {@code findById()} + a manual check) and MUST filter
     * out soft-deleted rows (ERD.md §6.3), so a deleted drop behaves as "not found" for every
     * read/update/delete path, not just list.
     */
    Optional<Drop> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    /**
     * Milestone 6 addition (`portfolio/`) — all of a user's active holdings, unfiltered and
     * unpaginated, for in-memory aggregation (summary/breakdown/export). Purely additive: no
     * existing method signature or behavior changed.
     */
    List<Drop> findAllByUserIdAndDeletedAtIsNull(Long userId);

    /** M9 — GET /admin/users/{id} "totalDrops". */
    long countByUserIdAndDeletedAtIsNull(Long userId);
}

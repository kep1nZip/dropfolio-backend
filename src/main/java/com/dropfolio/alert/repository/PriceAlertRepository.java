package com.dropfolio.alert.repository;

import com.dropfolio.alert.entity.AlertStatus;
import com.dropfolio.alert.entity.PriceAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface PriceAlertRepository extends JpaRepository<PriceAlert, Long>, JpaSpecificationExecutor<PriceAlert> {

    /** Mandatory ownership-scoped lookup — CLAUDE_CONTEXT.md §6: never {@code findById()} + a manual check. */
    Optional<PriceAlert> findByIdAndUserId(Long id, Long userId);

    /**
     * ERD.md §2.8 — "kritis untuk AlertEvaluationJob": per-item lookup of ACTIVE alerts,
     * exactly what {@code AlertEvaluationService.evaluate(...)} runs for every priced item.
     */
    List<PriceAlert> findByItemIdAndStatus(Long itemId, AlertStatus status);

    /** M9 — GET /admin/users/{id} "totalAlerts". */
    long countByUserId(Long userId);
}

package com.dropfolio.alert.repository;

import com.dropfolio.alert.entity.AlertStatus;
import com.dropfolio.alert.entity.PriceAlert;
import org.springframework.data.jpa.domain.Specification;

public final class PriceAlertSpecifications {

    private PriceAlertSpecifications() {
    }

    public static Specification<PriceAlert> ownedBy(Long userId) {
        return (root, query, cb) -> cb.equal(root.get("userId"), userId);
    }

    public static Specification<PriceAlert> ofStatus(AlertStatus status) {
        return (root, query, cb) -> status == null ? cb.conjunction() : cb.equal(root.get("status"), status);
    }
}

package com.dropfolio.drop.repository;

import com.dropfolio.drop.entity.Drop;
import com.dropfolio.drop.entity.DropSource;
import com.dropfolio.item.entity.Item;
import com.dropfolio.item.entity.ItemType;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Dynamic filters for {@code GET /drops} — same null-safe "always return a Specification,
 * falling back to {@code cb.conjunction()} when the filter value is absent" pattern already
 * established by {@code item/repository/ItemSpecifications} (Milestone 3), so every filter can
 * be unconditionally chained with {@code .and(...)}.
 *
 * {@code search}/{@code ofType} filter on the referenced {@code items} row via a correlated
 * subquery rather than a mapped JPA association on {@link Drop} — {@code Drop} intentionally
 * has no {@code @ManyToOne Item} field (same reasoning as {@code pricing/entity/ItemPrice}: no
 * association is needed anywhere else, so one isn't added just to support this one query).
 */
public final class DropSpecifications {

    private DropSpecifications() {
    }

    public static Specification<Drop> ownedBy(Long userId) {
        return (root, query, cb) -> cb.equal(root.get("userId"), userId);
    }

    /** ERD.md §6.3 — every list/read query must filter out soft-deleted rows. */
    public static Specification<Drop> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    public static Specification<Drop> search(String search) {
        return (root, query, cb) -> {
            if (search == null || search.isBlank()) {
                return cb.conjunction();
            }
            Subquery<Long> sub = query.subquery(Long.class);
            Root<Item> itemRoot = sub.from(Item.class);
            sub.select(itemRoot.get("id"))
                    .where(cb.like(cb.lower(itemRoot.get("name")), "%" + search.toLowerCase() + "%"));
            return root.get("itemId").in(sub);
        };
    }

    public static Specification<Drop> ofType(ItemType type) {
        return (root, query, cb) -> {
            if (type == null) {
                return cb.conjunction();
            }
            Subquery<Long> sub = query.subquery(Long.class);
            Root<Item> itemRoot = sub.from(Item.class);
            sub.select(itemRoot.get("id")).where(cb.equal(itemRoot.get("type"), type));
            return root.get("itemId").in(sub);
        };
    }

    public static Specification<Drop> ofSource(DropSource source) {
        return (root, query, cb) -> source == null ? cb.conjunction() : cb.equal(root.get("source"), source);
    }

    public static Specification<Drop> acquiredFrom(LocalDate dateFrom) {
        return (root, query, cb) -> dateFrom == null ? cb.conjunction()
                : cb.greaterThanOrEqualTo(root.get("acquisitionDate"), dateFrom);
    }

    public static Specification<Drop> acquiredTo(LocalDate dateTo) {
        return (root, query, cb) -> dateTo == null ? cb.conjunction()
                : cb.lessThanOrEqualTo(root.get("acquisitionDate"), dateTo);
    }

    /**
     * PM Decision (Milestone 5): {@code minValue}/{@code maxValue} filter {@code
     * acquisitionValueUsd} — the actual stored column on {@code drops} — not {@code
     * currentValueUsd} (a live pricing/portfolio concern, deliberately not joined here).
     */
    public static Specification<Drop> minAcquisitionValue(BigDecimal minValue) {
        return (root, query, cb) -> minValue == null ? cb.conjunction()
                : cb.greaterThanOrEqualTo(root.get("acquisitionValueUsd"), minValue);
    }

    public static Specification<Drop> maxAcquisitionValue(BigDecimal maxValue) {
        return (root, query, cb) -> maxValue == null ? cb.conjunction()
                : cb.lessThanOrEqualTo(root.get("acquisitionValueUsd"), maxValue);
    }
}

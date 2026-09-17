package com.dropfolio.item.repository;

import com.dropfolio.item.entity.Item;
import com.dropfolio.item.entity.ItemType;
import org.springframework.data.jpa.domain.Specification;

/**
 * Explicit dynamic query construction for {@code GET /items} filters (API_CONTRACT.md §4:
 * {@code search} partial-matches {@code name}, {@code type} is an exact enum match, both
 * optional and combinable) — TECHNICAL_SPEC.md §2 "repository query eksplisit untuk kasus
 * kompleks", kept out of the entity/repository interface itself for readability.
 */
public final class ItemSpecifications {

    private ItemSpecifications() {
    }

    public static Specification<Item> search(String search) {
        return (root, query, cb) -> search == null || search.isBlank()
                ? cb.conjunction()
                : cb.like(cb.lower(root.get("name")), "%" + search.toLowerCase() + "%");
    }

    public static Specification<Item> ofType(ItemType type) {
        return (root, query, cb) -> type == null
                ? cb.conjunction()
                : cb.equal(root.get("type"), type);
    }
}

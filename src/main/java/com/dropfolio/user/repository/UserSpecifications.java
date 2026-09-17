package com.dropfolio.user.repository;

import com.dropfolio.user.entity.UserStatus;
import org.springframework.data.jpa.domain.Specification;

/** M9 — GET /admin/users search/status filters (API_CONTRACT.md §11). */
public final class UserSpecifications {

    private UserSpecifications() {
    }

    /** Matches {@code email} OR {@code displayName}, case-insensitive substring — same convention as {@code ItemSpecifications.search}. */
    public static Specification<com.dropfolio.user.entity.User> search(String search) {
        return (root, query, cb) -> {
            if (search == null || search.isBlank()) {
                return cb.conjunction();
            }
            String pattern = "%" + search.toLowerCase() + "%";
            return cb.or(
                    cb.like(cb.lower(root.get("email")), pattern),
                    cb.like(cb.lower(root.get("displayName")), pattern));
        };
    }

    public static Specification<com.dropfolio.user.entity.User> ofStatus(UserStatus status) {
        return (root, query, cb) -> status == null ? cb.conjunction() : cb.equal(root.get("status"), status);
    }
}

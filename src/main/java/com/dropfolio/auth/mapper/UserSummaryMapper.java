package com.dropfolio.auth.mapper;

import com.dropfolio.auth.dto.UserSummary;
import com.dropfolio.user.entity.RoleName;
import com.dropfolio.user.entity.User;

import java.util.List;

/**
 * Explicit entity ↔ DTO mapping — TECHNICAL_SPEC.md §2. No reflection-based auto-mapper, so a
 * field like {@code passwordHash} can never leak into a response by accident.
 *
 * Lives in {@code auth/} (not {@code user/}) because {@link UserSummary} is an auth/ DTO
 * (TECHNICAL_SPEC.md §3.1) — keeps the dependency direction auth → user.entity, never the
 * reverse.
 */
public final class UserSummaryMapper {

    private UserSummaryMapper() {
    }

    public static UserSummary toSummary(User user, List<RoleName> roles) {
        return new UserSummary(
                user.getId(),
                user.getDisplayName(),
                roles.stream().map(Enum::name).toList()
        );
    }
}

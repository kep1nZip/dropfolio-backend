package com.dropfolio.admin.mapper;

import com.dropfolio.admin.dto.AdminUserDetailResponse;
import com.dropfolio.admin.dto.AdminUserResponse;
import com.dropfolio.user.entity.User;

/** Explicit entity <-> DTO mapping — TECHNICAL_SPEC.md §2. {@code passwordHash}/{@code tokenVersion} never included. */
public final class AdminUserMapper {

    private AdminUserMapper() {
    }

    public static AdminUserResponse toResponse(User user) {
        return new AdminUserResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getStatus().name(),
                user.getCreatedAt());
    }

    public static AdminUserDetailResponse toDetailResponse(User user, long totalDrops, long totalAlerts) {
        return new AdminUserDetailResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getStatus().name(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                totalDrops,
                totalAlerts,
                // M9 Implementation Authorization §4 (LOCKED): always null, never real Steam data.
                null);
    }
}

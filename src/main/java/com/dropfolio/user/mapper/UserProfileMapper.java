package com.dropfolio.user.mapper;

import com.dropfolio.user.dto.UserProfileResponse;
import com.dropfolio.user.entity.User;

import java.util.List;

/** Explicit entity <-> DTO mapping — TECHNICAL_SPEC.md §2. {@code passwordHash}/{@code tokenVersion} never included. */
public final class UserProfileMapper {

    private UserProfileMapper() {
    }

    public static UserProfileResponse toResponse(User user, List<String> roles) {
        return new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getStatus().name(),
                roles,
                // M10 Implementation Authorization §3 (LOCKED): always null, never real Steam data.
                null,
                user.getCreatedAt());
    }
}

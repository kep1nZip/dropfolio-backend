package com.dropfolio.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * API_CONTRACT.md §2 PATCH /users/me — partial, {@code null} = leave unchanged (same
 * established convention as every other Update*Request in this project). Only
 * {@code displayName} and {@code email} are editable via this endpoint — no other {@code User}
 * field is exposed here, so nothing else can be modified through this DTO even by accident.
 */
public record UpdateProfileRequest(
        @Size(min = 1, max = 100) String displayName,
        @Email String email
) {
}

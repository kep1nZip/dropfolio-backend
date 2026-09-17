package com.dropfolio.admin.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * API_CONTRACT.md §11 PATCH /admin/users/{id}/status. Only {@code "ACTIVE"} or
 * {@code "DEACTIVATED"} are valid (validated in {@code AdminUserService}, not here, same
 * pattern as {@code UpdateAlertRequest.status} — a client can never target {@code "DELETED"}
 * through this endpoint; that stays a self-service concern per M9 §5).
 */
public record UpdateUserStatusRequest(@NotBlank String status) {
}

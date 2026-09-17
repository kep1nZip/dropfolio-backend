package com.dropfolio.user.dto;

import jakarta.validation.constraints.NotBlank;

/** API_CONTRACT.md §2 DELETE /users/me — explicit confirmation to prevent accidental deletion. */
public record DeleteAccountRequest(@NotBlank String confirmation) {
}

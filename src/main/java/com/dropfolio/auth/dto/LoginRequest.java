package com.dropfolio.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** TECHNICAL_SPEC.md §3.1. */
public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password
) {
}

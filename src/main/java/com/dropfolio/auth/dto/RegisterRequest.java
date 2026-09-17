package com.dropfolio.auth.dto;

import com.dropfolio.auth.validation.ValidPassword;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** TECHNICAL_SPEC.md §3.1. */
public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8) @ValidPassword String password,
        @NotBlank @Size(min = 1, max = 100) String displayName
) {
}

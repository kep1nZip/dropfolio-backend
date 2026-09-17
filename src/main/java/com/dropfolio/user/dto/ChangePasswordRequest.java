package com.dropfolio.user.dto;

import com.dropfolio.auth.validation.ValidPassword;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * API_CONTRACT.md §2 POST /users/me/password. {@code currentPassword} is deliberately NOT
 * {@code @NotBlank} — it's optional only for a user whose {@code password_hash IS NULL}
 * (contract-mandated defensive branch for a Steam-only account that never set a password; no
 * registration path in this product currently creates such a user, since there is no Steam
 * login here, but the check is kept for contract fidelity — see {@code UserSelfService}).
 * {@code newPassword} reuses the same {@code @ValidPassword} policy as registration (M2).
 */
public record ChangePasswordRequest(
        String currentPassword,
        @NotBlank @Size(min = 8) @ValidPassword String newPassword
) {
}

package com.dropfolio.item.dto;

import jakarta.validation.constraints.Size;

/**
 * TECHNICAL_SPEC.md §3.4 — partial, only {@code name, iconUrl, isActive} allowed
 * (API_CONTRACT.md §4: {@code type} and {@code marketHashName} are NOT patchable).
 * All fields nullable-optional; {@code null} means "leave unchanged" (service-layer semantics).
 */
public record UpdateItemRequest(
        @Size(max = 200) String name,
        @Size(max = 500) String iconUrl,
        Boolean isActive
) {
}

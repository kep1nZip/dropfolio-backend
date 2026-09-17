package com.dropfolio.drop.dto;

/**
 * Nested item reference inside {@link DropResponse} — API_CONTRACT.md §5.
 * Intentionally its own type, not shared with any other module's item-ref DTO
 * (TECHNICAL_SPEC.md §3.12: each domain's nested item reference is separate).
 */
public record DropItemRef(
        Long id,
        String name,
        String type,
        String iconUrl
) {
}

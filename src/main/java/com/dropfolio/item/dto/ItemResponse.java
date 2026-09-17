package com.dropfolio.item.dto;

/** TECHNICAL_SPEC.md §3.4. Shape identical for list item, single item, and 201-created item. */
public record ItemResponse(
        Long id,
        String name,
        String type,
        String marketHashName,
        String iconUrl,
        boolean isActive
) {
}

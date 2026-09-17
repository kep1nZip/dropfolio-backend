package com.dropfolio.item.mapper;

import com.dropfolio.item.dto.ItemResponse;
import com.dropfolio.item.entity.Item;

/** Explicit entity ↔ DTO mapping — TECHNICAL_SPEC.md §2. No reflection-based auto-mapper. */
public final class ItemMapper {

    private ItemMapper() {
    }

    public static ItemResponse toResponse(Item item) {
        return new ItemResponse(
                item.getId(),
                item.getName(),
                item.getType().name(),
                item.getMarketHashName(),
                item.getIconUrl(),
                item.getIsActive()
        );
    }
}

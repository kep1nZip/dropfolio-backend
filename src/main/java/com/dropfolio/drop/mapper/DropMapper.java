package com.dropfolio.drop.mapper;

import com.dropfolio.drop.dto.DropItemRef;
import com.dropfolio.drop.dto.DropResponse;
import com.dropfolio.drop.entity.Drop;
import com.dropfolio.item.entity.Item;
import com.dropfolio.pricing.dto.PriceResponse;

/** Explicit entity ↔ DTO mapping — TECHNICAL_SPEC.md §2. No reflection-based auto-mapper. */
public final class DropMapper {

    private DropMapper() {
    }

    public static DropResponse toResponse(Drop drop, Item item, PriceResponse price) {
        DropItemRef itemRef = new DropItemRef(
                item.getId(),
                item.getName(),
                item.getType().name(),
                item.getIconUrl()
        );
        return new DropResponse(
                drop.getId(),
                itemRef,
                drop.getSource().name(),
                drop.getQuantity(),
                drop.getAcquisitionDate(),
                drop.getAcquisitionValueUsd(),
                price.priceAvailable() ? price.priceUsd() : null,
                price.priceAvailable()
        );
    }
}

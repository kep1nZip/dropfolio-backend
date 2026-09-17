package com.dropfolio.alert.mapper;

import com.dropfolio.alert.dto.AlertItemRef;
import com.dropfolio.alert.dto.AlertResponse;
import com.dropfolio.alert.entity.PriceAlert;
import com.dropfolio.item.entity.Item;

/** Explicit entity <-> DTO mapping — TECHNICAL_SPEC.md §2. No reflection-based auto-mapper. */
public final class AlertMapper {

    private AlertMapper() {
    }

    public static AlertResponse toResponse(PriceAlert alert, Item item) {
        AlertItemRef itemRef = item == null ? null : new AlertItemRef(item.getId(), item.getName());
        return new AlertResponse(
                alert.getId(),
                itemRef,
                alert.getTargetPriceUsd(),
                alert.getNotifyEmail(),
                alert.getNotifyInApp(),
                alert.getStatus().name(),
                alert.getCreatedAt(),
                alert.getTriggeredAt());
    }
}

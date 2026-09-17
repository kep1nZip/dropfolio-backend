package com.dropfolio.item.dto;

import com.dropfolio.item.entity.ItemType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * TECHNICAL_SPEC.md §3.4. {@code marketHashName} uniqueness is checked in the service
 * (DB-backed check), not here — a Bean Validation annotation can't safely express "unique in
 * the database" without a live query, which doesn't belong in an annotation on a DTO.
 */
public record CreateItemRequest(
        @NotBlank @Size(max = 200) String name,
        @NotNull ItemType type,
        @NotBlank @Size(max = 300) String marketHashName,
        @Size(max = 500) String iconUrl
) {
}

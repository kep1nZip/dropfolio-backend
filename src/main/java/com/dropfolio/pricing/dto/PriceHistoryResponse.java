package com.dropfolio.pricing.dto;

import java.util.List;

/**
 * Response of {@code GET /prices/{itemId}/history}. {@code range} echoes the canonical
 * (lower-case) range that was applied. {@code provider} is the provider of the most recent
 * returned point, or {@code null} when there are no points. {@code points} is ordered by
 * {@code fetchedAt} ascending and may be empty.
 */
public record PriceHistoryResponse(
        Long itemId,
        String provider,
        String range,
        List<PriceHistoryPointResponse> points
) {
}

package com.dropfolio.pricing.controller;

import com.dropfolio.common.envelope.ApiResponse;
import com.dropfolio.pricing.dto.PriceHistoryResponse;
import com.dropfolio.pricing.service.PriceHistoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/prices/{itemId}/history} — public market data, same authorization model
 * as {@code GET /prices/{itemId}}. Separate from {@link PriceController} so the frozen M4
 * controller and its test stay untouched. Binds params, calls one service method, wraps the
 * response — no business logic here.
 */
@RestController
@RequestMapping("/api/v1/prices")
public class PriceHistoryController {

    private final PriceHistoryService priceHistoryService;

    public PriceHistoryController(PriceHistoryService priceHistoryService) {
        this.priceHistoryService = priceHistoryService;
    }

    @GetMapping("/{itemId}/history")
    public ResponseEntity<ApiResponse<PriceHistoryResponse>> getHistory(
            @PathVariable Long itemId,
            @RequestParam(defaultValue = "30d") String range,
            @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(ApiResponse.of(priceHistoryService.getHistory(itemId, range, limit)));
    }
}

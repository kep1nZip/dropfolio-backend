package com.dropfolio.pricing.controller;

import com.dropfolio.common.envelope.ApiResponse;
import com.dropfolio.pricing.dto.PriceResponse;
import com.dropfolio.pricing.service.PricingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API_CONTRACT.md §8 — Domain: Prices. Public, single endpoint for Milestone 4
 * ({@code GET /prices/{itemId}}). No other pricing endpoint is added here (history/admin sync
 * belong to later milestones — see completion report scope-freeze confirmation).
 *
 * Controller only binds/calls/wraps — no business logic (TECHNICAL_SPEC.md §2), same convention
 * as {@code ItemController}.
 */
@RestController
@RequestMapping("/api/v1/prices")
public class PriceController {

    private final PricingService pricingService;

    public PriceController(PricingService pricingService) {
        this.pricingService = pricingService;
    }

    @GetMapping("/{itemId}")
    public ResponseEntity<ApiResponse<PriceResponse>> getPrice(@PathVariable Long itemId) {
        return ResponseEntity.ok(ApiResponse.of(pricingService.getPrice(itemId)));
    }
}

package com.dropfolio.portfolio.controller;

import com.dropfolio.common.envelope.ApiResponse;
import com.dropfolio.item.entity.ItemType;
import com.dropfolio.portfolio.dto.PortfolioBreakdownItemResponse;
import com.dropfolio.portfolio.dto.PortfolioSummaryResponse;
import com.dropfolio.portfolio.service.PortfolioService;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * API_CONTRACT.md §7 — Domain: Portfolio. Every endpoint is {@code Auth: Required}, scoped
 * implicitly to the current user (no per-resource ID, so no ownership-mismatch 404 case exists
 * here — see MILESTONE_6_ASSESSMENT_REPORT.md §5). No {@code SecurityConfig} change needed,
 * same as {@code drop/} — default {@code anyRequest().authenticated()} already covers it.
 *
 * Controller only binds, calls one service method, wraps the response — no business logic here
 * (TECHNICAL_SPEC.md §2), same convention as every other controller in this codebase.
 */
@RestController
@RequestMapping("/api/v1/portfolio")
public class PortfolioController {

    private final PortfolioService portfolioService;

    public PortfolioController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<PortfolioSummaryResponse>> summary(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.of(portfolioService.getSummary(userId)));
    }

    @GetMapping("/breakdown")
    public ResponseEntity<ApiResponse<List<PortfolioBreakdownItemResponse>>> breakdown(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) ItemType type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        Page<PortfolioBreakdownItemResponse> result =
                portfolioService.getBreakdown(userId, search, type, page, size, sort);
        return ResponseEntity.ok(ApiResponse.of(result.getContent(), paginationMeta(result)));
    }

    @GetMapping("/export")
    public ResponseEntity<String> export(@AuthenticationPrincipal Long userId) {
        Optional<String> csv = portfolioService.exportCsv(userId);
        if (csv.isEmpty()) {
            // API_CONTRACT.md §7: 204 when the user has no data to export.
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"dropfolio-export.csv\"")
                .body(csv.get());
    }

    private Map<String, Object> paginationMeta(Page<?> page) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("page", page.getNumber() + 1);
        meta.put("size", page.getSize());
        meta.put("totalElements", page.getTotalElements());
        meta.put("totalPages", page.getTotalPages());
        return meta;
    }
}

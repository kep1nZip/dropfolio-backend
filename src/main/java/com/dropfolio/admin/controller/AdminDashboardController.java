package com.dropfolio.admin.controller;

import com.dropfolio.admin.dto.DashboardResponse;
import com.dropfolio.admin.service.AdminDashboardService;
import com.dropfolio.common.envelope.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** M9 — GET /admin/dashboard (API_CONTRACT.md §11). ADMIN-only via {@code SecurityConfig} matcher. */
@RestController
@RequestMapping("/api/v1/admin/dashboard")
public class AdminDashboardController {

    private final AdminDashboardService adminDashboardService;

    public AdminDashboardController(AdminDashboardService adminDashboardService) {
        this.adminDashboardService = adminDashboardService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<DashboardResponse>> getDashboard() {
        return ResponseEntity.ok(ApiResponse.of(adminDashboardService.getDashboard()));
    }
}

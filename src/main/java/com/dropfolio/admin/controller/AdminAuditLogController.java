package com.dropfolio.admin.controller;

import com.dropfolio.admin.dto.AuditLogResponse;
import com.dropfolio.admin.service.AdminAuditLogService;
import com.dropfolio.common.envelope.ApiResponse;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M9 — GET /admin/audit-logs (API_CONTRACT.md §11). Read-only, append-only table — no
 * POST/PATCH/DELETE mapping exists here or anywhere else for {@code audit_logs}.
 */
@RestController
@RequestMapping("/api/v1/admin/audit-logs")
public class AdminAuditLogController {

    private final AdminAuditLogService adminAuditLogService;

    public AdminAuditLogController(AdminAuditLogService adminAuditLogService) {
        this.adminAuditLogService = adminAuditLogService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<AuditLogResponse>>> list(
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Instant dateFrom,
            @RequestParam(required = false) Instant dateTo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<AuditLogResponse> result = adminAuditLogService.list(actorUserId, action, dateFrom, dateTo, page, size);
        return ResponseEntity.ok(ApiResponse.of(result.getContent(), paginationMeta(result)));
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

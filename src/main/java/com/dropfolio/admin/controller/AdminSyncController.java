package com.dropfolio.admin.controller;

import com.dropfolio.admin.dto.SyncJobResponse;
import com.dropfolio.admin.dto.TriggerSyncResponse;
import com.dropfolio.admin.service.AdminSyncService;
import com.dropfolio.common.envelope.ApiResponse;
import com.dropfolio.scheduler.entity.JobStatus;
import com.dropfolio.scheduler.entity.JobType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M7 Implementation Authorization §4 — EXACTLY these three endpoints. No dashboard, no user
 * management, no audit-log viewer. ADMIN authorization enforced at {@code SecurityConfig}
 * path-matcher level, same single-layer convention as every other controller in this codebase
 * (see {@code ItemController} javadoc).
 *
 * Controller only binds/calls/wraps — no business logic (the lock/audit/rate-limit ordering
 * locked by PM Decision M7 §9 lives entirely in {@link AdminSyncService}).
 */
@RestController
@RequestMapping("/api/v1/admin/sync-jobs")
public class AdminSyncController {

    private final AdminSyncService adminSyncService;

    public AdminSyncController(AdminSyncService adminSyncService) {
        this.adminSyncService = adminSyncService;
    }

    @PostMapping("/price-sync")
    public ResponseEntity<ApiResponse<TriggerSyncResponse>> triggerPriceSync(
            @AuthenticationPrincipal Long adminUserId, HttpServletRequest request) {
        TriggerSyncResponse response = adminSyncService.triggerPriceSync(adminUserId, clientIp(request));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.of(response));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<SyncJobResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(adminSyncService.getById(id)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<SyncJobResponse>>> list(
            @RequestParam(required = false) JobType jobType,
            @RequestParam(required = false) JobStatus status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        Page<SyncJobResponse> result = adminSyncService.list(jobType, status, page, size, sort);
        return ResponseEntity.ok(ApiResponse.of(result.getContent(), paginationMeta(result)));
    }

    /** Same X-Forwarded-For-first convention as the rest of the codebase's IP-aware paths. */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
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

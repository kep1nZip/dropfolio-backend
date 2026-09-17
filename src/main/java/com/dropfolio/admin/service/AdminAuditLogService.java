package com.dropfolio.admin.service;

import com.dropfolio.admin.dto.AuditLogResponse;
import com.dropfolio.admin.mapper.AuditLogMapper;
import com.dropfolio.common.audit.AuditLog;
import com.dropfolio.common.audit.AuditLogRepository;
import com.dropfolio.common.audit.AuditLogSpecifications;
import com.dropfolio.common.exception.ValidationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * M9 — GET /admin/audit-logs (API_CONTRACT.md §11). Read-only — the append-only writer stays
 * {@link com.dropfolio.common.audit.AuditLogService}; no write/update/delete method exists
 * here or anywhere else for this table.
 */
@Service
public class AdminAuditLogService {

    private static final int MAX_PAGE_SIZE = 100;

    private final AuditLogRepository auditLogRepository;

    public AdminAuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(readOnly = true)
    public Page<AuditLogResponse> list(Long actorUserId, String action, Instant dateFrom, Instant dateTo,
                                        int page, int size) {
        if (page < 1) {
            throw new ValidationException("page must be >= 1");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ValidationException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        Specification<AuditLog> spec = Specification.where(AuditLogSpecifications.ofActor(actorUserId))
                .and(AuditLogSpecifications.ofAction(action))
                .and(AuditLogSpecifications.fromDate(dateFrom))
                .and(AuditLogSpecifications.toDate(dateTo));
        PageRequest pageRequest = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return auditLogRepository.findAll(spec, pageRequest).map(AuditLogMapper::toResponse);
    }
}

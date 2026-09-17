package com.dropfolio.admin.service;

import com.dropfolio.admin.dto.AuditLogResponse;
import com.dropfolio.common.audit.AuditLog;
import com.dropfolio.common.audit.AuditLogRepository;
import com.dropfolio.common.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Unit tests for {@link AdminAuditLogService} — M9 Implementation Authorization §7/§14. Read-only. */
@ExtendWith(MockitoExtension.class)
class AdminAuditLogServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-13T12:00:00Z");

    @Mock private AuditLogRepository auditLogRepository;

    private AdminAuditLogService adminAuditLogService;

    @BeforeEach
    void setUp() {
        adminAuditLogService = new AdminAuditLogService(auditLogRepository);
    }

    private AuditLog auditLog() {
        return AuditLog.builder().id(1L).actorUserId(7L).action("ADMIN_UPDATE_USER_STATUS")
                .entityType("users").entityId(1L).metadata("{}").createdAt(FIXED_INSTANT).build();
    }

    @Test
    void list_invalidPage_throwsValidation() {
        assertThatThrownBy(() -> adminAuditLogService.list(null, null, null, null, 0, 20))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void list_invalidSize_throwsValidation() {
        assertThatThrownBy(() -> adminAuditLogService.list(null, null, null, null, 1, 0))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void list_readsAdminUpdateUserStatusAction() {
        when(auditLogRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(auditLog())));

        Page<AuditLogResponse> result = adminAuditLogService.list(null, "ADMIN_UPDATE_USER_STATUS", null, null, 1, 20);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).action()).isEqualTo("ADMIN_UPDATE_USER_STATUS");
        assertThat(result.getContent().get(0).actorUserId()).isEqualTo(7L);
    }

    @Test
    void list_actorFilter_delegatesToRepository() {
        when(auditLogRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        adminAuditLogService.list(7L, null, null, null, 1, 20);
    }

    @Test
    void list_dateRangeFilter_accepted() {
        when(auditLogRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(auditLog())));

        Page<AuditLogResponse> result = adminAuditLogService.list(
                null, null, FIXED_INSTANT.minusSeconds(3600), FIXED_INSTANT, 1, 20);

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void list_emptyResult_returnsEmptyPage() {
        when(auditLogRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        assertThat(adminAuditLogService.list(null, null, null, null, 1, 20).getContent()).isEmpty();
    }
}

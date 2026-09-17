package com.dropfolio.admin.service;

import com.dropfolio.admin.dto.AdminUserDetailResponse;
import com.dropfolio.admin.dto.AdminUserResponse;
import com.dropfolio.admin.dto.UpdateUserStatusRequest;
import com.dropfolio.admin.mapper.AdminUserMapper;
import com.dropfolio.alert.repository.PriceAlertRepository;
import com.dropfolio.auth.service.RefreshTokenService;
import com.dropfolio.common.audit.AuditLogService;
import com.dropfolio.common.exception.ResourceNotFoundException;
import com.dropfolio.common.exception.ValidationException;
import com.dropfolio.drop.repository.DropRepository;
import com.dropfolio.user.entity.User;
import com.dropfolio.user.entity.UserStatus;
import com.dropfolio.user.repository.UserRepository;
import com.dropfolio.user.repository.UserSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * M9 — GET /admin/users, GET /admin/users/{id}, PATCH /admin/users/{id}/status
 * (API_CONTRACT.md §11). Admin-only resource — no ownership scoping (M9 Implementation
 * Authorization §3: "Ownership restriction tidak berlaku karena ini admin-only resource").
 */
@Service
public class AdminUserService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String ENTITY_TYPE = "users";
    private static final String ACTION_UPDATE_USER_STATUS = "ADMIN_UPDATE_USER_STATUS";

    private final UserRepository userRepository;
    private final DropRepository dropRepository;
    private final PriceAlertRepository priceAlertRepository;
    private final RefreshTokenService refreshTokenService;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public AdminUserService(UserRepository userRepository,
                             DropRepository dropRepository,
                             PriceAlertRepository priceAlertRepository,
                             RefreshTokenService refreshTokenService,
                             AuditLogService auditLogService,
                             Clock clock) {
        this.userRepository = userRepository;
        this.dropRepository = dropRepository;
        this.priceAlertRepository = priceAlertRepository;
        this.refreshTokenService = refreshTokenService;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<AdminUserResponse> list(String search, UserStatus status, int page, int size) {
        if (page < 1) {
            throw new ValidationException("page must be >= 1");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ValidationException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        Specification<User> spec = Specification.where(UserSpecifications.search(search))
                .and(UserSpecifications.ofStatus(status));
        PageRequest pageRequest = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.ASC, "id"));
        return userRepository.findAll(spec, pageRequest).map(AdminUserMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public AdminUserDetailResponse getById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        long totalDrops = dropRepository.countByUserIdAndDeletedAtIsNull(id);
        long totalAlerts = priceAlertRepository.countByUserId(id);
        return AdminUserMapper.toDetailResponse(user, totalDrops, totalAlerts);
    }

    /**
     * M9 §5: allowed transitions are {@code ACTIVE <-> DEACTIVATED} only. {@code "DELETED"} as
     * a target is rejected here (422) — account deletion stays a self-service concern.
     * Defensive extension (disclosed, not in the contract text verbatim): a user whose CURRENT
     * status is already {@code DELETED} is also rejected (422) rather than silently
     * "reactivated" — restoring a self-deleted account is a materially different, undecided
     * feature, not the same as a plain ACTIVE/DEACTIVATED toggle.
     */
    @Transactional
    public AdminUserDetailResponse updateStatus(Long id, UpdateUserStatusRequest request) {
        UserStatus targetStatus = parseTargetStatus(request.status());

        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getStatus() == UserStatus.DELETED) {
            throw new ValidationException("Cannot administratively change the status of a deleted account");
        }

        user.setStatus(targetStatus);
        user.setUpdatedAt(clock.instant());
        if (targetStatus == UserStatus.DEACTIVATED) {
            // M9 §5: bump token_version (instant access-token revoke, SYSTEM_ARCHITECTURE.md
            // §3.3 mechanism, unchanged) AND revoke outstanding refresh tokens (same
            // "existing mechanism" RefreshTokenService already uses when AuthService.refresh()
            // independently detects a deactivated account — applied proactively here instead
            // of waiting for the user's next refresh attempt to discover it).
            user.setTokenVersion(user.getTokenVersion() + 1);
            refreshTokenService.revokeAll(id);
        }
        User saved = userRepository.save(user);

        auditLogService.record(null, ACTION_UPDATE_USER_STATUS, ENTITY_TYPE, id,
                "{\"newStatus\":\"" + targetStatus.name() + "\"}", null);

        long totalDrops = dropRepository.countByUserIdAndDeletedAtIsNull(id);
        long totalAlerts = priceAlertRepository.countByUserId(id);
        return AdminUserMapper.toDetailResponse(saved, totalDrops, totalAlerts);
    }

    private UserStatus parseTargetStatus(String status) {
        if (!"ACTIVE".equals(status) && !"DEACTIVATED".equals(status)) {
            throw new ValidationException("status must be ACTIVE or DEACTIVATED");
        }
        return UserStatus.valueOf(status);
    }
}

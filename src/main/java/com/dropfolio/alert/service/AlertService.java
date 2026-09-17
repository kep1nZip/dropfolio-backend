package com.dropfolio.alert.service;

import com.dropfolio.alert.dto.AlertResponse;
import com.dropfolio.alert.dto.CreateAlertRequest;
import com.dropfolio.alert.dto.UpdateAlertRequest;
import com.dropfolio.alert.entity.AlertStatus;
import com.dropfolio.alert.entity.PriceAlert;
import com.dropfolio.alert.mapper.AlertMapper;
import com.dropfolio.alert.repository.PriceAlertRepository;
import com.dropfolio.alert.repository.PriceAlertSpecifications;
import com.dropfolio.common.exception.OwnershipMismatchException;
import com.dropfolio.common.exception.ResourceNotFoundException;
import com.dropfolio.common.exception.ValidationException;
import com.dropfolio.item.entity.Item;
import com.dropfolio.item.repository.ItemRepository;
import com.dropfolio.notification.repository.NotificationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** TECHNICAL_SPEC.md §2: all business logic lives here, controller only binds/calls/wraps. */
@Service
public class AlertService {

    /** API_CONTRACT.md §9 GET /alerts — allowed sort fields. */
    private static final Set<String> SORT_WHITELIST = Set.of("createdAt", "targetPriceUsd");
    private static final int MAX_PAGE_SIZE = 100;
    private static final String DEFAULT_SORT_FIELD = "createdAt";

    private final PriceAlertRepository priceAlertRepository;
    private final ItemRepository itemRepository;
    private final NotificationRepository notificationRepository;
    private final Clock clock;

    public AlertService(PriceAlertRepository priceAlertRepository, ItemRepository itemRepository,
                         NotificationRepository notificationRepository, Clock clock) {
        this.priceAlertRepository = priceAlertRepository;
        this.itemRepository = itemRepository;
        this.notificationRepository = notificationRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<AlertResponse> list(Long userId, AlertStatus status, int page, int size, String sort) {
        if (page < 1) {
            throw new ValidationException("page must be >= 1");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new ValidationException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        Sort springSort = parseSort(sort);
        Specification<PriceAlert> spec = Specification
                .where(PriceAlertSpecifications.ownedBy(userId))
                .and(PriceAlertSpecifications.ofStatus(status));
        Page<PriceAlert> alerts = priceAlertRepository.findAll(spec, PageRequest.of(page - 1, size, springSort));
        List<AlertResponse> content = toResponses(alerts.getContent());
        return new PageImpl<>(content, alerts.getPageable(), alerts.getTotalElements());
    }

    @Transactional(readOnly = true)
    public AlertResponse getById(Long userId, Long id) {
        PriceAlert alert = priceAlertRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new OwnershipMismatchException("Alert not found"));
        Item item = itemRepository.findById(alert.getItemId()).orElse(null);
        return AlertMapper.toResponse(alert, item);
    }

    @Transactional
    public AlertResponse create(Long userId, CreateAlertRequest request) {
        // API_CONTRACT.md §9: itemId not found OR isActive=false both map to the same 404 —
        // same convention as CreateDropRequest.itemId (Milestone 5).
        Item item = itemRepository.findById(request.itemId())
                .filter(i -> Boolean.TRUE.equals(i.getIsActive()))
                .orElseThrow(() -> new ResourceNotFoundException("Item not found or inactive"));

        boolean notifyEmail = request.notifyEmail() == null ? Boolean.TRUE : request.notifyEmail();
        boolean notifyInApp = request.notifyInApp() == null ? Boolean.TRUE : request.notifyInApp();
        if (!notifyEmail && !notifyInApp) {
            throw new ValidationException("At least one of notifyEmail or notifyInApp must be true");
        }

        PriceAlert alert = PriceAlert.builder()
                .userId(userId)
                .itemId(item.getId())
                .targetPriceUsd(request.targetPriceUsd())
                .notifyEmail(notifyEmail)
                .notifyInApp(notifyInApp)
                .status(AlertStatus.ACTIVE)
                .createdAt(clock.instant())
                .build();
        PriceAlert saved = priceAlertRepository.save(alert);
        return AlertMapper.toResponse(saved, item);
    }

    @Transactional
    public AlertResponse update(Long userId, Long id, UpdateAlertRequest request) {
        PriceAlert alert = priceAlertRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new OwnershipMismatchException("Alert not found"));

        if (request.targetPriceUsd() != null) {
            alert.setTargetPriceUsd(request.targetPriceUsd());
        }
        if (request.notifyEmail() != null) {
            alert.setNotifyEmail(request.notifyEmail());
        }
        if (request.notifyInApp() != null) {
            alert.setNotifyInApp(request.notifyInApp());
        }
        // M8 gap fix (Postman testing round): re-validate the MERGED state, not just the raw
        // request — a PATCH that only sends notifyEmail=false while notifyInApp is already
        // false on the existing row must be rejected too, same as CREATE's rule. Checked after
        // both fields above are applied so this sees the final resulting state.
        if (!Boolean.TRUE.equals(alert.getNotifyEmail()) && !Boolean.TRUE.equals(alert.getNotifyInApp())) {
            throw new ValidationException("At least one of notifyEmail or notifyInApp must be true");
        }
        if (request.status() != null) {
            // TRIGGERED is a system-only state — a client can only ever ask for ACTIVE or
            // DISABLED (M8 Implementation Authorization §2: no auto re-arm, and no client
            // path to self-trigger either).
            if (!"ACTIVE".equals(request.status()) && !"DISABLED".equals(request.status())) {
                throw new ValidationException("status must be ACTIVE or DISABLED");
            }
            alert.setStatus(AlertStatus.valueOf(request.status()));
        }

        PriceAlert saved = priceAlertRepository.save(alert);
        Item item = itemRepository.findById(saved.getItemId()).orElse(null);
        return AlertMapper.toResponse(saved, item);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        PriceAlert alert = priceAlertRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new OwnershipMismatchException("Alert not found"));
        // M8 Postman-testing gap fix: notifications.alert_id is ON DELETE NO ACTION at the DB
        // level (V12 migration comment has the full SQL Server multiple-cascade-paths reason),
        // so the app must detach existing notifications from this alert BEFORE the hard delete
        // below, or the delete would fail with an FK violation. This preserves notification
        // history (alertId -> NULL) rather than deleting it — replicating the original
        // SET-NULL intent at the application layer instead of the DB layer.
        notificationRepository.detachFromAlert(alert.getId());
        // Hard delete — ERD.md §6.4: price_alerts is user-initiated-cleanup, not a historical asset value.
        priceAlertRepository.delete(alert);
    }

    /** Batch-resolves {@code Item} for a page of alerts — one {@code findAllById} call, not N+1 (same convention as {@code DropService.toResponses}). */
    private List<AlertResponse> toResponses(List<PriceAlert> alerts) {
        if (alerts.isEmpty()) {
            return List.of();
        }
        Set<Long> itemIds = alerts.stream().map(PriceAlert::getItemId).collect(Collectors.toSet());
        Map<Long, Item> itemsById = itemRepository.findAllById(itemIds).stream()
                .collect(Collectors.toMap(Item::getId, i -> i));
        return alerts.stream()
                .map(a -> AlertMapper.toResponse(a, itemsById.get(a.getItemId())))
                .collect(Collectors.toList());
    }

    private Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.DESC, DEFAULT_SORT_FIELD);
        }
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        if (!SORT_WHITELIST.contains(field)) {
            throw new ValidationException("sort field not allowed: " + field + " (allowed: " + SORT_WHITELIST + ")");
        }
        Sort.Direction direction = parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim())
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        return Sort.by(direction, field);
    }
}

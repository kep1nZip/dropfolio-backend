package com.dropfolio.admin.service;

import com.dropfolio.admin.dto.DashboardResponse;
import com.dropfolio.admin.dto.LastSyncRef;
import com.dropfolio.item.repository.ItemRepository;
import com.dropfolio.scheduler.entity.JobStatus;
import com.dropfolio.scheduler.entity.JobType;
import com.dropfolio.scheduler.entity.SyncJob;
import com.dropfolio.scheduler.repository.SyncJobRepository;
import com.dropfolio.user.entity.UserStatus;
import com.dropfolio.user.repository.UserRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

/**
 * M9 — GET /admin/dashboard (API_CONTRACT.md §11). Pure aggregation over existing tables — no
 * new migration, no synchronous external call (M9 Implementation Authorization §2:
 * {@code priceProviderHealth} reads the already-in-process {@code priceProvider} circuit
 * breaker's current state, never calls Steam).
 */
@Service
public class AdminDashboardService {

    private static final String PRICE_PROVIDER_INSTANCE = "priceProvider";
    private static final Duration FAILED_SYNC_WINDOW = Duration.ofHours(24);

    private final UserRepository userRepository;
    private final ItemRepository itemRepository;
    private final SyncJobRepository syncJobRepository;
    private final CircuitBreaker priceProviderCircuitBreaker;
    private final Clock clock;

    public AdminDashboardService(UserRepository userRepository,
                                  ItemRepository itemRepository,
                                  SyncJobRepository syncJobRepository,
                                  CircuitBreakerRegistry circuitBreakerRegistry,
                                  Clock clock) {
        this.userRepository = userRepository;
        this.itemRepository = itemRepository;
        this.syncJobRepository = syncJobRepository;
        this.priceProviderCircuitBreaker = circuitBreakerRegistry.circuitBreaker(PRICE_PROVIDER_INSTANCE);
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DashboardResponse getDashboard() {
        long totalUsers = userRepository.count();
        long activeUsers = userRepository.countByStatus(UserStatus.ACTIVE);
        long totalTrackedItems = itemRepository.count();

        LastSyncRef lastSync = syncJobRepository.findFirstByJobTypeOrderByStartedAtDesc(JobType.PRICE_SYNC)
                .map(this::toLastSyncRef)
                .orElse(null);

        long failedSyncCount24h = syncJobRepository.countByJobTypeAndStatusAndStartedAtAfter(
                JobType.PRICE_SYNC, JobStatus.FAILED, clock.instant().minus(FAILED_SYNC_WINDOW));

        String priceProviderHealth = mapHealth(priceProviderCircuitBreaker.getState());

        return new DashboardResponse(totalUsers, activeUsers, totalTrackedItems, lastSync, failedSyncCount24h, priceProviderHealth);
    }

    private LastSyncRef toLastSyncRef(SyncJob job) {
        return new LastSyncRef(job.getStatus().name(), job.getFinishedAt(), job.getItemsProcessed());
    }

    /**
     * Interpretation note (flagged, not silently assumed): API_CONTRACT.md §11 shows only one
     * example value, {@code "HEALTHY"}, and doesn't enumerate the full set. Mapped from
     * resilience4j's {@link CircuitBreaker.State} as: {@code CLOSED -> HEALTHY} (normal),
     * {@code HALF_OPEN -> DEGRADED} (recovering, still testing), {@code OPEN}/{@code
     * FORCED_OPEN -> DOWN} (failing/forced off), anything else (e.g. {@code DISABLED}/{@code
     * METRICS_ONLY}, not used by this project's config) {@code -> UNKNOWN}.
     */
    private String mapHealth(CircuitBreaker.State state) {
        return switch (state) {
            case CLOSED -> "HEALTHY";
            case HALF_OPEN -> "DEGRADED";
            case OPEN, FORCED_OPEN -> "DOWN";
            default -> "UNKNOWN";
        };
    }
}

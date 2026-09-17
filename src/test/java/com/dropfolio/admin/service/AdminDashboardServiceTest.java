package com.dropfolio.admin.service;

import com.dropfolio.admin.dto.DashboardResponse;
import com.dropfolio.item.repository.ItemRepository;
import com.dropfolio.scheduler.entity.JobStatus;
import com.dropfolio.scheduler.entity.JobType;
import com.dropfolio.scheduler.entity.SyncJob;
import com.dropfolio.scheduler.entity.TriggeredBy;
import com.dropfolio.scheduler.repository.SyncJobRepository;
import com.dropfolio.user.entity.UserStatus;
import com.dropfolio.user.repository.UserRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Unit tests for {@link AdminDashboardService} — M9 Implementation Authorization §2/§14. */
@ExtendWith(MockitoExtension.class)
class AdminDashboardServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-13T12:00:00Z");

    @Mock private UserRepository userRepository;
    @Mock private ItemRepository itemRepository;
    @Mock private SyncJobRepository syncJobRepository;

    private Clock clock;
    private AdminDashboardService adminDashboardService;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
        adminDashboardService = new AdminDashboardService(
                userRepository, itemRepository, syncJobRepository, circuitBreakerRegistry, clock);
    }

    private SyncJob syncJob(JobStatus status, Instant startedAt, Instant finishedAt, int processed) {
        return SyncJob.builder().id(1L).jobType(JobType.PRICE_SYNC).status(status)
                .itemsProcessed(processed).triggeredBy(TriggeredBy.SCHEDULER)
                .startedAt(startedAt).finishedAt(finishedAt).build();
    }

    @Test
    void getDashboard_aggregatesAllFieldsFromExistingTables() {
        when(userRepository.count()).thenReturn(1204L);
        when(userRepository.countByStatus(UserStatus.ACTIVE)).thenReturn(980L);
        when(itemRepository.count()).thenReturn(15420L);
        when(syncJobRepository.findFirstByJobTypeOrderByStartedAtDesc(JobType.PRICE_SYNC))
                .thenReturn(Optional.of(syncJob(JobStatus.SUCCESS, FIXED_INSTANT.minusSeconds(60), FIXED_INSTANT, 118)));
        when(syncJobRepository.countByJobTypeAndStatusAndStartedAtAfter(
                eq(JobType.PRICE_SYNC), eq(JobStatus.FAILED), any(Instant.class))).thenReturn(1L);

        DashboardResponse response = adminDashboardService.getDashboard();

        assertThat(response.totalUsers()).isEqualTo(1204L);
        assertThat(response.activeUsers()).isEqualTo(980L);
        assertThat(response.totalTrackedItems()).isEqualTo(15420L);
        assertThat(response.lastPriceSync().status()).isEqualTo("SUCCESS");
        assertThat(response.lastPriceSync().itemsProcessed()).isEqualTo(118);
        assertThat(response.failedSyncCount24h()).isEqualTo(1L);
        assertThat(response.priceProviderHealth()).isEqualTo("HEALTHY");
    }

    @Test
    void getDashboard_noSyncJobEver_lastPriceSyncIsNull() {
        when(syncJobRepository.findFirstByJobTypeOrderByStartedAtDesc(JobType.PRICE_SYNC)).thenReturn(Optional.empty());
        when(syncJobRepository.countByJobTypeAndStatusAndStartedAtAfter(any(), any(), any())).thenReturn(0L);

        DashboardResponse response = adminDashboardService.getDashboard();

        assertThat(response.lastPriceSync()).isNull();
    }

    @Test
    void getDashboard_failedSyncQuery_usesA24HourWindow() {
        when(syncJobRepository.findFirstByJobTypeOrderByStartedAtDesc(any())).thenReturn(Optional.empty());
        when(syncJobRepository.countByJobTypeAndStatusAndStartedAtAfter(any(), any(), any())).thenReturn(0L);

        adminDashboardService.getDashboard();

        var captor = org.mockito.ArgumentCaptor.forClass(Instant.class);
        org.mockito.Mockito.verify(syncJobRepository).countByJobTypeAndStatusAndStartedAtAfter(
                eq(JobType.PRICE_SYNC), eq(JobStatus.FAILED), captor.capture());
        assertThat(captor.getValue()).isEqualTo(FIXED_INSTANT.minus(Duration.ofHours(24)));
    }

    @Test
    void getDashboard_circuitBreakerClosed_mapsToHealthy() {
        when(syncJobRepository.findFirstByJobTypeOrderByStartedAtDesc(any())).thenReturn(Optional.empty());
        when(syncJobRepository.countByJobTypeAndStatusAndStartedAtAfter(any(), any(), any())).thenReturn(0L);

        assertThat(adminDashboardService.getDashboard().priceProviderHealth()).isEqualTo("HEALTHY");
    }

    @Test
    void getDashboard_circuitBreakerOpen_mapsToDown() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(2).minimumNumberOfCalls(2).failureRateThreshold(50).build();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        CircuitBreaker breaker = registry.circuitBreaker("priceProvider");
        breaker.transitionToOpenState();
        AdminDashboardService service = new AdminDashboardService(userRepository, itemRepository, syncJobRepository, registry, clock);
        when(syncJobRepository.findFirstByJobTypeOrderByStartedAtDesc(any())).thenReturn(Optional.empty());
        when(syncJobRepository.countByJobTypeAndStatusAndStartedAtAfter(any(), any(), any())).thenReturn(0L);

        assertThat(service.getDashboard().priceProviderHealth()).isEqualTo("DOWN");
    }

    @Test
    void getDashboard_circuitBreakerHalfOpen_mapsToDegraded() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        CircuitBreaker breaker = registry.circuitBreaker("priceProvider");
        breaker.transitionToOpenState();
        breaker.transitionToHalfOpenState();
        AdminDashboardService service = new AdminDashboardService(userRepository, itemRepository, syncJobRepository, registry, clock);
        when(syncJobRepository.findFirstByJobTypeOrderByStartedAtDesc(any())).thenReturn(Optional.empty());
        when(syncJobRepository.countByJobTypeAndStatusAndStartedAtAfter(any(), any(), any())).thenReturn(0L);

        assertThat(service.getDashboard().priceProviderHealth()).isEqualTo("DEGRADED");
    }
}

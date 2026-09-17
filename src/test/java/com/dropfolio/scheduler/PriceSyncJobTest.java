package com.dropfolio.scheduler;

import com.dropfolio.item.entity.Item;
import com.dropfolio.item.entity.ItemType;
import com.dropfolio.item.repository.ItemRepository;
import com.dropfolio.pricing.exception.ItemPriceSyncException;
import com.dropfolio.pricing.service.PriceSyncService;
import com.dropfolio.scheduler.entity.JobStatus;
import com.dropfolio.scheduler.entity.JobType;
import com.dropfolio.scheduler.entity.SyncJob;
import com.dropfolio.scheduler.entity.TriggeredBy;
import com.dropfolio.scheduler.lock.SchedulerLockService;
import com.dropfolio.scheduler.repository.SyncJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PriceSyncJob} — M7 Implementation Authorization §22. Covers: all
 * items successful, partial item failures (batch continues, job still SUCCESS), retry
 * exhaustion surfacing as an item-level failure, fatal job failure (DB error mid-batch ->
 * FAILED, lock still released), and that the lock is released in the admin-triggered path's
 * {@code finally} regardless of outcome. "Inactive items skipped" is exercised at the
 * repository-query boundary: {@code itemRepository.findByIsActiveTrue()} is mocked to return
 * only active items, so any inactive item never reaches {@code priceSyncService.syncItem(...)}.
 */
@ExtendWith(MockitoExtension.class)
class PriceSyncJobTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-10T00:00:00Z");

    @Mock private SchedulerLockService lockService;
    @Mock private SyncJobRepository syncJobRepository;
    @Mock private ItemRepository itemRepository;
    @Mock private PriceSyncService priceSyncService;

    private Clock clock;
    private PriceSyncJob priceSyncJob;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        priceSyncJob = new PriceSyncJob(lockService, syncJobRepository, itemRepository, priceSyncService, clock);
        // NOTE: no global `syncJobRepository.save(...)` stub here (deliberately) — it used to
        // live in setUp(), but that made Mockito flag UnnecessaryStubbingException on
        // scheduledPriceSync_lockUnavailable_skipsSilently_noJobRowCreated, since save() is
        // never called on that path. Stubbed per-test instead, only where actually needed.
    }

    private Item activeItem(long id) {
        return Item.builder().id(id).name("Item " + id).type(ItemType.SKIN)
                .marketHashName("Item " + id).isActive(true).build();
    }

    @Test
    void scheduledPriceSync_lockUnavailable_skipsSilently_noJobRowCreated() {
        when(lockService.tryAcquire("price-sync")).thenReturn(null);

        priceSyncJob.scheduledPriceSync();

        verify(syncJobRepository, never()).save(any());
        verify(itemRepository, never()).findByIsActiveTrue();
        verify(lockService, never()).release(any(), any());
    }

    @Test
    void scheduledPriceSync_allItemsSuccessful_jobMarkedSuccess_lockReleased() {
        when(lockService.tryAcquire("price-sync")).thenReturn("owner-1");
        when(syncJobRepository.save(any(SyncJob.class))).thenAnswer(inv -> inv.getArgument(0));
        List<Item> items = List.of(activeItem(1L), activeItem(2L), activeItem(3L));
        when(itemRepository.findByIsActiveTrue()).thenReturn(items);

        priceSyncJob.scheduledPriceSync();

        ArgumentCaptor<SyncJob> jobCaptor = ArgumentCaptor.forClass(SyncJob.class);
        verify(syncJobRepository, times(2)).save(jobCaptor.capture());
        SyncJob finalState = jobCaptor.getAllValues().get(1);
        assertThat(finalState.getStatus()).isEqualTo(JobStatus.SUCCESS);
        assertThat(finalState.getItemsProcessed()).isEqualTo(3);
        assertThat(finalState.getTriggeredBy()).isEqualTo(TriggeredBy.SCHEDULER);
        assertThat(finalState.getTriggeredByUserId()).isNull();
        verify(priceSyncService, times(3)).syncItem(any());
        verify(lockService).release("price-sync", "owner-1");
    }

    @Test
    void scheduledPriceSync_partialItemFailure_batchContinues_jobStillSuccess() {
        when(lockService.tryAcquire("price-sync")).thenReturn("owner-1");
        when(syncJobRepository.save(any(SyncJob.class))).thenAnswer(inv -> inv.getArgument(0));
        Item ok1 = activeItem(1L);
        Item failing = activeItem(2L);
        Item ok2 = activeItem(3L);
        when(itemRepository.findByIsActiveTrue()).thenReturn(List.of(ok1, failing, ok2));
        // doAnswer (matched by item id) rather than doThrow(...).when(...).syncItem(failing) —
        // the latter tripped Mockito's PotentialStubbingProblem here (strict stubbing sees
        // syncItem(ok1)/syncItem(ok2) as unmatched invocations of an argument-specific stub
        // registered against a different Item instance with equal field values but no
        // equals()/hashCode() override). Matching on id inside doAnswer sidesteps that.
        doAnswer(invocation -> {
            Item item = invocation.getArgument(0);
            if (item != null && Long.valueOf(2L).equals(item.getId())) {
                throw new ItemPriceSyncException("retry exhausted");
            }
            return null;
        }).when(priceSyncService).syncItem(any(Item.class));

        priceSyncJob.scheduledPriceSync();

        ArgumentCaptor<SyncJob> jobCaptor = ArgumentCaptor.forClass(SyncJob.class);
        verify(syncJobRepository, times(2)).save(jobCaptor.capture());
        SyncJob finalState = jobCaptor.getAllValues().get(1);
        assertThat(finalState.getStatus()).isEqualTo(JobStatus.SUCCESS);
        // 2 of 3 succeeded — the failing item is skipped, not counted, batch still completes.
        assertThat(finalState.getItemsProcessed()).isEqualTo(2);
        verify(priceSyncService).syncItem(ok1);
        verify(priceSyncService).syncItem(failing);
        verify(priceSyncService).syncItem(ok2);
        verify(lockService).release("price-sync", "owner-1");
    }

    @Test
    void scheduledPriceSync_inactiveItems_neverReachSyncItem() {
        when(lockService.tryAcquire("price-sync")).thenReturn("owner-1");
        when(syncJobRepository.save(any(SyncJob.class))).thenAnswer(inv -> inv.getArgument(0));
        // Repository query itself filters to active-only — an inactive item is simply absent
        // from what it returns, matching Item.isActive=true scope (M7 §5).
        when(itemRepository.findByIsActiveTrue()).thenReturn(List.of(activeItem(1L)));

        priceSyncJob.scheduledPriceSync();

        verify(priceSyncService, times(1)).syncItem(any());
    }

    @Test
    void scheduledPriceSync_fatalFailure_jobMarkedFailed_lockStillReleased() {
        when(lockService.tryAcquire("price-sync")).thenReturn("owner-1");
        when(syncJobRepository.save(any(SyncJob.class))).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.findByIsActiveTrue())
                .thenThrow(new DataAccessResourceFailureException("db unavailable"));

        priceSyncJob.scheduledPriceSync();

        ArgumentCaptor<SyncJob> jobCaptor = ArgumentCaptor.forClass(SyncJob.class);
        verify(syncJobRepository, times(2)).save(jobCaptor.capture());
        SyncJob finalState = jobCaptor.getAllValues().get(1);
        assertThat(finalState.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(finalState.getErrorMessage()).contains("db unavailable");
        assertThat(finalState.getFinishedAt()).isEqualTo(FIXED_INSTANT);
        verify(lockService).release("price-sync", "owner-1");
    }

    @Test
    void scheduledPriceSync_persistenceFailureDuringItem_isTreatedAsFatal_notItemLevel() {
        when(lockService.tryAcquire("price-sync")).thenReturn("owner-1");
        when(syncJobRepository.save(any(SyncJob.class))).thenAnswer(inv -> inv.getArgument(0));
        Item item = activeItem(1L);
        when(itemRepository.findByIsActiveTrue()).thenReturn(List.of(item));
        doThrow(new DataAccessResourceFailureException("db down mid-batch")).when(priceSyncService).syncItem(item);

        priceSyncJob.scheduledPriceSync();

        ArgumentCaptor<SyncJob> jobCaptor = ArgumentCaptor.forClass(SyncJob.class);
        verify(syncJobRepository, times(2)).save(jobCaptor.capture());
        assertThat(jobCaptor.getAllValues().get(1).getStatus()).isEqualTo(JobStatus.FAILED);
        verify(lockService).release("price-sync", "owner-1");
    }

    @Test
    void runTriggeredByAdmin_executesJobAndReleasesLock() {
        SyncJob runningJob = SyncJob.builder().id(42L).jobType(JobType.PRICE_SYNC).status(JobStatus.RUNNING)
                .itemsProcessed(0).triggeredBy(TriggeredBy.ADMIN).triggeredByUserId(7L)
                .startedAt(FIXED_INSTANT).build();
        when(syncJobRepository.findById(42L)).thenReturn(Optional.of(runningJob));
        when(syncJobRepository.save(any(SyncJob.class))).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.findByIsActiveTrue()).thenReturn(List.of(activeItem(1L)));

        priceSyncJob.runTriggeredByAdmin(42L, "owner-token-abc");

        ArgumentCaptor<SyncJob> jobCaptor = ArgumentCaptor.forClass(SyncJob.class);
        verify(syncJobRepository).save(jobCaptor.capture());
        assertThat(jobCaptor.getValue().getStatus()).isEqualTo(JobStatus.SUCCESS);
        verify(lockService).release("price-sync", "owner-token-abc");
    }

    @Test
    void runTriggeredByAdmin_jobRowMissing_stillReleasesLock() {
        when(syncJobRepository.findById(999L)).thenReturn(Optional.empty());

        // Production code deliberately throws IllegalStateException when the job row is
        // missing (PriceSyncJob.runTriggeredByAdmin) rather than swallowing it — this is a
        // genuinely unexpected condition (AdminSyncService always creates the row before
        // dispatching), so surfacing it loudly is correct; production code was not changed to
        // accommodate this test.
        assertThrows(IllegalStateException.class,
                () -> priceSyncJob.runTriggeredByAdmin(999L, "owner-token-abc"));

        verify(lockService).release("price-sync", "owner-token-abc");
        verify(itemRepository, never()).findByIsActiveTrue();
    }
}

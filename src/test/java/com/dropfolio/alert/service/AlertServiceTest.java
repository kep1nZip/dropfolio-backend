package com.dropfolio.alert.service;

import com.dropfolio.alert.dto.AlertResponse;
import com.dropfolio.alert.dto.CreateAlertRequest;
import com.dropfolio.alert.dto.UpdateAlertRequest;
import com.dropfolio.alert.entity.AlertStatus;
import com.dropfolio.alert.entity.PriceAlert;
import com.dropfolio.common.exception.OwnershipMismatchException;
import com.dropfolio.common.exception.ResourceNotFoundException;
import com.dropfolio.common.exception.ValidationException;
import com.dropfolio.item.entity.Item;
import com.dropfolio.item.entity.ItemType;
import com.dropfolio.item.repository.ItemRepository;
import com.dropfolio.alert.repository.PriceAlertRepository;
import com.dropfolio.notification.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link AlertService} — M8 Implementation Authorization §18 Alert CRUD/validation/ownership. */
@ExtendWith(MockitoExtension.class)
class AlertServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-11T00:00:00Z");
    private static final Long USER_ID = 7L;
    private static final Long OTHER_USER_ID = 8L;

    @Mock private PriceAlertRepository priceAlertRepository;
    @Mock private ItemRepository itemRepository;
    @Mock private NotificationRepository notificationRepository;

    private Clock clock;
    private AlertService alertService;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        alertService = new AlertService(priceAlertRepository, itemRepository, notificationRepository, clock);
    }

    private Item activeItem(long id, String name) {
        return Item.builder().id(id).name(name).type(ItemType.SKIN).marketHashName(name).isActive(true).build();
    }

    private Item inactiveItem(long id) {
        return Item.builder().id(id).name("Retired Skin").type(ItemType.SKIN).marketHashName("Retired Skin").isActive(false).build();
    }

    // ---- create ----

    @Test
    void create_success_defaultsNotifyFlagsToTrue_statusActive() {
        Item item = activeItem(5L, "Revolution Case");
        when(itemRepository.findById(5L)).thenReturn(Optional.of(item));
        when(priceAlertRepository.save(any(PriceAlert.class))).thenAnswer(inv -> {
            PriceAlert a = inv.getArgument(0);
            a.setId(100L);
            return a;
        });

        CreateAlertRequest request = new CreateAlertRequest(5L, new BigDecimal("5.00"), null, null);
        AlertResponse response = alertService.create(USER_ID, request);

        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.notifyEmail()).isTrue();
        assertThat(response.notifyInApp()).isTrue();
        assertThat(response.item().id()).isEqualTo(5L);
        assertThat(response.item().name()).isEqualTo("Revolution Case");
    }

    @Test
    void create_itemNotFound_throwsResourceNotFound() {
        when(itemRepository.findById(999L)).thenReturn(Optional.empty());
        CreateAlertRequest request = new CreateAlertRequest(999L, new BigDecimal("5.00"), true, true);

        assertThatThrownBy(() -> alertService.create(USER_ID, request))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(priceAlertRepository, never()).save(any());
    }

    @Test
    void create_itemInactive_throwsResourceNotFound() {
        when(itemRepository.findById(5L)).thenReturn(Optional.of(inactiveItem(5L)));
        CreateAlertRequest request = new CreateAlertRequest(5L, new BigDecimal("5.00"), true, true);

        assertThatThrownBy(() -> alertService.create(USER_ID, request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void create_bothNotifyFlagsFalse_throwsValidation() {
        when(itemRepository.findById(5L)).thenReturn(Optional.of(activeItem(5L, "Case")));
        CreateAlertRequest request = new CreateAlertRequest(5L, new BigDecimal("5.00"), false, false);

        assertThatThrownBy(() -> alertService.create(USER_ID, request))
                .isInstanceOf(ValidationException.class);
        verify(priceAlertRepository, never()).save(any());
    }

    // ---- getById / ownership ----

    @Test
    void getById_ownedByCaller_returnsResponse() {
        PriceAlert alert = PriceAlert.builder().id(1L).userId(USER_ID).itemId(5L)
                .targetPriceUsd(new BigDecimal("5.00")).notifyEmail(true).notifyInApp(true)
                .status(AlertStatus.ACTIVE).createdAt(FIXED_INSTANT).build();
        when(priceAlertRepository.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(alert));
        when(itemRepository.findById(5L)).thenReturn(Optional.of(activeItem(5L, "Case")));

        AlertResponse response = alertService.getById(USER_ID, 1L);

        assertThat(response.id()).isEqualTo(1L);
    }

    @Test
    void getById_ownedBySomeoneElse_throwsOwnershipMismatch_notForbidden() {
        // findByIdAndUserId(1L, USER_ID) never matches a row owned by OTHER_USER_ID -> Optional.empty()
        when(priceAlertRepository.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> alertService.getById(USER_ID, 1L))
                .isInstanceOf(OwnershipMismatchException.class);
    }

    @Test
    void getById_genuinelyMissing_throwsOwnershipMismatch_sameAsMismatch() {
        when(priceAlertRepository.findByIdAndUserId(999L, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> alertService.getById(USER_ID, 999L))
                .isInstanceOf(OwnershipMismatchException.class);
    }

    // ---- update ----

    @Test
    void update_partialFields_onlyChangesProvidedFields() {
        PriceAlert alert = PriceAlert.builder().id(1L).userId(USER_ID).itemId(5L)
                .targetPriceUsd(new BigDecimal("5.00")).notifyEmail(true).notifyInApp(true)
                .status(AlertStatus.ACTIVE).createdAt(FIXED_INSTANT).build();
        when(priceAlertRepository.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(alert));
        when(priceAlertRepository.save(any(PriceAlert.class))).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.findById(5L)).thenReturn(Optional.of(activeItem(5L, "Case")));

        UpdateAlertRequest request = new UpdateAlertRequest(new BigDecimal("9.99"), null, null, null);
        AlertResponse response = alertService.update(USER_ID, 1L, request);

        assertThat(response.targetPriceUsd()).isEqualByComparingTo(new BigDecimal("9.99"));
        assertThat(response.notifyEmail()).isTrue(); // unchanged
        assertThat(response.notifyInApp()).isTrue(); // unchanged
    }

    @Test
    void update_statusDisabled_allowed() {
        PriceAlert alert = PriceAlert.builder().id(1L).userId(USER_ID).itemId(5L)
                .targetPriceUsd(new BigDecimal("5.00")).notifyEmail(true).notifyInApp(true)
                .status(AlertStatus.ACTIVE).createdAt(FIXED_INSTANT).build();
        when(priceAlertRepository.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(alert));
        when(priceAlertRepository.save(any(PriceAlert.class))).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.findById(5L)).thenReturn(Optional.of(activeItem(5L, "Case")));

        UpdateAlertRequest request = new UpdateAlertRequest(null, null, null, "DISABLED");
        AlertResponse response = alertService.update(USER_ID, 1L, request);

        assertThat(response.status()).isEqualTo("DISABLED");
    }

    @Test
    void update_statusTriggered_rejected_clientCannotSelfTrigger() {
        PriceAlert alert = PriceAlert.builder().id(1L).userId(USER_ID).itemId(5L)
                .targetPriceUsd(new BigDecimal("5.00")).notifyEmail(true).notifyInApp(true)
                .status(AlertStatus.ACTIVE).createdAt(FIXED_INSTANT).build();
        when(priceAlertRepository.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(alert));

        UpdateAlertRequest request = new UpdateAlertRequest(null, null, null, "TRIGGERED");

        assertThatThrownBy(() -> alertService.update(USER_ID, 1L, request))
                .isInstanceOf(ValidationException.class);
        verify(priceAlertRepository, never()).save(any());
    }

    /** M8 §2 lock: no auto re-arm — but explicit user action via PATCH IS the allowed path back to ACTIVE. */
    @Test
    void update_triggeredAlert_canBeManuallyReactivatedByUser() {
        PriceAlert alert = PriceAlert.builder().id(1L).userId(USER_ID).itemId(5L)
                .targetPriceUsd(new BigDecimal("5.00")).notifyEmail(true).notifyInApp(true)
                .status(AlertStatus.TRIGGERED).createdAt(FIXED_INSTANT).triggeredAt(FIXED_INSTANT).build();
        when(priceAlertRepository.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(alert));
        when(priceAlertRepository.save(any(PriceAlert.class))).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.findById(5L)).thenReturn(Optional.of(activeItem(5L, "Case")));

        UpdateAlertRequest request = new UpdateAlertRequest(null, null, null, "ACTIVE");
        AlertResponse response = alertService.update(USER_ID, 1L, request);

        assertThat(response.status()).isEqualTo("ACTIVE");
    }

    @Test
    void update_ownershipMismatch_throws404NotForbidden() {
        when(priceAlertRepository.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.empty());
        UpdateAlertRequest request = new UpdateAlertRequest(new BigDecimal("1.00"), null, null, null);

        assertThatThrownBy(() -> alertService.update(USER_ID, 1L, request))
                .isInstanceOf(OwnershipMismatchException.class);
    }

    /** M8 Postman-testing gap fix: PATCH must apply the same both-channels-false rule as CREATE, evaluated on the MERGED state. */
    @Test
    void update_bothNotifyFlagsFalseInSameRequest_throwsValidation() {
        PriceAlert alert = PriceAlert.builder().id(1L).userId(USER_ID).itemId(5L)
                .targetPriceUsd(new BigDecimal("5.00")).notifyEmail(true).notifyInApp(true)
                .status(AlertStatus.ACTIVE).createdAt(FIXED_INSTANT).build();
        when(priceAlertRepository.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(alert));

        UpdateAlertRequest request = new UpdateAlertRequest(null, false, false, null);

        assertThatThrownBy(() -> alertService.update(USER_ID, 1L, request))
                .isInstanceOf(ValidationException.class);
        verify(priceAlertRepository, never()).save(any());
    }

    /** Merged-state check: existing notifyInApp=true is untouched by this PATCH, so setting only notifyEmail=false is fine. */
    @Test
    void update_onlyOneFlagFalse_existingOtherFlagStillTrue_allowed() {
        PriceAlert alert = PriceAlert.builder().id(1L).userId(USER_ID).itemId(5L)
                .targetPriceUsd(new BigDecimal("5.00")).notifyEmail(true).notifyInApp(true)
                .status(AlertStatus.ACTIVE).createdAt(FIXED_INSTANT).build();
        when(priceAlertRepository.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(alert));
        when(priceAlertRepository.save(any(PriceAlert.class))).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.findById(5L)).thenReturn(Optional.of(activeItem(5L, "Case")));

        UpdateAlertRequest request = new UpdateAlertRequest(null, false, null, null);
        AlertResponse response = alertService.update(USER_ID, 1L, request);

        assertThat(response.notifyEmail()).isFalse();
        assertThat(response.notifyInApp()).isTrue();
    }

    /** Merged-state check: existing row already has notifyInApp=false; PATCHing notifyEmail=false too must be rejected. */
    @Test
    void update_mergedStateBothFalse_existingFalsePlusPatchFalse_throwsValidation() {
        PriceAlert alert = PriceAlert.builder().id(1L).userId(USER_ID).itemId(5L)
                .targetPriceUsd(new BigDecimal("5.00")).notifyEmail(true).notifyInApp(false)
                .status(AlertStatus.ACTIVE).createdAt(FIXED_INSTANT).build();
        when(priceAlertRepository.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(alert));

        UpdateAlertRequest request = new UpdateAlertRequest(null, false, null, null);

        assertThatThrownBy(() -> alertService.update(USER_ID, 1L, request))
                .isInstanceOf(ValidationException.class);
    }

    // ---- delete ----

    @Test
    void delete_ownedByCaller_detachesNotificationsThenHardDeletes() {
        PriceAlert alert = PriceAlert.builder().id(1L).userId(USER_ID).itemId(5L)
                .targetPriceUsd(new BigDecimal("5.00")).notifyEmail(true).notifyInApp(true)
                .status(AlertStatus.ACTIVE).createdAt(FIXED_INSTANT).build();
        when(priceAlertRepository.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(alert));

        alertService.delete(USER_ID, 1L);

        var inOrder = org.mockito.Mockito.inOrder(notificationRepository, priceAlertRepository);
        inOrder.verify(notificationRepository).detachFromAlert(1L);
        inOrder.verify(priceAlertRepository).delete(alert);
    }

    @Test
    void delete_ownershipMismatch_throws404_doesNotDelete() {
        when(priceAlertRepository.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> alertService.delete(USER_ID, 1L))
                .isInstanceOf(OwnershipMismatchException.class);
        verify(priceAlertRepository, never()).delete(any(PriceAlert.class));
        verify(notificationRepository, never()).detachFromAlert(anyLong());
    }

    // ---- list / validation ----

    @Test
    void list_invalidPage_throwsValidation() {
        assertThatThrownBy(() -> alertService.list(USER_ID, null, 0, 20, null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void list_invalidSize_throwsValidation() {
        assertThatThrownBy(() -> alertService.list(USER_ID, null, 1, 0, null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void list_disallowedSortField_throwsValidation() {
        assertThatThrownBy(() -> alertService.list(USER_ID, null, 1, 20, "status,asc"))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void list_scopedToOwner_batchResolvesItems() {
        PriceAlert alert = PriceAlert.builder().id(1L).userId(USER_ID).itemId(5L)
                .targetPriceUsd(new BigDecimal("5.00")).notifyEmail(true).notifyInApp(true)
                .status(AlertStatus.ACTIVE).createdAt(FIXED_INSTANT).build();
        when(priceAlertRepository.findAll(org.mockito.ArgumentMatchers.<Specification<PriceAlert>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(alert)));
        when(itemRepository.findAllById(any())).thenReturn(List.of(activeItem(5L, "Case")));

        Page<AlertResponse> result = alertService.list(USER_ID, null, 1, 20, null);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).item().name()).isEqualTo("Case");
    }
}

package com.hotelbooking.booking.service;

import com.hotelbooking.booking.domain.Booking;
import com.hotelbooking.booking.domain.BookingRepository;
import com.hotelbooking.booking.domain.BookingStatus;
import com.hotelbooking.booking.security.CurrentUser;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    private static final long TENANT_A = 1L;
    private static final long ROOM_TYPE = 1L;
    private static final long BOOKING_ID = 7L;
    private static final String EMAIL = "a1@example.test";
    private static final Instant NOW = Instant.parse("2026-11-03T02:15:30.987Z");
    private static final LocalDate NIGHT_1 = LocalDate.parse("2026-12-01");
    private static final LocalDate NIGHT_2 = LocalDate.parse("2026-12-02");
    private static final LocalDate CHECK_OUT = LocalDate.parse("2026-12-03");
    private static final BigDecimal BASE_PRICE = new BigDecimal("500000.00");

    private static final CurrentUser USER_A1 = new CurrentUser(TENANT_A, EMAIL, false);
    private static final CurrentUser ADMIN_A = new CurrentUser(TENANT_A, "admin@example.test", true);

    @Mock
    private BookingRepository repository;

    private BookingService service;

    @BeforeEach
    void setUp() {
        service = serviceAt(NOW);
    }

    // ---------- create ----------

    @Test
    void create_reservesNightsAscendingThenInsertsPendingBooking() {
        stubPrice();
        stubReserved(NIGHT_1, 2, 1);
        stubReserved(NIGHT_2, 2, 1);
        when(repository.saveAndFlush(any(Booking.class))).thenAnswer(call -> call.getArgument(0));

        Booking result = service.create(USER_A1, ROOM_TYPE, NIGHT_1, CHECK_OUT, 2);

        assertThat(result.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
        assertThat(result.getTenantId()).isEqualTo(TENANT_A);
        assertThat(result.getRoomTypeId()).isEqualTo(ROOM_TYPE);
        assertThat(result.getUserEmail()).isEqualTo(EMAIL);
        assertThat(result.getCheckIn()).isEqualTo(NIGHT_1);
        assertThat(result.getCheckOut()).isEqualTo(CHECK_OUT);
        assertThat(result.getRoomCount()).isEqualTo(2);
        assertThat(result.getTotalAmount()).isEqualByComparingTo("2000000");
        assertThat(result.getCreatedAt()).isEqualTo(Instant.parse("2026-11-03T02:15:30Z"));
        assertThat(result.getPaymentExpiresAt()).isEqualTo(Instant.parse("2026-11-03T02:30:30Z"));

        InOrder order = inOrder(repository);
        order.verify(repository).findBasePrice(ROOM_TYPE, TENANT_A);
        order.verify(repository).reserveNight(TENANT_A, ROOM_TYPE, NIGHT_1, 2);
        order.verify(repository).reserveNight(TENANT_A, ROOM_TYPE, NIGHT_2, 2);
        order.verify(repository).saveAndFlush(any(Booking.class));
    }

    @Test
    void create_checkOutNightIsNotReserved() {
        stubPrice();
        stubReserved(NIGHT_1, 1, 1);
        when(repository.saveAndFlush(any(Booking.class))).thenAnswer(call -> call.getArgument(0));

        service.create(USER_A1, ROOM_TYPE, NIGHT_1, NIGHT_2, 1);

        verify(repository, never()).reserveNight(TENANT_A, ROOM_TYPE, NIGHT_2, 1);
    }

    @Test
    void create_roomTypeOutsideTenant_isNotFoundWithoutTouchingInventory() {
        when(repository.findBasePrice(ROOM_TYPE, TENANT_A)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(USER_A1, ROOM_TYPE, NIGHT_1, CHECK_OUT, 1))
                .isInstanceOf(RoomTypeNotFoundException.class);

        verify(repository, never()).reserveNight(anyLong(), anyLong(), any(), anyInt());
    }

    @Test
    void create_nightWithoutStock_isUnavailableAndStopsBeforeInsert() {
        stubPrice();
        stubReserved(NIGHT_1, 1, 1);
        stubReserved(NIGHT_2, 1, 0);

        assertThatThrownBy(() -> service.create(USER_A1, ROOM_TYPE, NIGHT_1, CHECK_OUT, 1))
                .isInstanceOf(RoomUnavailableException.class);

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void create_userOutsideTenant_isNotProvisioned() {
        stubPrice();
        stubReserved(NIGHT_1, 1, 1);
        when(repository.saveAndFlush(any(Booking.class))).thenThrow(violationOf(BookingService.CONSTRAINT_USER_TENANT));

        assertThatThrownBy(() -> service.create(USER_A1, ROOM_TYPE, NIGHT_1, NIGHT_2, 1))
                .isInstanceOf(UserNotProvisionedException.class);
    }

    @Test
    void create_otherConstraintFailure_isNotReportedAsBusinessError() {
        stubPrice();
        stubReserved(NIGHT_1, 1, 1);
        when(repository.saveAndFlush(any(Booking.class))).thenThrow(violationOf("chk_booking_status"));

        assertThatThrownBy(() -> service.create(USER_A1, ROOM_TYPE, NIGHT_1, NIGHT_2, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void create_integrityFailureWithoutConstraintName_isNotReportedAsBusinessError() {
        stubPrice();
        stubReserved(NIGHT_1, 1, 1);
        when(repository.saveAndFlush(any(Booking.class))).thenThrow(new DataIntegrityViolationException("unknown"));

        assertThatThrownBy(() -> service.create(USER_A1, ROOM_TYPE, NIGHT_1, NIGHT_2, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void create_databaseDown_isNotReportedAsConflict() {
        when(repository.findBasePrice(ROOM_TYPE, TENANT_A)).thenThrow(new DataAccessResourceFailureException("down"));

        assertThatThrownBy(() -> service.create(USER_A1, ROOM_TYPE, NIGHT_1, CHECK_OUT, 1))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void create_checkInBeforeToday_isRejectedBeforeAnyQuery() {
        assertThatThrownBy(() -> service.create(
                USER_A1, ROOM_TYPE, LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-04"), 1))
                .isInstanceOf(InvalidStayException.class);

        verify(repository, never()).findBasePrice(anyLong(), anyLong());
    }

    @Test
    void create_checkInToday_isAllowed() {
        stubPriceAndAcceptAnyNight();

        service.create(USER_A1, ROOM_TYPE, LocalDate.parse("2026-11-03"), LocalDate.parse("2026-11-04"), 1);

        verify(repository).saveAndFlush(any(Booking.class));
    }

    /** 17:00 UTC ngày 3 đã là 00:00 ngày 4 ở Việt Nam, nên ngày 3 là quá khứ dù theo UTC vẫn là hôm nay. */
    @Test
    void create_todayFollowsVietnamTime_notUtc() {
        BookingService atVietnamMidnight = serviceAt(Instant.parse("2026-11-03T17:00:00Z"));

        assertThatThrownBy(() -> atVietnamMidnight.create(
                USER_A1, ROOM_TYPE, LocalDate.parse("2026-11-03"), LocalDate.parse("2026-11-05"), 1))
                .isInstanceOf(InvalidStayException.class);
    }

    @Test
    void create_justBeforeVietnamMidnight_stillAcceptsSameDay() {
        BookingService beforeMidnight = serviceAt(Instant.parse("2026-11-03T16:59:59Z"));
        stubPriceAndAcceptAnyNight();

        beforeMidnight.create(USER_A1, ROOM_TYPE, LocalDate.parse("2026-11-03"), LocalDate.parse("2026-11-04"), 1);

        verify(repository).saveAndFlush(any(Booking.class));
    }

    // ---------- list và get ----------

    @Test
    void userListsOnlyOwnBookings() {
        List<Booking> own = List.of(booking(BookingStatus.PENDING_PAYMENT));
        when(repository.findByTenantIdAndUserEmailOrderByIdAsc(TENANT_A, EMAIL)).thenReturn(own);

        assertThat(service.list(USER_A1)).isEqualTo(own);

        verify(repository, never()).findByTenantIdOrderByIdAsc(anyLong());
    }

    @Test
    void adminListsAllBookingsInOwnTenant() {
        List<Booking> tenantBookings = List.of(booking(BookingStatus.PENDING_PAYMENT));
        when(repository.findByTenantIdOrderByIdAsc(TENANT_A)).thenReturn(tenantBookings);

        assertThat(service.list(ADMIN_A)).isEqualTo(tenantBookings);

        verify(repository, never()).findByTenantIdAndUserEmailOrderByIdAsc(anyLong(), any());
    }

    @Test
    void userReadsOwnBooking() {
        Booking own = booking(BookingStatus.PENDING_PAYMENT);
        when(repository.findByIdAndTenantIdAndUserEmail(BOOKING_ID, TENANT_A, EMAIL)).thenReturn(Optional.of(own));

        assertThat(service.get(USER_A1, BOOKING_ID)).isSameAs(own);
    }

    @Test
    void userCannotReadBookingOfAnotherOwnerOrTenant() {
        when(repository.findByIdAndTenantIdAndUserEmail(BOOKING_ID, TENANT_A, EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(USER_A1, BOOKING_ID)).isInstanceOf(BookingNotFoundException.class);

        verify(repository, never()).findByIdAndTenantId(anyLong(), anyLong());
    }

    @Test
    void adminReadsAnyBookingInOwnTenant() {
        Booking other = booking(BookingStatus.PENDING_PAYMENT);
        when(repository.findByIdAndTenantId(BOOKING_ID, TENANT_A)).thenReturn(Optional.of(other));

        assertThat(service.get(ADMIN_A, BOOKING_ID)).isSameAs(other);
    }

    @Test
    void adminCannotReadBookingOfAnotherTenant() {
        when(repository.findByIdAndTenantId(BOOKING_ID, TENANT_A)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(ADMIN_A, BOOKING_ID)).isInstanceOf(BookingNotFoundException.class);
    }

    // ---------- pay ----------

    @Test
    void pay_confirmsWithoutTouchingInventory() {
        Booking confirmed = booking(BookingStatus.CONFIRMED);
        when(repository.confirmIfPayable(BOOKING_ID, TENANT_A, EMAIL, NOW)).thenReturn(1);
        stubOwned(confirmed);

        assertThat(service.pay(USER_A1, BOOKING_ID)).isSameAs(confirmed);

        verifyInventoryUntouched();
    }

    @Test
    void pay_notAllowedStatus_isConflict() {
        when(repository.confirmIfPayable(BOOKING_ID, TENANT_A, EMAIL, NOW)).thenReturn(0);
        stubOwned(booking(BookingStatus.CANCELLED));

        assertThatThrownBy(() -> service.pay(USER_A1, BOOKING_ID)).isInstanceOf(BookingStateConflictException.class);

        verifyInventoryUntouched();
    }

    @Test
    void pay_notOwned_isNotFound() {
        when(repository.confirmIfPayable(BOOKING_ID, TENANT_A, EMAIL, NOW)).thenReturn(0);
        when(repository.findByIdAndTenantIdAndUserEmail(BOOKING_ID, TENANT_A, EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.pay(USER_A1, BOOKING_ID)).isInstanceOf(BookingNotFoundException.class);
    }

    // ---------- cancel ----------

    @Test
    void cancel_releasesEveryNightAscending() {
        Booking cancelled = booking(BookingStatus.CANCELLED);
        when(repository.cancelIfPending(BOOKING_ID, TENANT_A, EMAIL)).thenReturn(1);
        stubOwned(cancelled);
        stubReleased(NIGHT_1, 1);
        stubReleased(NIGHT_2, 1);

        assertThat(service.cancel(USER_A1, BOOKING_ID)).isSameAs(cancelled);

        InOrder order = inOrder(repository);
        order.verify(repository).cancelIfPending(BOOKING_ID, TENANT_A, EMAIL);
        order.verify(repository).releaseNight(TENANT_A, ROOM_TYPE, NIGHT_1, 2);
        order.verify(repository).releaseNight(TENANT_A, ROOM_TYPE, NIGHT_2, 2);
    }

    @Test
    void cancel_alreadyCancelled_returnsBookingWithoutReleasingAgain() {
        Booking cancelled = booking(BookingStatus.CANCELLED);
        when(repository.cancelIfPending(BOOKING_ID, TENANT_A, EMAIL)).thenReturn(0);
        stubOwned(cancelled);

        assertThat(service.cancel(USER_A1, BOOKING_ID)).isSameAs(cancelled);

        verifyInventoryUntouched();
    }

    @Test
    void cancel_confirmedBooking_isConflict() {
        when(repository.cancelIfPending(BOOKING_ID, TENANT_A, EMAIL)).thenReturn(0);
        stubOwned(booking(BookingStatus.CONFIRMED));

        assertThatThrownBy(() -> service.cancel(USER_A1, BOOKING_ID)).isInstanceOf(BookingStateConflictException.class);

        verifyInventoryUntouched();
    }

    @Test
    void cancel_expiredBooking_isConflict() {
        when(repository.cancelIfPending(BOOKING_ID, TENANT_A, EMAIL)).thenReturn(0);
        stubOwned(booking(BookingStatus.EXPIRED));

        assertThatThrownBy(() -> service.cancel(USER_A1, BOOKING_ID)).isInstanceOf(BookingStateConflictException.class);

        verifyInventoryUntouched();
    }

    @Test
    void cancel_notOwned_isNotFound() {
        when(repository.cancelIfPending(BOOKING_ID, TENANT_A, EMAIL)).thenReturn(0);
        when(repository.findByIdAndTenantIdAndUserEmail(BOOKING_ID, TENANT_A, EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(USER_A1, BOOKING_ID)).isInstanceOf(BookingNotFoundException.class);
    }

    /** Ném lỗi để transaction rollback cả việc đổi trạng thái, thay vì để sold lệch thêm. */
    @Test
    void cancel_inventoryOutOfSync_failsInsteadOfSilentlyContinuing() {
        when(repository.cancelIfPending(BOOKING_ID, TENANT_A, EMAIL)).thenReturn(1);
        stubOwned(booking(BookingStatus.CANCELLED));
        stubReleased(NIGHT_1, 0);

        assertThatThrownBy(() -> service.cancel(USER_A1, BOOKING_ID)).isInstanceOf(IllegalStateException.class);
    }

    // ---------- expire ----------

    @Test
    void expire_releasesInventoryOfOverdueBooking() {
        when(repository.expireIfOverdue(BOOKING_ID, NOW)).thenReturn(1);
        when(repository.findById(BOOKING_ID)).thenReturn(Optional.of(booking(BookingStatus.EXPIRED)));
        stubReleased(NIGHT_1, 1);
        stubReleased(NIGHT_2, 1);

        assertThat(service.expire(BOOKING_ID)).isTrue();

        verify(repository).releaseNight(TENANT_A, ROOM_TYPE, NIGHT_1, 2);
        verify(repository).releaseNight(TENANT_A, ROOM_TYPE, NIGHT_2, 2);
    }

    @Test
    void expire_notOverdueOrAlreadyHandled_doesNotReleaseInventory() {
        when(repository.expireIfOverdue(BOOKING_ID, NOW)).thenReturn(0);

        assertThat(service.expire(BOOKING_ID)).isFalse();

        verifyInventoryUntouched();
    }

    // ---------- Helpers ----------

    private BookingService serviceAt(Instant now) {
        return new BookingService(repository, Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(15));
    }

    private void stubPrice() {
        when(repository.findBasePrice(ROOM_TYPE, TENANT_A)).thenReturn(Optional.of(BASE_PRICE));
    }

    private void stubPriceAndAcceptAnyNight() {
        stubPrice();
        when(repository.reserveNight(anyLong(), anyLong(), any(), anyInt())).thenReturn(1);
    }

    private void stubReserved(LocalDate night, int roomCount, int updated) {
        when(repository.reserveNight(TENANT_A, ROOM_TYPE, night, roomCount)).thenReturn(updated);
    }

    private void stubReleased(LocalDate night, int updated) {
        when(repository.releaseNight(TENANT_A, ROOM_TYPE, night, 2)).thenReturn(updated);
    }

    private void stubOwned(Booking booking) {
        when(repository.findByIdAndTenantIdAndUserEmail(BOOKING_ID, TENANT_A, EMAIL)).thenReturn(Optional.of(booking));
    }

    private void verifyInventoryUntouched() {
        verify(repository, never()).releaseNight(anyLong(), anyLong(), any(), anyInt());
        verify(repository, never()).reserveNight(anyLong(), anyLong(), any(), anyInt());
    }

    private static DataIntegrityViolationException violationOf(String constraint) {
        return new DataIntegrityViolationException(
                "rejected", new ConstraintViolationException("rejected", new SQLException("rejected"), constraint));
    }

    private static Booking booking(BookingStatus status) {
        Booking booking = Booking.pendingPayment(TENANT_A, ROOM_TYPE, EMAIL, NIGHT_1, CHECK_OUT, 2,
                new BigDecimal("2000000.00"), NOW.plusSeconds(900), NOW);
        ReflectionTestUtils.setField(booking, "id", BOOKING_ID);
        ReflectionTestUtils.setField(booking, "status", status);
        return booking;
    }
}

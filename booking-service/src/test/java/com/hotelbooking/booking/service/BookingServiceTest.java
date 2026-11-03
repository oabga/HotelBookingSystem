package com.hotelbooking.booking.service;

import com.hotelbooking.booking.domain.Booking;
import com.hotelbooking.booking.domain.BookingRepository;
import com.hotelbooking.booking.domain.BookingStatus;
import com.hotelbooking.booking.security.CurrentUser;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    private static final long TENANT_A = 1L;
    private static final Instant NOW = Instant.parse("2026-11-03T02:15:30.987Z");
    private static final Instant CHECK_IN = Instant.parse("2026-12-01T07:00:00Z");
    private static final Instant CHECK_OUT = Instant.parse("2026-12-02T04:00:00Z");

    private static final CurrentUser USER_A1 = new CurrentUser(TENANT_A, "a1@example.test", false);
    private static final CurrentUser ADMIN_A = new CurrentUser(TENANT_A, "admin@example.test", true);

    @Mock
    private BookingRepository repository;

    private BookingService service;

    @BeforeEach
    void setUp() {
        service = new BookingService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void create_savesConfirmedBookingFromJwtIdentity() {
        when(repository.saveAndFlush(any(Booking.class))).thenAnswer(call -> call.getArgument(0));

        Booking result = service.create(USER_A1, 1L, CHECK_IN, CHECK_OUT);

        assertThat(result.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(result.getTenantId()).isEqualTo(TENANT_A);
        assertThat(result.getRoomId()).isEqualTo(1L);
        assertThat(result.getUserEmail()).isEqualTo("a1@example.test");
        assertThat(result.getCheckIn()).isEqualTo(CHECK_IN);
        assertThat(result.getCheckOut()).isEqualTo(CHECK_OUT);
        assertThat(result.getCreatedAt()).isEqualTo(Instant.parse("2026-11-03T02:15:30Z"));
    }

    @Test
    void overlappingTime_isRoomUnavailable() {
        failInsertOnExclusion();
        when(repository.roomExistsInTenant(1L, TENANT_A)).thenReturn(true);

        assertThatThrownBy(() -> create()).isInstanceOf(RoomUnavailableException.class);
    }

    @Test
    void overlappingTimeOnRoomOutsideTenant_isRoomNotFound() {
        failInsertOnExclusion();
        when(repository.roomExistsInTenant(1L, TENANT_A)).thenReturn(false);

        assertThatThrownBy(() -> create()).isInstanceOf(RoomNotFoundException.class);
    }

    @Test
    void tenantMissingFromDatabase_isNotProvisioned() {
        failInsertOn(BookingService.CONSTRAINT_TENANT);

        assertThatThrownBy(() -> create()).isInstanceOf(UserNotProvisionedException.class);
    }

    @Test
    void roomOutsideTenant_isRoomNotFound() {
        failInsertOn(BookingService.CONSTRAINT_ROOM_TENANT);

        assertThatThrownBy(() -> create()).isInstanceOf(RoomNotFoundException.class);
    }

    @Test
    void userOutsideTenant_isNotProvisioned() {
        failInsertOn(BookingService.CONSTRAINT_USER_TENANT);

        assertThatThrownBy(() -> create()).isInstanceOf(UserNotProvisionedException.class);
    }

    @Test
    void deadlockBetweenConcurrentInserts_isRoomUnavailable() {
        when(repository.saveAndFlush(any(Booking.class))).thenThrow(new CannotAcquireLockException("deadlock"));
        when(repository.roomExistsInTenant(1L, TENANT_A)).thenReturn(true);

        assertThatThrownBy(() -> create()).isInstanceOf(RoomUnavailableException.class);
    }

    @Test
    void deadlockOnRoomOutsideTenant_isRoomNotFound() {
        when(repository.saveAndFlush(any(Booking.class))).thenThrow(new CannotAcquireLockException("deadlock"));
        when(repository.roomExistsInTenant(1L, TENANT_A)).thenReturn(false);

        assertThatThrownBy(() -> create()).isInstanceOf(RoomNotFoundException.class);
    }

    @Test
    void otherConstraintFailure_isNotReportedAsBusinessError() {
        failInsertOn("chk_booking_status");

        assertThatThrownBy(() -> create()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void integrityFailureWithoutConstraintName_isNotReportedAsBusinessError() {
        when(repository.saveAndFlush(any(Booking.class))).thenThrow(new DataIntegrityViolationException("unknown"));

        assertThatThrownBy(() -> create()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseDown_isNotReportedAsConflict() {
        when(repository.saveAndFlush(any(Booking.class))).thenThrow(new DataAccessResourceFailureException("down"));

        assertThatThrownBy(() -> create()).isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void userListsOnlyOwnBookings() {
        List<Booking> own = List.of(booking("a1@example.test"));
        when(repository.findByTenantIdAndUserEmailOrderByIdAsc(TENANT_A, "a1@example.test")).thenReturn(own);

        assertThat(service.list(USER_A1)).isEqualTo(own);

        verify(repository, never()).findByTenantIdOrderByIdAsc(anyLong());
    }

    @Test
    void adminListsAllBookingsInOwnTenant() {
        List<Booking> tenantBookings = List.of(booking("a1@example.test"), booking("a2@example.test"));
        when(repository.findByTenantIdOrderByIdAsc(TENANT_A)).thenReturn(tenantBookings);

        assertThat(service.list(ADMIN_A)).isEqualTo(tenantBookings);

        verify(repository, never()).findByTenantIdAndUserEmailOrderByIdAsc(anyLong(), any());
    }

    @Test
    void userReadsOwnBooking() {
        Booking own = booking("a1@example.test");
        when(repository.findByIdAndTenantIdAndUserEmail(7L, TENANT_A, "a1@example.test"))
                .thenReturn(Optional.of(own));

        assertThat(service.get(USER_A1, 7L)).isSameAs(own);
    }

    @Test
    void userCannotReadBookingOfAnotherOwnerOrTenant() {
        when(repository.findByIdAndTenantIdAndUserEmail(7L, TENANT_A, "a1@example.test"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(USER_A1, 7L)).isInstanceOf(BookingNotFoundException.class);

        verify(repository, never()).findByIdAndTenantId(anyLong(), anyLong());
    }

    @Test
    void adminReadsAnyBookingInOwnTenant() {
        Booking other = booking("a2@example.test");
        when(repository.findByIdAndTenantId(7L, TENANT_A)).thenReturn(Optional.of(other));

        assertThat(service.get(ADMIN_A, 7L)).isSameAs(other);
    }

    @Test
    void adminCannotReadBookingOfAnotherTenant() {
        when(repository.findByIdAndTenantId(7L, TENANT_A)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(ADMIN_A, 7L)).isInstanceOf(BookingNotFoundException.class);
    }

    private void create() {
        service.create(USER_A1, 1L, CHECK_IN, CHECK_OUT);
    }

    /** Giống PostgreSQL thật: có SQLState 23P01 nhưng Hibernate không trả tên constraint. */
    private void failInsertOnExclusion() {
        SQLException exclusion = new SQLException("conflict", BookingService.SQLSTATE_EXCLUSION_VIOLATION);
        when(repository.saveAndFlush(any(Booking.class))).thenThrow(new DataIntegrityViolationException(
                "rejected", new ConstraintViolationException("rejected", exclusion, null)));
    }

    private void failInsertOn(String constraint) {
        when(repository.saveAndFlush(any(Booking.class))).thenThrow(new DataIntegrityViolationException(
                "rejected", new ConstraintViolationException("rejected", new SQLException("rejected"), constraint)));
    }

    private static Booking booking(String email) {
        return Booking.confirmed(TENANT_A, 1L, email, CHECK_IN, CHECK_OUT, NOW);
    }
}

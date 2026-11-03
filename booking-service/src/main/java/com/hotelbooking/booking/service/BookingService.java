package com.hotelbooking.booking.service;

import com.hotelbooking.booking.domain.Booking;
import com.hotelbooking.booking.domain.BookingRepository;
import com.hotelbooking.booking.security.CurrentUser;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class BookingService {

    /** Hibernate không trả tên constraint cho lỗi EXCLUDE; bảng booking chỉ có một EXCLUDE là chống trùng giờ. */
    static final String SQLSTATE_EXCLUSION_VIOLATION = "23P01";
    static final String CONSTRAINT_ROOM_TENANT = "fk_booking_room_same_tenant";
    static final String CONSTRAINT_USER_TENANT = "fk_booking_user_same_tenant";
    static final String CONSTRAINT_TENANT = "booking_tenant_id_fkey";

    private final BookingRepository bookings;
    private final Clock clock;

    public BookingService(BookingRepository bookings, Clock clock) {
        this.bookings = bookings;
        this.clock = clock;
    }

    /**
     * Chỉ INSERT rồi để database quyết định, không SELECT kiểm tra trước, vì hai request có thể cùng
     * thấy phòng còn trống. Các ràng buộc của bảng booking chặn trùng giờ và sai tenant (docs/erd-v2.md §3).
     */
    public Booking create(CurrentUser user, long roomId, Instant checkIn, Instant checkOut) {
        Instant createdAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Booking booking = Booking.confirmed(user.tenantId(), roomId, user.email(), checkIn, checkOut, createdAt);
        try {
            return bookings.saveAndFlush(booking);
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex, user, roomId);
        } catch (PessimisticLockingFailureException ex) {
            // Hai INSERT đồng thời vào cùng khoảng giờ có thể chờ lẫn nhau: PostgreSQL hủy một bên với
            // lỗi deadlock thay vì lỗi EXCLUDE. Bên bị hủy chính là bên thua nên xử lý như đè giờ.
            throw timeConflict(user, roomId);
        }
    }

    /** USER chỉ thấy booking của mình; ADMIN thấy toàn bộ booking trong tenant. */
    public List<Booking> list(CurrentUser user) {
        return user.admin()
                ? bookings.findByTenantIdOrderByIdAsc(user.tenantId())
                : bookings.findByTenantIdAndUserEmailOrderByIdAsc(user.tenantId(), user.email());
    }

    /** Không tồn tại, khác tenant hay khác chủ đều là cùng một lỗi 404, không lộ lý do. */
    public Booking get(CurrentUser user, long id) {
        return (user.admin()
                ? bookings.findByIdAndTenantId(id, user.tenantId())
                : bookings.findByIdAndTenantIdAndUserEmail(id, user.tenantId(), user.email()))
                .orElseThrow(BookingNotFoundException::new);
    }

    private RuntimeException translate(DataIntegrityViolationException ex, CurrentUser user, long roomId) {
        if (!(ex.getCause() instanceof ConstraintViolationException violation)) {
            return ex;
        }
        if (SQLSTATE_EXCLUSION_VIOLATION.equals(violation.getSQLState())) {
            return timeConflict(user, roomId);
        }
        if (CONSTRAINT_ROOM_TENANT.equals(violation.getConstraintName())) {
            return new RoomNotFoundException();
        }
        if (CONSTRAINT_USER_TENANT.equals(violation.getConstraintName())
                || CONSTRAINT_TENANT.equals(violation.getConstraintName())) {
            return new UserNotProvisionedException();
        }
        return ex;
    }

    /**
     * EXCLUDE chỉ xét room_id và chạy trước các FK, nên phòng của tenant khác cũng có thể báo đè giờ.
     * Phải kiểm tra lại để trả 404, nếu không 409 sẽ tiết lộ lịch đặt phòng của tenant đó.
     */
    private RuntimeException timeConflict(CurrentUser user, long roomId) {
        return bookings.roomExistsInTenant(roomId, user.tenantId())
                ? new RoomUnavailableException()
                : new RoomNotFoundException();
    }
}

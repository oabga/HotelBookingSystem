package com.hotelbooking.booking.service;

import com.hotelbooking.booking.domain.Booking;
import com.hotelbooking.booking.domain.BookingRepository;
import com.hotelbooking.booking.domain.BookingStatus;
import com.hotelbooking.booking.security.CurrentUser;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class BookingService {

    static final String CONSTRAINT_USER_TENANT = "fk_booking_user_same_tenant";

    /** "Hôm nay" tính theo giờ Việt Nam (docs/api-contract.md §1). */
    private static final ZoneId HOTEL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final BookingRepository bookings;
    private final Clock clock;
    private final Duration paymentHold;

    public BookingService(BookingRepository bookings, Clock clock,
                          @Value("${booking.payment.hold}") Duration paymentHold) {
        this.bookings = bookings;
        this.clock = clock;
        this.paymentHold = paymentHold;
    }

    /**
     * Trừ tồn từng đêm theo ngày tăng dần rồi mới INSERT, tất cả trong một transaction READ COMMITTED
     * (docs/erd-v2.md §5.1). Đọc room_type theo tenant trước, nên loại phòng của tenant khác luôn là 404.
     */
    @Transactional
    public Booking create(CurrentUser user, long roomTypeId, LocalDate checkIn, LocalDate checkOut, int roomCount) {
        if (checkIn.isBefore(LocalDate.ofInstant(clock.instant(), HOTEL_ZONE))) {
            throw new InvalidStayException("checkIn must not be in the past");
        }
        BigDecimal basePrice = bookings.findBasePrice(roomTypeId, user.tenantId())
                .orElseThrow(RoomTypeNotFoundException::new);

        List<LocalDate> nights = checkIn.datesUntil(checkOut).toList();
        for (LocalDate night : nights) {
            if (bookings.reserveNight(user.tenantId(), roomTypeId, night, roomCount) != 1) {
                throw new RoomUnavailableException();
            }
        }

        BigDecimal totalAmount = basePrice
                .multiply(BigDecimal.valueOf(nights.size()))
                .multiply(BigDecimal.valueOf(roomCount));
        Instant createdAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Booking booking = Booking.pendingPayment(user.tenantId(), roomTypeId, user.email(), checkIn, checkOut,
                roomCount, totalAmount, createdAt.plus(paymentHold), createdAt);
        try {
            return bookings.saveAndFlush(booking);
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    /** USER chỉ thấy booking của mình; ADMIN thấy toàn bộ booking trong tenant. */
    @Transactional(readOnly = true)
    public List<Booking> list(CurrentUser user) {
        return user.admin()
                ? bookings.findByTenantIdOrderByIdAsc(user.tenantId())
                : bookings.findByTenantIdAndUserEmailOrderByIdAsc(user.tenantId(), user.email());
    }

    /** Không tồn tại, khác tenant hay khác chủ đều là cùng một lỗi 404, không lộ lý do. */
    @Transactional(readOnly = true)
    public Booking get(CurrentUser user, long id) {
        return (user.admin()
                ? bookings.findByIdAndTenantId(id, user.tenantId())
                : bookings.findByIdAndTenantIdAndUserEmail(id, user.tenantId(), user.email()))
                .orElseThrow(BookingNotFoundException::new);
    }

    /** Chỉ đổi booking, không đổi tồn: phòng đã được trừ lúc đặt (docs/api-contract.md §5.5). */
    @Transactional
    public Booking pay(CurrentUser user, long id) {
        int updated = bookings.confirmIfPayable(id, user.tenantId(), user.email(), clock.instant());
        Booking booking = findOwned(user, id);
        if (updated != 1) {
            throw new BookingStateConflictException();
        }
        return booking;
    }

    /**
     * Đổi trạng thái có điều kiện trước, chỉ khi đổi được đúng một dòng mới trả tồn, nên huỷ lặp hoặc huỷ trùng
     * lúc job hết hạn chạy không trả tồn hai lần. Huỷ lại đơn đã huỷ vẫn là 200 (docs/api-contract.md §5.6).
     */
    @Transactional
    public Booking cancel(CurrentUser user, long id) {
        int updated = bookings.cancelIfPending(id, user.tenantId(), user.email());
        Booking booking = findOwned(user, id);
        if (updated == 1) {
            releaseInventory(booking);
            return booking;
        }
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            return booking;
        }
        throw new BookingStateConflictException();
    }

    /** Mỗi đơn một transaction riêng, nên đơn lỗi không kéo theo các đơn khác trong cùng lượt job. */
    @Transactional
    public boolean expire(long id) {
        if (bookings.expireIfOverdue(id, clock.instant()) != 1) {
            return false;
        }
        releaseInventory(bookings.findById(id).orElseThrow());
        return true;
    }

    private Booking findOwned(CurrentUser user, long id) {
        return bookings.findByIdAndTenantIdAndUserEmail(id, user.tenantId(), user.email())
                .orElseThrow(BookingNotFoundException::new);
    }

    /** Không trả được đêm nào nghĩa là sold đã lệch với booking: rollback để không làm lệch thêm. */
    private void releaseInventory(Booking booking) {
        for (LocalDate night : booking.getCheckIn().datesUntil(booking.getCheckOut()).toList()) {
            int released = bookings.releaseNight(
                    booking.getTenantId(), booking.getRoomTypeId(), night, booking.getRoomCount());
            if (released != 1) {
                throw new IllegalStateException(
                        "Inventory out of sync for booking " + booking.getId() + " on " + night);
            }
        }
    }

    private RuntimeException translate(DataIntegrityViolationException ex) {
        if (ex.getCause() instanceof ConstraintViolationException violation
                && CONSTRAINT_USER_TENANT.equals(violation.getConstraintName())) {
            return new UserNotProvisionedException();
        }
        return ex;
    }
}

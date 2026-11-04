package com.hotelbooking.booking.service;

import com.hotelbooking.booking.domain.Booking;
import com.hotelbooking.booking.domain.BookingRepository;
import com.hotelbooking.booking.domain.BookingRescheduleRepository;
import com.hotelbooking.booking.domain.BookingStatus;
import com.hotelbooking.booking.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Đổi ngày của đơn đã xác nhận. Chỉ service này được đổi sold của việc đổi ngày, cùng transaction với booking.
 * Giá mỗi đêm giữ theo đơn gốc: đổi giá phòng về sau không ảnh hưởng đơn đã tạo, kể cả khi đơn đổi ngày.
 */
@Service
public class BookingRescheduleService {

    private static final ZoneId HOTEL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final long MAX_NIGHTS = 30;

    private final BookingRepository bookings;
    private final BookingRescheduleRepository reschedules;
    private final Clock clock;

    public BookingRescheduleService(BookingRepository bookings, BookingRescheduleRepository reschedules, Clock clock) {
        this.bookings = bookings;
        this.reschedules = reschedules;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<BookingRescheduleRepository.ReschedulableBooking> listReschedulable(CurrentUser user) {
        return reschedules.findReschedulable(user.tenantId(), user.email(), today());
    }

    /** Chỉ đọc, không giữ phòng. Khi duyệt sẽ kiểm tra lại trong transaction. */
    @Transactional(readOnly = true)
    public Quote quote(CurrentUser user, long id, LocalDate checkIn, LocalDate checkOut) {
        validateDates(checkIn, checkOut);
        Booking booking = user.admin()
                ? bookings.findByIdAndTenantId(id, user.tenantId()).orElseThrow(BookingNotFoundException::new)
                : bookings.findByIdAndTenantIdAndUserEmail(id, user.tenantId(), user.email())
                        .orElseThrow(BookingNotFoundException::new);
        requireReschedulable(booking, checkIn, checkOut);
        String roomTypeName = reschedules.findRoomTypeName(booking.getRoomTypeId(), user.tenantId())
                .orElseThrow(IllegalStateException::new);
        return new Quote(booking, roomTypeName, checkIn, checkOut, nights(checkIn, checkOut),
                newTotal(booking, checkIn, checkOut), hasRoom(booking, checkIn, checkOut));
    }

    /**
     * Gọi lại với cùng requestId trả về kết quả đã áp dụng thay vì đổi lần nữa, nên support-service
     * có thể duyệt lại an toàn nếu lần trước đã đổi ngày nhưng chưa kịp ghi nhận phiếu.
     */
    @Transactional
    public Booking reschedule(CurrentUser admin, long id, long requestId, LocalDate checkIn, LocalDate checkOut) {
        validateDates(checkIn, checkOut);
        Booking booking = reschedules.findForUpdate(id, admin.tenantId()).orElseThrow(BookingNotFoundException::new);

        var applied = reschedules.findApplied(admin.tenantId(), requestId);
        if (applied.isPresent()) {
            boolean same = applied.get().getBookingId() == id
                    && applied.get().getCheckIn().equals(checkIn.toString())
                    && applied.get().getCheckOut().equals(checkOut.toString());
            if (!same) {
                throw new BookingStateConflictException();
            }
            return booking;
        }

        requireReschedulable(booking, checkIn, checkOut);
        moveInventory(booking, checkIn, checkOut);

        LocalDate previousCheckIn = booking.getCheckIn();
        LocalDate previousCheckOut = booking.getCheckOut();
        BigDecimal previousTotal = booking.getTotalAmount();
        BigDecimal total = newTotal(booking, checkIn, checkOut);
        if (reschedules.applyDates(id, admin.tenantId(), checkIn, checkOut, total) != 1) {
            throw new BookingStateConflictException();
        }
        reschedules.recordApplied(admin.tenantId(), requestId, id, previousCheckIn, previousCheckOut,
                checkIn, checkOut, previousTotal, total);
        return bookings.findByIdAndTenantId(id, admin.tenantId()).orElseThrow(BookingNotFoundException::new);
    }

    /**
     * Theo ngày tăng dần như lúc đặt phòng, để các request tranh cùng đêm không deadlock.
     * Đêm có ở cả kỳ cũ và mới không đổi: đơn đang giữ sẵn nên không được đòi thêm phòng cho đêm đó.
     */
    private void moveInventory(Booking booking, LocalDate checkIn, LocalDate checkOut) {
        Set<LocalDate> previous = booking.getCheckIn().datesUntil(booking.getCheckOut()).collect(Collectors.toSet());
        Set<LocalDate> requested = checkIn.datesUntil(checkOut).collect(Collectors.toSet());
        Set<LocalDate> allNights = new TreeSet<>(previous);
        allNights.addAll(requested);

        long tenantId = booking.getTenantId();
        long roomTypeId = booking.getRoomTypeId();
        int roomCount = booking.getRoomCount();
        for (LocalDate night : allNights) {
            boolean wasHeld = previous.contains(night);
            boolean isNeeded = requested.contains(night);
            if (isNeeded && !wasHeld) {
                if (bookings.reserveNight(tenantId, roomTypeId, night, roomCount) != 1) {
                    throw new RoomUnavailableException();
                }
            } else if (wasHeld && !isNeeded) {
                if (bookings.releaseNight(tenantId, roomTypeId, night, roomCount) != 1) {
                    throw new IllegalStateException(
                            "Inventory out of sync for booking " + booking.getId() + " on " + night);
                }
            }
        }
    }

    private boolean hasRoom(Booking booking, LocalDate checkIn, LocalDate checkOut) {
        Map<String, Integer> remaining = reschedules
                .findRemaining(booking.getTenantId(), booking.getRoomTypeId(), checkIn, checkOut).stream()
                .collect(Collectors.toMap(BookingRescheduleRepository.NightRemaining::getStayDate,
                        BookingRescheduleRepository.NightRemaining::getRemaining));
        return checkIn.datesUntil(checkOut)
                .filter(night -> night.isBefore(booking.getCheckIn()) || !night.isBefore(booking.getCheckOut()))
                .allMatch(night -> remaining.getOrDefault(night.toString(), 0) >= booking.getRoomCount());
    }

    private void requireReschedulable(Booking booking, LocalDate checkIn, LocalDate checkOut) {
        if (booking.getStatus() != BookingStatus.CONFIRMED || !booking.getCheckIn().isAfter(today())) {
            throw new BookingStateConflictException();
        }
        if (booking.getCheckIn().equals(checkIn) && booking.getCheckOut().equals(checkOut)) {
            throw new InvalidStayException("New dates must differ from the current dates");
        }
    }

    private void validateDates(LocalDate checkIn, LocalDate checkOut) {
        if (!checkOut.isAfter(checkIn)) {
            throw new InvalidStayException("checkOut must be after checkIn");
        }
        if (nights(checkIn, checkOut) > MAX_NIGHTS) {
            throw new InvalidStayException("stay must not exceed 30 nights");
        }
        if (checkIn.isBefore(today())) {
            throw new InvalidStayException("checkIn must not be in the past");
        }
    }

    private static BigDecimal newTotal(Booking booking, LocalDate checkIn, LocalDate checkOut) {
        return booking.getTotalAmount()
                .multiply(BigDecimal.valueOf(nights(checkIn, checkOut)))
                .divide(BigDecimal.valueOf(nights(booking.getCheckIn(), booking.getCheckOut())), 2, RoundingMode.HALF_UP);
    }

    private static long nights(LocalDate checkIn, LocalDate checkOut) {
        return ChronoUnit.DAYS.between(checkIn, checkOut);
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), HOTEL_ZONE);
    }

    public record Quote(Booking booking, String roomTypeName, LocalDate checkIn, LocalDate checkOut, long nights,
                        BigDecimal newTotalAmount, boolean available) {
    }
}

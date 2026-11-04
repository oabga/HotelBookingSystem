package com.hotelbooking.booking.api;

import com.hotelbooking.booking.domain.Booking;
import com.hotelbooking.booking.security.CurrentUser;
import com.hotelbooking.booking.service.BookingRescheduleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Dùng cho luồng xin đổi ngày ở Support Center. Quyền theo role nằm ở SecurityConfig. */
@RestController
@RequestMapping("/bookings")
public class BookingRescheduleController {

    private final BookingRescheduleService service;

    public BookingRescheduleController(BookingRescheduleService service) {
        this.service = service;
    }

    @GetMapping("/reschedulable")
    public List<ReschedulableResponse> reschedulable(@AuthenticationPrincipal Jwt jwt) {
        return service.listReschedulable(CurrentUser.from(jwt)).stream()
                .map(item -> new ReschedulableResponse(item.getId(), item.getRoomTypeId(), item.getRoomTypeName(),
                        item.getHotelName(), LocalDate.parse(item.getCheckIn()), LocalDate.parse(item.getCheckOut()),
                        item.getRoomCount(), item.getTotalAmount()))
                .toList();
    }

    @GetMapping("/{id}/reschedule-quote")
    public QuoteResponse quote(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive Long id,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut) {
        BookingRescheduleService.Quote quote = service.quote(CurrentUser.from(jwt), id, checkIn, checkOut);
        Booking booking = quote.booking();
        return new QuoteResponse(booking.getId(), booking.getRoomTypeId(), quote.roomTypeName(), booking.getCheckIn(),
                booking.getCheckOut(), quote.checkIn(), quote.checkOut(), quote.nights(), booking.getRoomCount(),
                booking.getTotalAmount(), quote.newTotalAmount(), quote.available());
    }

    @PostMapping("/{id}/reschedule")
    public BookingResponse reschedule(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive Long id,
                                      @Valid @RequestBody RescheduleRequest request) {
        return BookingResponse.from(service.reschedule(CurrentUser.from(jwt), id, request.requestId(),
                request.checkIn(), request.checkOut()));
    }

    public record ReschedulableResponse(Long id, Long roomTypeId, String roomTypeName, String hotelName,
                                        LocalDate checkIn, LocalDate checkOut, Integer roomCount,
                                        BigDecimal totalAmount) {
    }

    public record QuoteResponse(Long bookingId, Long roomTypeId, String roomTypeName, LocalDate currentCheckIn,
                                LocalDate currentCheckOut, LocalDate checkIn, LocalDate checkOut, long nights,
                                Integer roomCount, BigDecimal currentTotalAmount, BigDecimal newTotalAmount,
                                boolean available) {
    }
}

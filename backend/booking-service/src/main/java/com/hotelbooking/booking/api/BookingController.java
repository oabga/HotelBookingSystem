package com.hotelbooking.booking.api;

import com.hotelbooking.booking.security.CurrentUser;
import com.hotelbooking.booking.service.BookingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse create(@AuthenticationPrincipal Jwt jwt,
                                  @Valid @RequestBody CreateBookingRequest request) {
        return BookingResponse.from(
                bookingService.create(CurrentUser.from(jwt), request.roomId(), request.checkIn(), request.checkOut()));
    }

    @GetMapping
    public List<BookingResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return bookingService.list(CurrentUser.from(jwt)).stream()
                .map(BookingResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public BookingResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive Long id) {
        return BookingResponse.from(bookingService.get(CurrentUser.from(jwt), id));
    }
}

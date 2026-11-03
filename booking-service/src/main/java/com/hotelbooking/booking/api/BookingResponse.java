package com.hotelbooking.booking.api;

import com.hotelbooking.booking.domain.Booking;
import com.hotelbooking.booking.domain.BookingStatus;

import java.time.Instant;

public record BookingResponse(
        Long id,
        Long tenantId,
        Long roomId,
        String userEmail,
        Instant checkIn,
        Instant checkOut,
        BookingStatus status,
        Instant createdAt) {

    static BookingResponse from(Booking booking) {
        return new BookingResponse(
                booking.getId(),
                booking.getTenantId(),
                booking.getRoomId(),
                booking.getUserEmail(),
                booking.getCheckIn(),
                booking.getCheckOut(),
                booking.getStatus(),
                booking.getCreatedAt());
    }
}

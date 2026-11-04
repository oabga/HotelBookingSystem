package com.hotelbooking.booking.api;

import com.hotelbooking.booking.domain.Booking;
import com.hotelbooking.booking.domain.BookingStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record BookingResponse(
        Long id,
        Long tenantId,
        Long roomTypeId,
        String userEmail,
        LocalDate checkIn,
        LocalDate checkOut,
        Integer roomCount,
        BigDecimal totalAmount,
        BookingStatus status,
        Instant paymentExpiresAt,
        Instant createdAt) {

    static BookingResponse from(Booking booking) {
        return new BookingResponse(
                booking.getId(),
                booking.getTenantId(),
                booking.getRoomTypeId(),
                booking.getUserEmail(),
                booking.getCheckIn(),
                booking.getCheckOut(),
                booking.getRoomCount(),
                booking.getTotalAmount(),
                booking.getStatus(),
                booking.getPaymentExpiresAt(),
                booking.getCreatedAt());
    }
}

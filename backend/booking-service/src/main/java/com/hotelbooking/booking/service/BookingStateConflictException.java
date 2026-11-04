package com.hotelbooking.booking.service;

public class BookingStateConflictException extends RuntimeException {

    public BookingStateConflictException() {
        super("Booking status does not allow this action");
    }
}

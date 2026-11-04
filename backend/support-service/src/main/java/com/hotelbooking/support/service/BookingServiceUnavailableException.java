package com.hotelbooking.support.service;

public class BookingServiceUnavailableException extends RuntimeException {
    public BookingServiceUnavailableException() {
        super("Booking service is unavailable");
    }
}

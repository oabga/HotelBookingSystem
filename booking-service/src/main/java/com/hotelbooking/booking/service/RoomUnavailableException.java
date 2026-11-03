package com.hotelbooking.booking.service;

public class RoomUnavailableException extends RuntimeException {

    public RoomUnavailableException() {
        super("Room is not available for the requested time");
    }
}

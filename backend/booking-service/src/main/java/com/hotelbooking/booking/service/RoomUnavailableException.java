package com.hotelbooking.booking.service;

public class RoomUnavailableException extends RuntimeException {

    public RoomUnavailableException() {
        super("Not enough rooms for the requested stay");
    }
}

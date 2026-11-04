package com.hotelbooking.booking.service;

public class RoomTypeNotFoundException extends RuntimeException {

    public RoomTypeNotFoundException() {
        super("Room type not found");
    }
}

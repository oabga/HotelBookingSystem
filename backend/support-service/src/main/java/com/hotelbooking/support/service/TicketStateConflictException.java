package com.hotelbooking.support.service;

public class TicketStateConflictException extends RuntimeException {
    public TicketStateConflictException(String message) {
        super(message);
    }
}

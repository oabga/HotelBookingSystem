package com.hotelbooking.support.service;

public class InvalidDateChangeException extends RuntimeException {
    public InvalidDateChangeException(String message) {
        super(message);
    }
}

package com.hotelbooking.support.service;

public class RequestNotFoundException extends RuntimeException {
    public RequestNotFoundException() {
        super("Request not found");
    }
}

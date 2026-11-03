package com.hotelbooking.booking.service;

public class UserNotProvisionedException extends RuntimeException {

    public UserNotProvisionedException() {
        super("User is not provisioned in this tenant");
    }
}

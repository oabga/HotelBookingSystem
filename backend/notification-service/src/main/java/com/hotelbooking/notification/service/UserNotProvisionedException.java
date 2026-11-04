package com.hotelbooking.notification.service;

public class UserNotProvisionedException extends RuntimeException {
    public UserNotProvisionedException() {
        super("User is not provisioned");
    }
}

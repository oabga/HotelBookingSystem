package com.hotelbooking.support.service;

public class UserNotProvisionedException extends RuntimeException {
    public UserNotProvisionedException() {
        super("User is not provisioned");
    }
}

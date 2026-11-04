package com.hotelbooking.booking.service;

/** Quy tắc cần biết "hôm nay" nên kiểm tra ở service, các quy tắc còn lại nằm trong CreateBookingRequest. */
public class InvalidStayException extends RuntimeException {

    public InvalidStayException(String message) {
        super(message);
    }
}

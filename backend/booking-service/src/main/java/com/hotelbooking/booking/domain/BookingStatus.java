package com.hotelbooking.booking.domain;

/** PENDING_PAYMENT và CONFIRMED đang giữ tồn; CANCELLED và EXPIRED đã trả tồn. */
public enum BookingStatus {
    PENDING_PAYMENT,
    CONFIRMED,
    CANCELLED,
    EXPIRED
}

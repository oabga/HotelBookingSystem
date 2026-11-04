package com.hotelbooking.catalog.api;

public record HotelResponse(long id, long tenantId, String name, String address) {
}

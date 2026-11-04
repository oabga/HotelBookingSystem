package com.hotelbooking.catalog.api;

import java.math.BigDecimal;

public record RoomTypeResponse(
        long id, long tenantId, long hotelId, String name,
        int capacity, BigDecimal basePrice, int totalRooms) {
}

package com.hotelbooking.catalog.api;

import java.math.BigDecimal;

public record AvailabilityResponse(
        long roomTypeId, String name, int capacity, BigDecimal pricePerNight,
        long nights, BigDecimal totalPricePerRoom, int availableRooms) {
}

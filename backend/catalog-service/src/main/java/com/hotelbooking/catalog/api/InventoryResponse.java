package com.hotelbooking.catalog.api;

import java.time.LocalDate;

public record InventoryResponse(
        long roomTypeId, String name, LocalDate stayDate, int total, int sold, int available) {
}

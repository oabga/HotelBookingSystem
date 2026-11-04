package com.hotelbooking.catalog.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.math.BigDecimal;

public record CreateRoomTypeRequest(
        String name,
        @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer capacity,
        @JsonDeserialize(using = StrictBigDecimalDeserializer.class) BigDecimal basePrice,
        @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer totalRooms) {
}

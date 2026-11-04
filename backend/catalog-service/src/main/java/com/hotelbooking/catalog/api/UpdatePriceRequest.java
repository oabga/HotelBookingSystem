package com.hotelbooking.catalog.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.math.BigDecimal;

public record UpdatePriceRequest(
        @JsonDeserialize(using = StrictBigDecimalDeserializer.class) BigDecimal basePrice) {
}

package com.hotelbooking.booking.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

/** requestId là ID phiếu của support-service, dùng để gọi lại không đổi ngày hai lần. */
public record RescheduleRequest(
        @NotNull @Positive @JsonDeserialize(using = StrictLongDeserializer.class) Long requestId,
        @NotNull @JsonDeserialize(using = StayDateDeserializer.class) LocalDate checkIn,
        @NotNull @JsonDeserialize(using = StayDateDeserializer.class) LocalDate checkOut) {
}

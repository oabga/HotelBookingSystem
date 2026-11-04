package com.hotelbooking.support.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

/** Trường lạ như tenantId, status hay newTotal bị bỏ qua: các giá trị đó chỉ lấy từ JWT, Booking và database. */
public record CreateDateChangeRequest(
        @NotNull @Positive @JsonDeserialize(using = StrictLongDeserializer.class) Long bookingId,
        @NotNull @JsonDeserialize(using = StayDateDeserializer.class) LocalDate checkIn,
        @NotNull @JsonDeserialize(using = StayDateDeserializer.class) LocalDate checkOut,
        @JsonDeserialize(using = StrictStringDeserializer.class) String description) {

    @AssertTrue(message = "description must be 1-4000 characters of plain text")
    public boolean isDescriptionValid() {
        return SupportText.isValid(description, SupportText.MAX_BODY);
    }
}

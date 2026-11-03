package com.hotelbooking.booking.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/** Field lạ như tenantId hay status bị bỏ qua, không ảnh hưởng dữ liệu được lưu. */
public record CreateBookingRequest(
        @NotNull @Positive @JsonDeserialize(using = StrictLongDeserializer.class) Long roomId,
        @NotNull @JsonDeserialize(using = BookingTimeDeserializer.class) Instant checkIn,
        @NotNull @JsonDeserialize(using = BookingTimeDeserializer.class) Instant checkOut) {

    /** Trường hợp thiếu giá trị đã được @NotNull báo lỗi. */
    @AssertTrue(message = "checkOut must be after checkIn")
    public boolean isCheckOutAfterCheckIn() {
        return checkIn == null || checkOut == null || checkOut.isAfter(checkIn);
    }
}

package com.hotelbooking.booking.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Field lạ như tenantId, status hay totalAmount bị bỏ qua, không ảnh hưởng dữ liệu được lưu. */
public record CreateBookingRequest(
        @NotNull @Positive @JsonDeserialize(using = StrictLongDeserializer.class) Long roomTypeId,
        @NotNull @JsonDeserialize(using = StayDateDeserializer.class) LocalDate checkIn,
        @NotNull @JsonDeserialize(using = StayDateDeserializer.class) LocalDate checkOut,
        @NotNull @Min(1) @Max(10) @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer roomCount) {

    private static final long MAX_NIGHTS = 30;

    /** Trường hợp thiếu giá trị đã được @NotNull báo lỗi. */
    @AssertTrue(message = "checkOut must be after checkIn")
    public boolean isCheckOutAfterCheckIn() {
        return checkIn == null || checkOut == null || checkOut.isAfter(checkIn);
    }

    @AssertTrue(message = "stay must not exceed 30 nights")
    public boolean isWithinMaxStay() {
        return checkIn == null || checkOut == null || ChronoUnit.DAYS.between(checkIn, checkOut) <= MAX_NIGHTS;
    }
}

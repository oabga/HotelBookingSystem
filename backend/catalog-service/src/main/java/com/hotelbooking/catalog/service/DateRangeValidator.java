package com.hotelbooking.catalog.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

@Component
public class DateRangeValidator {
    private final Clock clock;

    public DateRangeValidator(Clock clock) {
        this.clock = clock;
    }

    public DateRange availability(String checkInText, String checkOutText) {
        DateRange range = parse(checkInText, checkOutText);
        if (range.checkIn().isBefore(LocalDate.now(clock))) {
            throw new IllegalArgumentException("checkIn must not be in the past");
        }
        return range;
    }

    public DateRange inventory(String checkInText, String checkOutText) {
        return parse(checkInText, checkOutText);
    }

    private static DateRange parse(String checkInText, String checkOutText) {
        final LocalDate checkIn;
        final LocalDate checkOut;
        try {
            checkIn = LocalDate.parse(checkInText);
            checkOut = LocalDate.parse(checkOutText);
        } catch (DateTimeParseException | NullPointerException ex) {
            throw new IllegalArgumentException("Dates must use YYYY-MM-DD", ex);
        }
        if (!checkOut.isAfter(checkIn)) throw new IllegalArgumentException("checkOut must be after checkIn");
        long nights = java.time.temporal.ChronoUnit.DAYS.between(checkIn, checkOut);
        if (nights > 30) throw new IllegalArgumentException("Date range must not exceed 30 nights");
        return new DateRange(checkIn, checkOut, nights);
    }

    public record DateRange(LocalDate checkIn, LocalDate checkOut, long nights) {
    }
}

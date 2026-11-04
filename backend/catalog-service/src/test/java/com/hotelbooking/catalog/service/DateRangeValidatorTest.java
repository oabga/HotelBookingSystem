package com.hotelbooking.catalog.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DateRangeValidatorTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"),
            ZoneId.of("Asia/Ho_Chi_Minh"));
    private final DateRangeValidator validator = new DateRangeValidator(clock);

    @Test
    void acceptsTodayAndCountsNightsExcludingCheckout() {
        var range = validator.availability("2026-11-03", "2026-11-05");
        assertEquals(2, range.nights());
    }

    @Test
    void rejectsPastDatesAndMoreThanThirtyNightsForSearch() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.availability("2026-11-02", "2026-11-03"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.availability("2026-11-03", "2026-12-04"));
    }

    @Test
    void inventoryAllowsPastButStillLimitsThirtyDays() {
        assertEquals(2, validator.inventory("2026-11-01", "2026-11-03").nights());
        assertThrows(IllegalArgumentException.class,
                () -> validator.inventory("2026-11-01", "2026-12-02"));
    }

    @Test
    void rejectsNonIsoAndImpossibleDates() {
        assertThrows(IllegalArgumentException.class,
                () -> validator.inventory("2026-02-30", "2026-03-01"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.inventory("2026-11-01T10:00:00", "2026-11-02"));
    }
}

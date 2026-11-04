package com.hotelbooking.booking.service;

import com.hotelbooking.booking.domain.BookingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingExpiryJobTest {

    private static final Instant NOW = Instant.parse("2026-11-03T02:15:30Z");

    @Mock
    private BookingRepository repository;

    @Mock
    private BookingService service;

    @Spy
    private Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @InjectMocks
    private BookingExpiryJob job;

    @Test
    void expiresEveryOverdueBooking() {
        when(repository.findOverduePendingIds(NOW)).thenReturn(List.of(1L, 2L));

        job.expireOverdue();

        verify(service).expire(1L);
        verify(service).expire(2L);
    }

    @Test
    void nothingOverdue_doesNothing() {
        when(repository.findOverduePendingIds(NOW)).thenReturn(List.of());

        job.expireOverdue();

        verify(service, never()).expire(anyLong());
    }

    @Test
    void failureOnOneBooking_doesNotBlockTheOthers() {
        when(repository.findOverduePendingIds(NOW)).thenReturn(List.of(1L, 2L));
        when(service.expire(1L)).thenThrow(new IllegalStateException("out of sync"));

        job.expireOverdue();

        verify(service).expire(2L);
    }
}

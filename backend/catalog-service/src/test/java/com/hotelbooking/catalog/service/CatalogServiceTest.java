package com.hotelbooking.catalog.service;

import com.hotelbooking.catalog.api.CreateRoomTypeRequest;
import com.hotelbooking.catalog.api.RoomTypeResponse;
import com.hotelbooking.catalog.repository.CatalogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {
    @Mock
    CatalogRepository repository;

    @Mock
    DateRangeValidator dateRangeValidator;

    @Test
    void createsRoomTypeAndInventoryUsingJwtTenantAndVietnamToday() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-03T18:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
        CatalogService service = new CatalogService(repository, dateRangeValidator, clock);
        var request = new CreateRoomTypeRequest("  Suite  ", 4, new BigDecimal("2500000.00"), 2);
        var roomType = new RoomTypeResponse(77, 8, 3, "Suite", 4,
                new BigDecimal("2500000.00"), 2);
        when(repository.hotelExists(8, 3)).thenReturn(true);
        when(repository.insertRoomType(8, 3, "Suite", 4, new BigDecimal("2500000.00"), 2)).thenReturn(77L);
        when(repository.findRoomType(8, 77)).thenReturn(roomType);

        assertThat(service.createRoomType(8, 3, request)).isEqualTo(roomType);
        verify(repository).createInventory(8, 77, 2, java.time.LocalDate.of(2026, 11, 4));
    }

    @Test
    void rejectsInvalidPriceBeforeWriting() {
        CatalogService service = new CatalogService(repository, dateRangeValidator, Clock.systemUTC());
        var request = new CreateRoomTypeRequest("Suite", 2, new BigDecimal("10.123"), 1);

        assertThatThrownBy(() -> service.createRoomType(1, 1, request))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).insertRoomType(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void tenantScopedMissingHotelIsNotFound() {
        CatalogService service = new CatalogService(repository, dateRangeValidator, Clock.systemUTC());
        when(repository.hotelExists(2, 1)).thenReturn(false);
        var request = new CreateRoomTypeRequest("Suite", 2, new BigDecimal("100.00"), 1);

        assertThatThrownBy(() -> service.createRoomType(2, 1, request))
                .isInstanceOf(CatalogNotFoundException.class);
        verify(repository, never()).insertRoomType(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt());
    }
}

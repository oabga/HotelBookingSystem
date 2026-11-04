package com.hotelbooking.catalog.service;

import com.hotelbooking.catalog.api.AvailabilityResponse;
import com.hotelbooking.catalog.api.CreateRoomTypeRequest;
import com.hotelbooking.catalog.api.HotelResponse;
import com.hotelbooking.catalog.api.InventoryResponse;
import com.hotelbooking.catalog.api.RoomTypeResponse;
import com.hotelbooking.catalog.repository.CatalogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@Service
public class CatalogService {
    private static final BigDecimal MAX_PRICE = new BigDecimal("99999999.99");
    private final CatalogRepository repository;
    private final DateRangeValidator dateRangeValidator;
    private final Clock clock;

    public CatalogService(CatalogRepository repository, DateRangeValidator dateRangeValidator, Clock clock) {
        this.repository = repository;
        this.dateRangeValidator = dateRangeValidator;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<HotelResponse> hotels(long tenantId) {
        return repository.findHotels(tenantId);
    }

    @Transactional(readOnly = true)
    public List<AvailabilityResponse> availability(long tenantId, long hotelId,
                                                   String checkInText, String checkOutText) {
        DateRangeValidator.DateRange range = dateRangeValidator.availability(checkInText, checkOutText);
        requirePositiveId(hotelId, "hotelId");
        requireHotel(tenantId, hotelId);
        return repository.findAvailability(tenantId, hotelId, range.checkIn(), range.checkOut(), range.nights())
                .stream()
                .map(row -> new AvailabilityResponse(row.id(), row.name(), row.capacity(), row.price(),
                        range.nights(), row.price().multiply(BigDecimal.valueOf(range.nights())),
                        row.availableRooms()))
                .toList();
    }

    @Transactional
    public RoomTypeResponse createRoomType(long tenantId, long hotelId, CreateRoomTypeRequest request) {
        requirePositiveId(hotelId, "hotelId");
        ValidRoomType valid = validateRoomType(request);
        requireHotel(tenantId, hotelId);
        long roomTypeId = repository.insertRoomType(tenantId, hotelId, valid.name(), valid.capacity(),
                valid.basePrice(), valid.totalRooms());
        repository.createInventory(tenantId, roomTypeId, valid.totalRooms(), LocalDate.now(clock));
        RoomTypeResponse created = repository.findRoomType(tenantId, roomTypeId);
        if (created == null) throw new IllegalStateException("Inserted room type could not be read");
        return created;
    }

    @Transactional
    public RoomTypeResponse updatePrice(long tenantId, long roomTypeId, BigDecimal price) {
        requirePositiveId(roomTypeId, "roomTypeId");
        validatePrice(price);
        if (!repository.updatePrice(tenantId, roomTypeId, price)) throw new CatalogNotFoundException();
        return repository.findRoomType(tenantId, roomTypeId);
    }

    @Transactional(readOnly = true)
    public List<InventoryResponse> inventory(long tenantId, long hotelId,
                                             String checkInText, String checkOutText) {
        DateRangeValidator.DateRange range = dateRangeValidator.inventory(checkInText, checkOutText);
        requirePositiveId(hotelId, "hotelId");
        requireHotel(tenantId, hotelId);
        return repository.findInventory(tenantId, hotelId, range.checkIn(), range.checkOut());
    }

    private void requireHotel(long tenantId, long hotelId) {
        if (!repository.hotelExists(tenantId, hotelId)) throw new CatalogNotFoundException();
    }

    private static ValidRoomType validateRoomType(CreateRoomTypeRequest request) {
        if (request == null) throw new IllegalArgumentException("Request body is required");
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty() || name.length() > 100) throw new IllegalArgumentException("name must be 1 to 100 characters");
        Integer capacity = request.capacity();
        if (capacity == null || capacity < 1 || capacity > 20) throw new IllegalArgumentException("capacity must be 1 to 20");
        Integer totalRooms = request.totalRooms();
        if (totalRooms == null || totalRooms < 1 || totalRooms > 1000) {
            throw new IllegalArgumentException("totalRooms must be 1 to 1000");
        }
        validatePrice(request.basePrice());
        return new ValidRoomType(name, capacity, request.basePrice(), totalRooms);
    }

    private static void validatePrice(BigDecimal price) {
        if (price == null || price.signum() <= 0 || price.compareTo(MAX_PRICE) > 0
                || price.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("basePrice must be greater than 0, at most 99999999.99, and have at most 2 decimals");
        }
    }

    private static void requirePositiveId(long id, String field) {
        if (id <= 0) throw new IllegalArgumentException(field + " must be a positive integer");
    }

    private record ValidRoomType(String name, int capacity, BigDecimal basePrice, int totalRooms) {
    }
}

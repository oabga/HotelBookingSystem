package com.hotelbooking.catalog.api;

import com.hotelbooking.catalog.security.CurrentUser;
import com.hotelbooking.catalog.service.CatalogService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping
public class CatalogController {
    private final CatalogService service;

    public CatalogController(CatalogService service) {
        this.service = service;
    }

    @GetMapping("/hotels")
    public List<HotelResponse> hotels(@AuthenticationPrincipal Jwt jwt) {
        return service.hotels(CurrentUser.from(jwt).tenantId());
    }

    @GetMapping("/hotels/{hotelId}/availability")
    public List<AvailabilityResponse> availability(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long hotelId,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {
        return service.availability(CurrentUser.from(jwt).tenantId(), hotelId, checkIn, checkOut);
    }

    @PostMapping("/hotels/{hotelId}/room-types")
    public ResponseEntity<RoomTypeResponse> createRoomType(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long hotelId,
            @RequestBody CreateRoomTypeRequest request) {
        RoomTypeResponse created = service.createRoomType(CurrentUser.from(jwt).tenantId(), hotelId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/room-types/{roomTypeId}/price")
    public RoomTypeResponse updatePrice(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long roomTypeId,
            @RequestBody UpdatePriceRequest request) {
        return service.updatePrice(CurrentUser.from(jwt).tenantId(), roomTypeId,
                request == null ? null : request.basePrice());
    }

    @GetMapping("/admin/hotels/{hotelId}/inventory")
    public List<InventoryResponse> inventory(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable long hotelId,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {
        return service.inventory(CurrentUser.from(jwt).tenantId(), hotelId, checkIn, checkOut);
    }
}

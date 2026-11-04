package com.hotelbooking.catalog.repository;

import com.hotelbooking.catalog.api.AvailabilityResponse;
import com.hotelbooking.catalog.api.HotelResponse;
import com.hotelbooking.catalog.api.InventoryResponse;
import com.hotelbooking.catalog.api.RoomTypeResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public class CatalogRepository {
    private final JdbcClient jdbc;

    public CatalogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<HotelResponse> findHotels(long tenantId) {
        return jdbc.sql("""
                SELECT id, tenant_id, name, address
                FROM hotel
                WHERE tenant_id = :tenantId
                ORDER BY id
                """)
                .param("tenantId", tenantId)
                .query((rs, row) -> new HotelResponse(
                        rs.getLong("id"), rs.getLong("tenant_id"), rs.getString("name"), rs.getString("address")))
                .list();
    }

    public boolean hotelExists(long tenantId, long hotelId) {
        return jdbc.sql("SELECT 1 FROM hotel WHERE tenant_id = :tenantId AND id = :hotelId")
                .param("tenantId", tenantId).param("hotelId", hotelId)
                .query(Integer.class).optional().isPresent();
    }

    public List<AvailabilityRow> findAvailability(
            long tenantId, long hotelId, LocalDate checkIn, LocalDate checkOut, long nights) {
        return jdbc.sql("""
                SELECT rt.id, rt.name, rt.capacity, rt.base_price,
                       MIN(i.total - i.sold) AS available_rooms
                FROM room_type rt
                JOIN room_inventory i
                  ON i.tenant_id = rt.tenant_id AND i.room_type_id = rt.id
                WHERE rt.tenant_id = :tenantId AND rt.hotel_id = :hotelId
                  AND i.stay_date >= :checkIn AND i.stay_date < :checkOut
                GROUP BY rt.id, rt.name, rt.capacity, rt.base_price
                HAVING COUNT(*) = :nights AND MIN(i.total - i.sold) > 0
                ORDER BY rt.id
                """)
                .param("tenantId", tenantId).param("hotelId", hotelId)
                .param("checkIn", checkIn).param("checkOut", checkOut).param("nights", nights)
                .query((rs, row) -> new AvailabilityRow(
                        rs.getLong("id"), rs.getString("name"), rs.getInt("capacity"),
                        rs.getBigDecimal("base_price"), rs.getInt("available_rooms")))
                .list();
    }

    public long insertRoomType(long tenantId, long hotelId, String name, int capacity,
                               BigDecimal basePrice, int totalRooms) {
        return jdbc.sql("""
                INSERT INTO room_type (tenant_id, hotel_id, name, capacity, base_price, total_rooms)
                VALUES (:tenantId, :hotelId, :name, :capacity, :basePrice, :totalRooms)
                RETURNING id
                """)
                .param("tenantId", tenantId).param("hotelId", hotelId).param("name", name)
                .param("capacity", capacity).param("basePrice", basePrice).param("totalRooms", totalRooms)
                .query(Long.class).single();
    }

    public void createInventory(long tenantId, long roomTypeId, int totalRooms, LocalDate firstDate) {
        jdbc.sql("""
                INSERT INTO room_inventory (tenant_id, room_type_id, stay_date, total, sold)
                SELECT :tenantId, :roomTypeId, d::date, :totalRooms, 0
                FROM generate_series(CAST(:firstDate AS date), DATE '2027-01-31', INTERVAL '1 day') AS d
                """)
                .param("tenantId", tenantId).param("roomTypeId", roomTypeId)
                .param("totalRooms", totalRooms).param("firstDate", firstDate)
                .update();
    }

    public RoomTypeResponse findRoomType(long tenantId, long roomTypeId) {
        return jdbc.sql("""
                SELECT id, tenant_id, hotel_id, name, capacity, base_price, total_rooms
                FROM room_type WHERE tenant_id = :tenantId AND id = :roomTypeId
                """)
                .param("tenantId", tenantId).param("roomTypeId", roomTypeId)
                .query((rs, row) -> new RoomTypeResponse(
                        rs.getLong("id"), rs.getLong("tenant_id"), rs.getLong("hotel_id"),
                        rs.getString("name"), rs.getInt("capacity"), rs.getBigDecimal("base_price"),
                        rs.getInt("total_rooms")))
                .optional().orElse(null);
    }

    public boolean updatePrice(long tenantId, long roomTypeId, BigDecimal price) {
        return jdbc.sql("UPDATE room_type SET base_price = :price WHERE tenant_id = :tenantId AND id = :id")
                .param("price", price).param("tenantId", tenantId).param("id", roomTypeId)
                .update() == 1;
    }

    public List<InventoryResponse> findInventory(long tenantId, long hotelId,
                                                  LocalDate checkIn, LocalDate checkOut) {
        return jdbc.sql("""
                SELECT rt.id AS room_type_id, rt.name, i.stay_date, i.total, i.sold,
                       (i.total - i.sold) AS available
                FROM room_type rt
                JOIN room_inventory i
                  ON i.tenant_id = rt.tenant_id AND i.room_type_id = rt.id
                WHERE rt.tenant_id = :tenantId AND rt.hotel_id = :hotelId
                  AND i.stay_date >= :checkIn AND i.stay_date < :checkOut
                ORDER BY rt.id, i.stay_date
                """)
                .param("tenantId", tenantId).param("hotelId", hotelId)
                .param("checkIn", checkIn).param("checkOut", checkOut)
                .query((rs, row) -> new InventoryResponse(
                        rs.getLong("room_type_id"), rs.getString("name"),
                        rs.getObject("stay_date", LocalDate.class), rs.getInt("total"),
                        rs.getInt("sold"), rs.getInt("available")))
                .list();
    }

    public record AvailabilityRow(long id, String name, int capacity, BigDecimal price, int availableRooms) {
    }
}

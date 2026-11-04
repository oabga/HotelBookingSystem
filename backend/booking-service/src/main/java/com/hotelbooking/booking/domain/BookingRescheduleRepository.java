package com.hotelbooking.booking.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Truy vấn riêng cho việc đổi ngày. Mọi truy vấn đều có tenantId nên không đọc hoặc ghi xuyên tenant. */
public interface BookingRescheduleRepository extends Repository<Booking, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.id = :id AND b.tenantId = :tenantId")
    Optional<Booking> findForUpdate(@Param("id") long id, @Param("tenantId") long tenantId);

    @Query(value = "SELECT name FROM room_type WHERE id = :roomTypeId AND tenant_id = :tenantId", nativeQuery = true)
    Optional<String> findRoomTypeName(@Param("roomTypeId") long roomTypeId, @Param("tenantId") long tenantId);

    /** Đêm chưa mở bán không có dòng, nên bên gọi phải coi đêm thiếu là không còn phòng. */
    @Query(value = """
            SELECT CAST(stay_date AS VARCHAR) AS stayDate, total - sold AS remaining
            FROM room_inventory
            WHERE tenant_id = :tenantId AND room_type_id = :roomTypeId
              AND stay_date >= :fromDate AND stay_date < :toDate
            """, nativeQuery = true)
    List<NightRemaining> findRemaining(@Param("tenantId") long tenantId, @Param("roomTypeId") long roomTypeId,
                                       @Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate);

    @Query(value = """
            SELECT b.id AS id, b.room_type_id AS roomTypeId, rt.name AS roomTypeName, h.name AS hotelName,
                   CAST(b.check_in AS VARCHAR) AS checkIn, CAST(b.check_out AS VARCHAR) AS checkOut,
                   b.room_count AS roomCount, b.total_amount AS totalAmount
            FROM booking b
            JOIN room_type rt ON rt.tenant_id = b.tenant_id AND rt.id = b.room_type_id
            JOIN hotel h ON h.tenant_id = rt.tenant_id AND h.id = rt.hotel_id
            WHERE b.tenant_id = :tenantId AND b.user_email = :email
              AND b.status = 'CONFIRMED' AND b.check_in > :today
            ORDER BY b.id
            """, nativeQuery = true)
    List<ReschedulableBooking> findReschedulable(@Param("tenantId") long tenantId, @Param("email") String email,
                                                 @Param("today") LocalDate today);

    /** Chỉ đơn CONFIRMED mới đổi được. clearAutomatically để lần đọc sau thấy đúng dữ liệu mới. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE booking
            SET check_in = :checkIn, check_out = :checkOut, total_amount = :total
            WHERE id = :id AND tenant_id = :tenantId AND status = 'CONFIRMED'
            """, nativeQuery = true)
    int applyDates(@Param("id") long id, @Param("tenantId") long tenantId, @Param("checkIn") LocalDate checkIn,
                   @Param("checkOut") LocalDate checkOut, @Param("total") BigDecimal total);

    @Modifying
    @Query(value = """
            INSERT INTO booking_reschedule (tenant_id, request_id, booking_id, previous_check_in, previous_check_out,
                                            new_check_in, new_check_out, previous_total, new_total)
            VALUES (:tenantId, :requestId, :bookingId, :previousCheckIn, :previousCheckOut,
                    :checkIn, :checkOut, :previousTotal, :total)
            """, nativeQuery = true)
    int recordApplied(@Param("tenantId") long tenantId, @Param("requestId") long requestId,
                      @Param("bookingId") long bookingId, @Param("previousCheckIn") LocalDate previousCheckIn,
                      @Param("previousCheckOut") LocalDate previousCheckOut, @Param("checkIn") LocalDate checkIn,
                      @Param("checkOut") LocalDate checkOut, @Param("previousTotal") BigDecimal previousTotal,
                      @Param("total") BigDecimal total);

    @Query(value = """
            SELECT booking_id AS bookingId, CAST(new_check_in AS VARCHAR) AS checkIn,
                   CAST(new_check_out AS VARCHAR) AS checkOut
            FROM booking_reschedule
            WHERE tenant_id = :tenantId AND request_id = :requestId
            """, nativeQuery = true)
    Optional<AppliedReschedule> findApplied(@Param("tenantId") long tenantId, @Param("requestId") long requestId);

    interface NightRemaining {
        String getStayDate();

        Integer getRemaining();
    }

    interface ReschedulableBooking {
        Long getId();

        Long getRoomTypeId();

        String getRoomTypeName();

        String getHotelName();

        String getCheckIn();

        String getCheckOut();

        Integer getRoomCount();

        BigDecimal getTotalAmount();
    }

    interface AppliedReschedule {
        Long getBookingId();

        String getCheckIn();

        String getCheckOut();
    }
}

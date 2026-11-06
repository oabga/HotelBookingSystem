package com.hotelbooking.booking.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Mọi truy vấn theo người dùng đều bắt buộc có tenantId, nên không có đường đọc hoặc ghi xuyên tenant.
 * {@code @TenantId} trên Booking lọc thêm các truy vấn dẫn xuất, nhưng không lọc truy vấn native và findById,
 * nên tham số tenantId ở đây vẫn bắt buộc.
 * Các UPDATE trả về số dòng đổi được: READ COMMITTED đánh giá lại điều kiện sau khi chờ khóa,
 * nên giá trị này quyết định ai thắng khi có request đồng thời (docs/erd-v2.md §5.1, §6).
 */
public interface BookingRepository extends JpaRepository<Booking, Long> {

    List<Booking> findByTenantIdOrderByIdAsc(Long tenantId);

    List<Booking> findByTenantIdAndUserEmailOrderByIdAsc(Long tenantId, String userEmail);

    Optional<Booking> findByIdAndTenantId(Long id, Long tenantId);

    Optional<Booking> findByIdAndTenantIdAndUserEmail(Long id, Long tenantId, String userEmail);

    @Query(value = "SELECT base_price FROM room_type WHERE id = :roomTypeId AND tenant_id = :tenantId",
            nativeQuery = true)
    Optional<BigDecimal> findBasePrice(@Param("roomTypeId") long roomTypeId, @Param("tenantId") long tenantId);

    @Modifying
    @Query(value = """
            UPDATE room_inventory
            SET sold = sold + :roomCount
            WHERE tenant_id = :tenantId
              AND room_type_id = :roomTypeId
              AND stay_date = :stayDate
              AND sold + :roomCount <= total
            """, nativeQuery = true)
    int reserveNight(@Param("tenantId") long tenantId, @Param("roomTypeId") long roomTypeId,
                     @Param("stayDate") LocalDate stayDate, @Param("roomCount") int roomCount);

    @Modifying
    @Query(value = """
            UPDATE room_inventory
            SET sold = sold - :roomCount
            WHERE tenant_id = :tenantId
              AND room_type_id = :roomTypeId
              AND stay_date = :stayDate
              AND sold >= :roomCount
            """, nativeQuery = true)
    int releaseNight(@Param("tenantId") long tenantId, @Param("roomTypeId") long roomTypeId,
                     @Param("stayDate") LocalDate stayDate, @Param("roomCount") int roomCount);

    @Modifying
    @Query(value = """
            UPDATE booking
            SET status = 'CONFIRMED'
            WHERE id = :id AND tenant_id = :tenantId AND user_email = :email
              AND status = 'PENDING_PAYMENT'
              AND payment_expires_at > :now
            """, nativeQuery = true)
    int confirmIfPayable(@Param("id") long id, @Param("tenantId") long tenantId,
                         @Param("email") String email, @Param("now") Instant now);

    @Modifying
    @Query(value = """
            UPDATE booking
            SET status = 'CANCELLED'
            WHERE id = :id AND tenant_id = :tenantId AND user_email = :email
              AND status = 'PENDING_PAYMENT'
            """, nativeQuery = true)
    int cancelIfPending(@Param("id") long id, @Param("tenantId") long tenantId, @Param("email") String email);

    @Modifying
    @Query(value = """
            UPDATE booking
            SET status = 'EXPIRED'
            WHERE id = :id
              AND status = 'PENDING_PAYMENT'
              AND payment_expires_at <= :now
            """, nativeQuery = true)
    int expireIfOverdue(@Param("id") long id, @Param("now") Instant now);

    /** Dùng index idx_booking_pending_expiry. Job chạy cho mọi tenant nên không lọc theo tenant. */
    @Query(value = """
            SELECT id FROM booking
            WHERE status = 'PENDING_PAYMENT' AND payment_expires_at <= :now
            ORDER BY payment_expires_at
            """, nativeQuery = true)
    List<Long> findOverduePendingIds(@Param("now") Instant now);
}

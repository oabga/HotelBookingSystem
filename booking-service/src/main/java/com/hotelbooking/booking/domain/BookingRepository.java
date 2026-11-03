package com.hotelbooking.booking.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Mọi truy vấn đọc đều bắt buộc có tenantId, nên không có đường đọc xuyên tenant. */
public interface BookingRepository extends JpaRepository<Booking, Long> {

    List<Booking> findByTenantIdOrderByIdAsc(Long tenantId);

    List<Booking> findByTenantIdAndUserEmailOrderByIdAsc(Long tenantId, String userEmail);

    Optional<Booking> findByIdAndTenantId(Long id, Long tenantId);

    Optional<Booking> findByIdAndTenantIdAndUserEmail(Long id, Long tenantId, String userEmail);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM room WHERE id = :roomId AND tenant_id = :tenantId)",
            nativeQuery = true)
    boolean roomExistsInTenant(@Param("roomId") long roomId, @Param("tenantId") long tenantId);
}

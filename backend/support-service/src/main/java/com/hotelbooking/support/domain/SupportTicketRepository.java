package com.hotelbooking.support.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Mọi truy vấn theo người dùng đều bắt buộc có tenantId, nên không có đường đọc hoặc ghi xuyên tenant. */
public interface SupportTicketRepository extends JpaRepository<SupportTicket, Long> {

    Page<SupportTicket> findByTenantIdOrderByIdAsc(Long tenantId, Pageable pageable);

    Page<SupportTicket> findByTenantIdAndRequesterUserIdOrderByIdAsc(Long tenantId, Long requesterUserId,
                                                                     Pageable pageable);

    Optional<SupportTicket> findByIdAndTenantId(Long id, Long tenantId);

    /** Giữ khóa dòng để đổi trạng thái và thêm tin nhắn không chen vào nhau (không nhắn được sau khi CLOSED). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM SupportTicket t WHERE t.id = :id AND t.tenantId = :tenantId")
    Optional<SupportTicket> findByIdAndTenantIdForUpdate(@Param("id") Long id, @Param("tenantId") Long tenantId);

    /** Role phải khớp JWT, để user vừa bị đổi role không dùng được token cũ còn hạn. */
    @Query(value = "SELECT id FROM app_user WHERE tenant_id = :tenantId AND email = :email AND role = :role",
            nativeQuery = true)
    Optional<Long> findUserId(@Param("tenantId") long tenantId, @Param("email") String email,
                              @Param("role") String role);

    @Query(value = "SELECT id AS id, email AS email FROM app_user WHERE tenant_id = :tenantId AND id IN (:ids)",
            nativeQuery = true)
    List<UserEmail> findUserEmails(@Param("tenantId") long tenantId, @Param("ids") Collection<Long> ids);

    /** Đọc trực tiếp vì Support dùng chung database với Booking, không gọi HTTP sang Booking. */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM booking
                WHERE id = :bookingId AND tenant_id = :tenantId AND user_email = :email
            )
            """, nativeQuery = true)
    boolean ownsBooking(@Param("bookingId") long bookingId, @Param("tenantId") long tenantId,
                        @Param("email") String email);

    interface UserEmail {
        Long getId();

        String getEmail();
    }
}

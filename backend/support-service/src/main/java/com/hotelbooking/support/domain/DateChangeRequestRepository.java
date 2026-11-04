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
public interface DateChangeRequestRepository extends JpaRepository<DateChangeRequest, Long> {

    @Query("""
            SELECT r FROM DateChangeRequest r
            WHERE r.tenantId = :tenantId
              AND (:requesterUserId IS NULL OR r.requesterUserId = :requesterUserId)
              AND (:status IS NULL OR r.status = :status)
            ORDER BY r.id DESC
            """)
    Page<DateChangeRequest> search(@Param("tenantId") Long tenantId, @Param("requesterUserId") Long requesterUserId,
                                   @Param("status") DateChangeStatus status, Pageable pageable);

    Optional<DateChangeRequest> findByIdAndTenantId(Long id, Long tenantId);

    /** Giữ khóa dòng trong lúc gọi Booking, để hai admin xử lý cùng phiếu thì người sau thấy kết quả của người trước. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM DateChangeRequest r WHERE r.id = :id AND r.tenantId = :tenantId")
    Optional<DateChangeRequest> findForUpdate(@Param("id") Long id, @Param("tenantId") Long tenantId);

    /** Role phải khớp JWT, để user vừa bị đổi role không dùng được token cũ còn hạn. */
    @Query(value = "SELECT id FROM app_user WHERE tenant_id = :tenantId AND email = :email AND role = :role",
            nativeQuery = true)
    Optional<Long> findUserId(@Param("tenantId") long tenantId, @Param("email") String email,
                              @Param("role") String role);

    @Query(value = "SELECT id AS id, email AS email FROM app_user WHERE tenant_id = :tenantId AND id IN (:ids)",
            nativeQuery = true)
    List<UserEmail> findUserEmails(@Param("tenantId") long tenantId, @Param("ids") Collection<Long> ids);

    /** Booking đã đổi ngày cho phiếu này: lần duyệt trước đã áp dụng nhưng chưa kịp ghi nhận phiếu. */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM booking_reschedule WHERE tenant_id = :tenantId AND request_id = :requestId)
            """, nativeQuery = true)
    boolean isAppliedByBooking(@Param("tenantId") long tenantId, @Param("requestId") long requestId);

    interface UserEmail {
        Long getId();

        String getEmail();
    }
}

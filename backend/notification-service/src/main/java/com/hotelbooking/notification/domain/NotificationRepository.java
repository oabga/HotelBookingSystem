package com.hotelbooking.notification.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Mọi truy vấn theo người dùng đều bắt buộc có tenant và người nhận, nên không đọc được thông báo của người khác. */
public interface NotificationRepository extends JpaRepository<NotificationRow, Long> {

    Page<NotificationRow> findByTenantIdAndRecipientUserIdOrderByIdDesc(Long tenantId, Long recipientUserId,
                                                                        Pageable pageable);

    long countByTenantIdAndRecipientUserIdAndReadAtIsNull(Long tenantId, Long recipientUserId);

    Optional<NotificationRow> findByIdAndTenantIdAndRecipientUserId(Long id, Long tenantId, Long recipientUserId);

    @Query(value = "SELECT id FROM app_user WHERE tenant_id = :tenantId AND email = :email AND role = :role",
            nativeQuery = true)
    Optional<Long> findUserId(@Param("tenantId") long tenantId, @Param("email") String email,
                              @Param("role") String role);

    /** SKIP LOCKED để nhiều bản chạy song song không xử lý cùng một sự kiện và không chờ nhau. */
    @Query(value = """
            SELECT id FROM notification_outbox
            WHERE processed_at IS NULL
            ORDER BY id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> lockUnprocessed(@Param("batchSize") int batchSize);

    /** Cùng transaction với việc đánh dấu đã xử lý, và UNIQUE source_event_id chặn tạo trùng. */
    @Modifying
    @Query(value = """
            INSERT INTO notification (tenant_id, recipient_user_id, type, title, body, reference_id,
                                      source_event_id, created_at)
            SELECT tenant_id, recipient_user_id, type, title, body, reference_id, id, created_at
            FROM notification_outbox WHERE id IN (:ids)
            ON CONFLICT (source_event_id) DO NOTHING
            """, nativeQuery = true)
    int createFromOutbox(@Param("ids") List<Long> ids);

    @Modifying
    @Query(value = "UPDATE notification_outbox SET processed_at = now() WHERE id IN (:ids)", nativeQuery = true)
    int markProcessed(@Param("ids") List<Long> ids);
}

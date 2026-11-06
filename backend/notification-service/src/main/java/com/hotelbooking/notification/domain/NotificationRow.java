package com.hotelbooking.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;

import java.time.Instant;

/** Thông báo chỉ được tạo từ notification_outbox, nên entity này không có constructor public. */
@Entity
@Table(name = "notification")
public class NotificationRow implements NotificationView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Hibernate tự lọc theo tenant của Session (xem TenantIdentifierResolver). */
    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "recipient_user_id", nullable = false, updatable = false)
    private Long recipientUserId;

    @Column(name = "type", nullable = false, updatable = false, length = 40)
    private String type;

    @Column(name = "title", nullable = false, updatable = false, length = 200)
    private String title;

    @Column(name = "body", nullable = false, updatable = false, length = 2000)
    private String body;

    @Column(name = "reference_id", nullable = false, updatable = false)
    private Long referenceId;

    @Column(name = "source_event_id", nullable = false, updatable = false)
    private Long sourceEventId;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected NotificationRow() {
        // JPA
    }

    /** Đọc lại không đổi thời điểm đọc đầu tiên. */
    public void markRead(Instant now) {
        if (readAt == null) {
            readAt = now;
        }
    }

    @Override
    public Long getId() {
        return id;
    }

    @Override
    public String getType() {
        return type;
    }

    @Override
    public String getTitle() {
        return title;
    }

    @Override
    public String getBody() {
        return body;
    }

    @Override
    public Long getReferenceId() {
        return referenceId;
    }

    @Override
    public Instant getReadAt() {
        return readAt;
    }

    @Override
    public Instant getCreatedAt() {
        return createdAt;
    }
}

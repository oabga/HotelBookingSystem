package com.hotelbooking.support.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Sự kiện chờ notification-service đọc; service đó đánh dấu processed_at sau khi đã tạo thông báo. */
@Entity
@Table(name = "notification_outbox")
public class NotificationOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected NotificationOutbox() {
        // JPA
    }

    public NotificationOutbox(long tenantId, long recipientUserId, String type, String title, String body,
                              long referenceId, Instant createdAt) {
        this.tenantId = tenantId;
        this.recipientUserId = recipientUserId;
        this.type = type;
        this.title = title;
        this.body = body;
        this.referenceId = referenceId;
        this.createdAt = createdAt;
    }
}

package com.hotelbooking.support.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Bất biến: chỉ thêm mới, database cũng chặn UPDATE và DELETE. */
@Entity
@Table(name = "ticket_message")
public class TicketMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "ticket_id", nullable = false, updatable = false)
    private Long ticketId;

    @Column(name = "author_user_id", nullable = false, updatable = false)
    private Long authorUserId;

    @Column(name = "author_role", nullable = false, updatable = false, length = 20)
    private String authorRole;

    @Column(name = "body", nullable = false, updatable = false, length = 4000)
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TicketMessage() {
        // JPA
    }

    public TicketMessage(long tenantId, long ticketId, long authorUserId, String authorRole, String body,
                         Instant createdAt) {
        this.tenantId = tenantId;
        this.ticketId = ticketId;
        this.authorUserId = authorUserId;
        this.authorRole = authorRole;
        this.body = body;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getTicketId() {
        return ticketId;
    }

    public Long getAuthorUserId() {
        return authorUserId;
    }

    public String getAuthorRole() {
        return authorRole;
    }

    public String getBody() {
        return body;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

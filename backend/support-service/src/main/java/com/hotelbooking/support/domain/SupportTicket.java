package com.hotelbooking.support.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "support_ticket")
public class SupportTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "requester_user_id", nullable = false, updatable = false)
    private Long requesterUserId;

    @Column(name = "booking_id", updatable = false)
    private Long bookingId;

    @Column(name = "subject", nullable = false, updatable = false, length = 200)
    private String subject;

    @Column(name = "description", nullable = false, updatable = false, length = 4000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TicketStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected SupportTicket() {
        // JPA
    }

    private SupportTicket(long tenantId, long requesterUserId, Long bookingId, String subject, String description,
                          Instant now) {
        this.tenantId = tenantId;
        this.requesterUserId = requesterUserId;
        this.bookingId = bookingId;
        this.subject = subject;
        this.description = description;
        this.status = TicketStatus.OPEN;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static SupportTicket open(long tenantId, long requesterUserId, Long bookingId, String subject,
                                     String description, Instant now) {
        return new SupportTicket(tenantId, requesterUserId, bookingId, subject, description, now);
    }

    /** Bên gọi đã kiểm tra canMoveTo khi đang giữ khóa dòng. */
    public void moveTo(TicketStatus target, Instant now) {
        status = target;
        updatedAt = now;
        if (target == TicketStatus.CLOSED) {
            closedAt = now;
        }
    }

    public void touch(Instant now) {
        updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public Long getRequesterUserId() {
        return requesterUserId;
    }

    public Long getBookingId() {
        return bookingId;
    }

    public String getSubject() {
        return subject;
    }

    public String getDescription() {
        return description;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }
}

package com.hotelbooking.support.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Phiếu xin đổi ngày. Các giá trị ngày và tiền là ảnh chụp lúc gửi; booking chỉ đổi khi phiếu được duyệt. */
@Entity
@Table(name = "date_change_request")
public class DateChangeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Hibernate tự lọc theo tenant của Session và từ chối INSERT khác tenant (xem TenantIdentifierResolver). */
    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private Long bookingId;

    @Column(name = "requester_user_id", nullable = false, updatable = false)
    private Long requesterUserId;

    @Column(name = "room_type_name", nullable = false, updatable = false, length = 100)
    private String roomTypeName;

    @Column(name = "room_count", nullable = false, updatable = false)
    private Integer roomCount;

    @Column(name = "current_check_in", nullable = false, updatable = false)
    private LocalDate currentCheckIn;

    @Column(name = "current_check_out", nullable = false, updatable = false)
    private LocalDate currentCheckOut;

    @Column(name = "requested_check_in", nullable = false, updatable = false)
    private LocalDate requestedCheckIn;

    @Column(name = "requested_check_out", nullable = false, updatable = false)
    private LocalDate requestedCheckOut;

    @Column(name = "current_total", nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal currentTotal;

    @Column(name = "new_total", nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal newTotal;

    @Column(name = "description", nullable = false, updatable = false, length = 4000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DateChangeStatus status;

    @Column(name = "decided_by_user_id")
    private Long decidedByUserId;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_note", length = 1000)
    private String decisionNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DateChangeRequest() {
        // JPA
    }

    @SuppressWarnings("java:S107")
    public DateChangeRequest(long tenantId, long bookingId, long requesterUserId, String roomTypeName, int roomCount,
                             LocalDate currentCheckIn, LocalDate currentCheckOut, LocalDate requestedCheckIn,
                             LocalDate requestedCheckOut, BigDecimal currentTotal, BigDecimal newTotal,
                             String description, Instant createdAt) {
        this.tenantId = tenantId;
        this.bookingId = bookingId;
        this.requesterUserId = requesterUserId;
        this.roomTypeName = roomTypeName;
        this.roomCount = roomCount;
        this.currentCheckIn = currentCheckIn;
        this.currentCheckOut = currentCheckOut;
        this.requestedCheckIn = requestedCheckIn;
        this.requestedCheckOut = requestedCheckOut;
        this.currentTotal = currentTotal;
        this.newTotal = newTotal;
        this.description = description;
        this.status = DateChangeStatus.PENDING;
        this.createdAt = createdAt;
    }

    public void decide(DateChangeStatus decision, long adminUserId, String note, Instant now) {
        status = decision;
        decidedByUserId = adminUserId;
        decisionNote = note;
        decidedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public Long getBookingId() {
        return bookingId;
    }

    public Long getRequesterUserId() {
        return requesterUserId;
    }

    public String getRoomTypeName() {
        return roomTypeName;
    }

    public Integer getRoomCount() {
        return roomCount;
    }

    public LocalDate getCurrentCheckIn() {
        return currentCheckIn;
    }

    public LocalDate getCurrentCheckOut() {
        return currentCheckOut;
    }

    public LocalDate getRequestedCheckIn() {
        return requestedCheckIn;
    }

    public LocalDate getRequestedCheckOut() {
        return requestedCheckOut;
    }

    public BigDecimal getCurrentTotal() {
        return currentTotal;
    }

    public BigDecimal getNewTotal() {
        return newTotal;
    }

    public String getDescription() {
        return description;
    }

    public DateChangeStatus getStatus() {
        return status;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

package com.hotelbooking.booking.domain;

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

@Entity
@Table(name = "booking")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Hibernate tự lọc theo tenant của Session và từ chối INSERT khác tenant (xem TenantIdentifierResolver). */
    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "room_type_id", nullable = false, updatable = false)
    private Long roomTypeId;

    @Column(name = "user_email", nullable = false, updatable = false)
    private String userEmail;

    @Column(name = "check_in", nullable = false, updatable = false)
    private LocalDate checkIn;

    @Column(name = "check_out", nullable = false, updatable = false)
    private LocalDate checkOut;

    @Column(name = "room_count", nullable = false, updatable = false)
    private Integer roomCount;

    @Column(name = "total_amount", nullable = false, updatable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount;

    /** Chỉ đổi bằng các UPDATE có điều kiện trong BookingRepository, không đổi qua entity. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, updatable = false, length = 20)
    private BookingStatus status;

    @Column(name = "payment_expires_at", nullable = false, updatable = false)
    private Instant paymentExpiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Booking() {
        // JPA
    }

    private Booking(Long tenantId, Long roomTypeId, String userEmail, LocalDate checkIn, LocalDate checkOut,
                    Integer roomCount, BigDecimal totalAmount, BookingStatus status,
                    Instant paymentExpiresAt, Instant createdAt) {
        this.tenantId = tenantId;
        this.roomTypeId = roomTypeId;
        this.userEmail = userEmail;
        this.checkIn = checkIn;
        this.checkOut = checkOut;
        this.roomCount = roomCount;
        this.totalAmount = totalAmount;
        this.status = status;
        this.paymentExpiresAt = paymentExpiresAt;
        this.createdAt = createdAt;
    }

    public static Booking pendingPayment(long tenantId, long roomTypeId, String userEmail,
                                         LocalDate checkIn, LocalDate checkOut, int roomCount,
                                         BigDecimal totalAmount, Instant paymentExpiresAt, Instant createdAt) {
        return new Booking(tenantId, roomTypeId, userEmail, checkIn, checkOut, roomCount, totalAmount,
                BookingStatus.PENDING_PAYMENT, paymentExpiresAt, createdAt);
    }

    public Long getId() {
        return id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public Long getRoomTypeId() {
        return roomTypeId;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public LocalDate getCheckIn() {
        return checkIn;
    }

    public LocalDate getCheckOut() {
        return checkOut;
    }

    public Integer getRoomCount() {
        return roomCount;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public Instant getPaymentExpiresAt() {
        return paymentExpiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

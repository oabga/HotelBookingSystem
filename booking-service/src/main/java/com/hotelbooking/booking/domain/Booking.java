package com.hotelbooking.booking.domain;

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
@Table(name = "booking")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "room_id", nullable = false, updatable = false)
    private Long roomId;

    @Column(name = "user_email", nullable = false, updatable = false)
    private String userEmail;

    @Column(name = "check_in", nullable = false, updatable = false)
    private Instant checkIn;

    @Column(name = "check_out", nullable = false, updatable = false)
    private Instant checkOut;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BookingStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Booking() {
        // JPA
    }

    private Booking(Long tenantId, Long roomId, String userEmail, Instant checkIn, Instant checkOut,
                    BookingStatus status, Instant createdAt) {
        this.tenantId = tenantId;
        this.roomId = roomId;
        this.userEmail = userEmail;
        this.checkIn = checkIn;
        this.checkOut = checkOut;
        this.status = status;
        this.createdAt = createdAt;
    }

    public static Booking confirmed(long tenantId, long roomId, String userEmail,
                                    Instant checkIn, Instant checkOut, Instant createdAt) {
        return new Booking(tenantId, roomId, userEmail, checkIn, checkOut, BookingStatus.CONFIRMED, createdAt);
    }

    public Long getId() {
        return id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public Long getRoomId() {
        return roomId;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public Instant getCheckIn() {
        return checkIn;
    }

    public Instant getCheckOut() {
        return checkOut;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

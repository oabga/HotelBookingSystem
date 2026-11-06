package com.hotelbooking.booking.service;

import com.hotelbooking.booking.domain.BookingRepository;
import com.hotelbooking.booking.security.TenantIdentifierResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/** Đổi đơn quá hạn thanh toán sang EXPIRED và trả tồn (docs/api-contract.md §5.7). */
@Component
public class BookingExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(BookingExpiryJob.class);

    private final BookingRepository bookings;
    private final BookingService bookingService;
    private final Clock clock;

    public BookingExpiryJob(BookingRepository bookings, BookingService bookingService, Clock clock) {
        this.bookings = bookings;
        this.bookingService = bookingService;
        this.clock = clock;
    }

    /** Job không có JWT và xử lý mọi tenant, nên chạy với tenant gốc của Hibernate. */
    @Scheduled(fixedDelayString = "${booking.expiry.interval}")
    public void expireOverdue() {
        TenantIdentifierResolver.runAsSystem(() -> {
            for (long id : bookings.findOverduePendingIds(clock.instant())) {
                try {
                    bookingService.expire(id);
                } catch (RuntimeException ex) {
                    log.error("Could not expire booking {}", id, ex);
                }
            }
        });
    }
}

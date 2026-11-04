package com.hotelbooking.support.api;

import com.hotelbooking.support.domain.DateChangeRequest;
import com.hotelbooking.support.domain.DateChangeStatus;
import com.hotelbooking.support.service.DateChangeService.RequestPage;
import com.hotelbooking.support.service.DateChangeService.RequestView;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record DateChangeResponse(
        Long id,
        Long tenantId,
        Long bookingId,
        String requesterEmail,
        String roomTypeName,
        Integer roomCount,
        LocalDate currentCheckIn,
        LocalDate currentCheckOut,
        LocalDate requestedCheckIn,
        LocalDate requestedCheckOut,
        BigDecimal currentTotalAmount,
        BigDecimal newTotalAmount,
        String description,
        DateChangeStatus status,
        String decisionNote,
        Instant decidedAt,
        Instant createdAt) {

    static DateChangeResponse from(RequestView view) {
        DateChangeRequest request = view.request();
        return new DateChangeResponse(request.getId(), request.getTenantId(), request.getBookingId(),
                view.requesterEmail(), request.getRoomTypeName(), request.getRoomCount(),
                request.getCurrentCheckIn(), request.getCurrentCheckOut(), request.getRequestedCheckIn(),
                request.getRequestedCheckOut(), request.getCurrentTotal(), request.getNewTotal(),
                request.getDescription(), request.getStatus(), request.getDecisionNote(), request.getDecidedAt(),
                request.getCreatedAt());
    }

    public record PageResponse(List<DateChangeResponse> items, int page, int size, long totalElements, int totalPages) {
        static PageResponse from(RequestPage page) {
            return new PageResponse(page.items().stream().map(DateChangeResponse::from).toList(),
                    page.page(), page.size(), page.totalElements(), page.totalPages());
        }
    }
}

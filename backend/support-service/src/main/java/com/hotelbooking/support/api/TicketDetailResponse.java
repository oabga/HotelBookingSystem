package com.hotelbooking.support.api;

import com.hotelbooking.support.domain.SupportTicket;
import com.hotelbooking.support.domain.TicketStatus;
import com.hotelbooking.support.service.SupportService.TicketView;

import java.time.Instant;
import java.util.List;

public record TicketDetailResponse(
        Long id,
        Long tenantId,
        Long bookingId,
        String requesterEmail,
        String subject,
        String description,
        TicketStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant closedAt,
        List<MessageResponse> messages) {

    static TicketDetailResponse from(TicketView view) {
        SupportTicket ticket = view.ticket();
        return new TicketDetailResponse(ticket.getId(), ticket.getTenantId(), ticket.getBookingId(),
                view.requesterEmail(), ticket.getSubject(), ticket.getDescription(), ticket.getStatus(),
                ticket.getCreatedAt(), ticket.getUpdatedAt(), ticket.getClosedAt(),
                view.messages().stream().map(MessageResponse::from).toList());
    }
}

package com.hotelbooking.support.api;

import com.hotelbooking.support.domain.TicketMessage;

import java.time.Instant;

public record MessageResponse(Long id, Long ticketId, Long authorUserId, String authorRole, String body,
                              Instant createdAt) {

    static MessageResponse from(TicketMessage message) {
        return new MessageResponse(message.getId(), message.getTicketId(), message.getAuthorUserId(),
                message.getAuthorRole(), message.getBody(), message.getCreatedAt());
    }
}

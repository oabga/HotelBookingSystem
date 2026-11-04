package com.hotelbooking.support.api;

import com.hotelbooking.support.service.SupportService.TicketPage;

import java.util.List;

public record TicketPageResponse(List<TicketResponse> items, int page, int size, long totalElements, int totalPages) {

    static TicketPageResponse from(TicketPage page) {
        return new TicketPageResponse(page.items().stream().map(TicketResponse::from).toList(),
                page.page(), page.size(), page.totalElements(), page.totalPages());
    }
}

package com.hotelbooking.support.service;

import com.hotelbooking.support.domain.SupportTicket;
import com.hotelbooking.support.domain.SupportTicketRepository;
import com.hotelbooking.support.domain.TicketMessage;
import com.hotelbooking.support.domain.TicketMessageRepository;
import com.hotelbooking.support.domain.TicketStatus;
import com.hotelbooking.support.security.CurrentUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * USER chỉ thấy ticket của mình, ADMIN thấy toàn bộ ticket trong tenant.
 * Ticket không thuộc phạm vi luôn là 404, giống hệt ticket không tồn tại, để không lộ dữ liệu.
 */
@Service
public class SupportService {

    private final SupportTicketRepository tickets;
    private final TicketMessageRepository messages;
    private final Clock clock;

    public SupportService(SupportTicketRepository tickets, TicketMessageRepository messages, Clock clock) {
        this.tickets = tickets;
        this.messages = messages;
        this.clock = clock;
    }

    @Transactional
    public TicketView create(CurrentUser user, Long bookingId, String subject, String description) {
        long requesterId = requireUserId(user);
        if (bookingId != null && !tickets.ownsBooking(bookingId, user.tenantId(), user.email())) {
            throw new BookingNotFoundException();
        }
        SupportTicket ticket = tickets.saveAndFlush(
                SupportTicket.open(user.tenantId(), requesterId, bookingId, subject, description, now()));
        return new TicketView(ticket, user.email(), List.of());
    }

    @Transactional(readOnly = true)
    public TicketPage list(CurrentUser user, int page, int size) {
        long userId = requireUserId(user);
        PageRequest pageable = PageRequest.of(page, size);
        Page<SupportTicket> result = user.admin()
                ? tickets.findByTenantIdOrderByIdAsc(user.tenantId(), pageable)
                : tickets.findByTenantIdAndRequesterUserIdOrderByIdAsc(user.tenantId(), userId, pageable);

        List<Long> requesterIds = result.getContent().stream()
                .map(SupportTicket::getRequesterUserId).distinct().toList();
        Map<Long, String> emails = requesterIds.isEmpty() ? Map.of()
                : tickets.findUserEmails(user.tenantId(), requesterIds).stream()
                        .collect(Collectors.toMap(SupportTicketRepository.UserEmail::getId,
                                SupportTicketRepository.UserEmail::getEmail));
        List<TicketView> items = result.getContent().stream()
                .map(ticket -> new TicketView(ticket, emails.get(ticket.getRequesterUserId()), List.of()))
                .toList();
        return new TicketPage(items, page, size, result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public TicketView get(CurrentUser user, long id) {
        long userId = requireUserId(user);
        SupportTicket ticket = requireVisible(user, userId, tickets.findByIdAndTenantId(id, user.tenantId()));
        return view(user, ticket, messages.findByTenantIdAndTicketIdOrderByIdAsc(user.tenantId(), id));
    }

    @Transactional
    public TicketMessage addMessage(CurrentUser user, long id, String body) {
        long userId = requireUserId(user);
        SupportTicket ticket = requireVisible(user, userId, tickets.findByIdAndTenantIdForUpdate(id, user.tenantId()));
        if (ticket.getStatus() == TicketStatus.CLOSED) {
            throw new TicketStateConflictException("Ticket is closed");
        }
        Instant now = now();
        ticket.touch(now);
        return messages.saveAndFlush(new TicketMessage(user.tenantId(), id, userId, user.role(), body, now));
    }

    /** Khóa dòng trước khi đọc trạng thái nên hai request đồng thời không thể cùng đổi từ một trạng thái. */
    @Transactional
    public TicketView changeStatus(CurrentUser user, long id, TicketStatus target) {
        long userId = requireUserId(user);
        SupportTicket ticket = requireVisible(user, userId, tickets.findByIdAndTenantIdForUpdate(id, user.tenantId()));
        if (!ticket.getStatus().canMoveTo(target)) {
            throw new TicketStateConflictException("Cannot change status from " + ticket.getStatus() + " to " + target);
        }
        ticket.moveTo(target, now());
        tickets.flush();
        return view(user, ticket, List.of());
    }

    private TicketView view(CurrentUser user, SupportTicket ticket, List<TicketMessage> ticketMessages) {
        String email = tickets.findUserEmails(user.tenantId(), List.of(ticket.getRequesterUserId())).stream()
                .map(SupportTicketRepository.UserEmail::getEmail).findFirst().orElseThrow();
        return new TicketView(ticket, email, ticketMessages);
    }

    private long requireUserId(CurrentUser user) {
        return tickets.findUserId(user.tenantId(), user.email(), user.role())
                .orElseThrow(UserNotProvisionedException::new);
    }

    private static SupportTicket requireVisible(CurrentUser user, long userId,
                                                Optional<SupportTicket> found) {
        return found.filter(ticket -> user.admin() || ticket.getRequesterUserId() == userId)
                .orElseThrow(TicketNotFoundException::new);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    public record TicketView(SupportTicket ticket, String requesterEmail, List<TicketMessage> messages) {
    }

    public record TicketPage(List<TicketView> items, int page, int size, long totalElements, int totalPages) {
    }
}

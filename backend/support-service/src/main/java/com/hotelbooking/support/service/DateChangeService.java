package com.hotelbooking.support.service;

import com.hotelbooking.support.domain.DateChangeRequest;
import com.hotelbooking.support.domain.DateChangeRequestRepository;
import com.hotelbooking.support.domain.DateChangeStatus;
import com.hotelbooking.support.domain.NotificationOutbox;
import com.hotelbooking.support.domain.NotificationOutboxRepository;
import com.hotelbooking.support.security.CurrentUser;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Luồng xin đổi ngày. USER gửi phiếu cho đơn của mình, ADMIN trong tenant duyệt hoặc từ chối.
 * Phiếu của người khác hoặc tenant khác luôn là 404, giống hệt phiếu không tồn tại.
 * Không giữ phòng trong lúc chờ duyệt: Booking kiểm tra lại khi duyệt và trả 409 nếu đã hết phòng.
 */
@Service
public class DateChangeService {

    static final String CONSTRAINT_ONE_PENDING = "uq_date_change_request_pending";
    static final String TYPE_APPROVED = "DATE_CHANGE_APPROVED";
    static final String TYPE_REJECTED = "DATE_CHANGE_REJECTED";

    private final DateChangeRequestRepository requests;
    private final NotificationOutboxRepository outbox;
    private final BookingClient booking;
    private final Clock clock;

    public DateChangeService(DateChangeRequestRepository requests, NotificationOutboxRepository outbox,
                             BookingClient booking, Clock clock) {
        this.requests = requests;
        this.outbox = outbox;
        this.booking = booking;
        this.clock = clock;
    }

    @Transactional
    public RequestView create(CurrentUser user, String token, long bookingId, LocalDate checkIn, LocalDate checkOut,
                              String description) {
        long requesterId = requireUserId(user);
        BookingClient.Quote quote = booking.quote(token, bookingId, checkIn, checkOut);
        if (!quote.available()) {
            throw new RequestConflictException("Not enough rooms for the requested dates");
        }
        DateChangeRequest request = new DateChangeRequest(user.tenantId(), bookingId, requesterId,
                quote.roomTypeName(), quote.roomCount(), quote.currentCheckIn(), quote.currentCheckOut(),
                checkIn, checkOut, quote.currentTotalAmount(), quote.newTotalAmount(), description, now());
        try {
            return new RequestView(requests.saveAndFlush(request), user.email());
        } catch (DataIntegrityViolationException ex) {
            if (ex.getCause() instanceof ConstraintViolationException violation
                    && CONSTRAINT_ONE_PENDING.equals(violation.getConstraintName())) {
                throw new RequestConflictException("This booking already has a pending request");
            }
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public RequestPage list(CurrentUser user, DateChangeStatus status, int page, int size) {
        long userId = requireUserId(user);
        Page<DateChangeRequest> result = requests.search(user.tenantId(), user.admin() ? null : userId, status,
                PageRequest.of(page, size));
        List<Long> requesterIds = result.getContent().stream()
                .map(DateChangeRequest::getRequesterUserId).distinct().toList();
        Map<Long, String> emails = requesterIds.isEmpty() ? Map.of()
                : requests.findUserEmails(user.tenantId(), requesterIds).stream()
                        .collect(Collectors.toMap(DateChangeRequestRepository.UserEmail::getId,
                                DateChangeRequestRepository.UserEmail::getEmail));
        return new RequestPage(result.getContent().stream()
                .map(request -> new RequestView(request, emails.get(request.getRequesterUserId())))
                .toList(), page, size, result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public RequestView get(CurrentUser user, long id) {
        long userId = requireUserId(user);
        DateChangeRequest request = requests.findByIdAndTenantId(id, user.tenantId())
                .filter(found -> user.admin() || found.getRequesterUserId() == userId)
                .orElseThrow(RequestNotFoundException::new);
        return view(user, request);
    }

    /**
     * Booking đổi ngày trước, phiếu ghi nhận sau, trong khi giữ khóa dòng phiếu. Nếu Booking đã đổi mà bước sau
     * lỗi, phiếu vẫn PENDING và duyệt lại là an toàn vì Booking bỏ qua requestId đã áp dụng.
     */
    @Transactional
    public RequestView approve(CurrentUser admin, String token, long id) {
        long adminId = requireUserId(admin);
        DateChangeRequest request = requirePending(admin, id);
        booking.reschedule(token, request.getBookingId(), id, request.getRequestedCheckIn(),
                request.getRequestedCheckOut());
        request.decide(DateChangeStatus.APPROVED, adminId, null, now());
        outbox.save(new NotificationOutbox(admin.tenantId(), request.getRequesterUserId(), TYPE_APPROVED,
                "Yêu cầu đổi ngày đã được duyệt",
                "Booking #" + request.getBookingId() + " (" + request.getRoomTypeName() + ") đã đổi sang "
                        + request.getRequestedCheckIn() + " → " + request.getRequestedCheckOut()
                        + ". Tổng tiền mới: " + money(request.getNewTotal()) + " VND.",
                id, now()));
        requests.flush();
        return view(admin, request);
    }

    @Transactional
    public RequestView reject(CurrentUser admin, long id, String note) {
        long adminId = requireUserId(admin);
        DateChangeRequest request = requirePending(admin, id);
        if (requests.isAppliedByBooking(admin.tenantId(), id)) {
            throw new RequestConflictException("Dates were already changed; approve to finish this request");
        }
        request.decide(DateChangeStatus.REJECTED, adminId, note, now());
        String reason = note == null ? "" : " Lý do: " + note;
        outbox.save(new NotificationOutbox(admin.tenantId(), request.getRequesterUserId(), TYPE_REJECTED,
                "Yêu cầu đổi ngày bị từ chối",
                "Booking #" + request.getBookingId() + " (" + request.getRoomTypeName() + ") giữ nguyên ngày "
                        + request.getCurrentCheckIn() + " → " + request.getCurrentCheckOut() + "." + reason,
                id, now()));
        requests.flush();
        return view(admin, request);
    }

    private DateChangeRequest requirePending(CurrentUser admin, long id) {
        DateChangeRequest request = requests.findForUpdate(id, admin.tenantId())
                .orElseThrow(RequestNotFoundException::new);
        if (request.getStatus() != DateChangeStatus.PENDING) {
            throw new RequestConflictException("Request was already decided");
        }
        return request;
    }

    private RequestView view(CurrentUser user, DateChangeRequest request) {
        String email = requests.findUserEmails(user.tenantId(), List.of(request.getRequesterUserId())).stream()
                .map(DateChangeRequestRepository.UserEmail::getEmail).findFirst().orElseThrow();
        return new RequestView(request, email);
    }

    private long requireUserId(CurrentUser user) {
        return requests.findUserId(user.tenantId(), user.email(), user.role())
                .orElseThrow(UserNotProvisionedException::new);
    }

    private static String money(BigDecimal value) {
        return NumberFormat.getNumberInstance(Locale.of("vi", "VN")).format(value);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    public record RequestView(DateChangeRequest request, String requesterEmail) {
    }

    public record RequestPage(List<RequestView> items, int page, int size, long totalElements, int totalPages) {
    }
}

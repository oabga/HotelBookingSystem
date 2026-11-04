package com.hotelbooking.notification.api;

import com.hotelbooking.notification.domain.NotificationView;
import com.hotelbooking.notification.security.CurrentUser;
import com.hotelbooking.notification.service.NotificationService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** Mỗi người chỉ thấy thông báo của mình. Không có API tạo thông báo: chúng chỉ đến từ notification_outbox. */
@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public InboxResponse list(@AuthenticationPrincipal Jwt jwt,
                              @RequestParam(defaultValue = "0") @Min(0) int page,
                              @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        NotificationService.Inbox inbox = service.list(CurrentUser.from(jwt), page, size);
        Page<? extends NotificationView> result = inbox.page();
        return new InboxResponse(result.getContent().stream().map(NotificationResponse::from).toList(),
                page, size, result.getTotalElements(), result.getTotalPages(), inbox.unreadCount());
    }

    @PostMapping("/{id}/read")
    public NotificationResponse markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive Long id) {
        return NotificationResponse.from(service.markRead(CurrentUser.from(jwt), id));
    }

    public record NotificationResponse(Long id, String type, String title, String body, Long referenceId,
                                       Instant readAt, Instant createdAt) {
        static NotificationResponse from(NotificationView view) {
            return new NotificationResponse(view.getId(), view.getType(), view.getTitle(), view.getBody(),
                    view.getReferenceId(), view.getReadAt(), view.getCreatedAt());
        }
    }

    public record InboxResponse(List<NotificationResponse> items, int page, int size, long totalElements,
                                int totalPages, long unreadCount) {
    }
}

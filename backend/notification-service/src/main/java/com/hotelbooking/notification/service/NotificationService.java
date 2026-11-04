package com.hotelbooking.notification.service;

import com.hotelbooking.notification.domain.NotificationRepository;
import com.hotelbooking.notification.domain.NotificationRow;
import com.hotelbooking.notification.security.CurrentUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class NotificationService {

    private final NotificationRepository notifications;
    private final Clock clock = Clock.systemUTC();

    public NotificationService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public Inbox list(CurrentUser user, int page, int size) {
        long userId = requireUserId(user);
        Page<NotificationRow> result = notifications.findByTenantIdAndRecipientUserIdOrderByIdDesc(
                user.tenantId(), userId, PageRequest.of(page, size));
        long unread = notifications.countByTenantIdAndRecipientUserIdAndReadAtIsNull(user.tenantId(), userId);
        return new Inbox(result, unread);
    }

    /** Thông báo của người khác trả 404, giống hệt thông báo không tồn tại. */
    @Transactional
    public NotificationRow markRead(CurrentUser user, long id) {
        long userId = requireUserId(user);
        NotificationRow notification = notifications.findByIdAndTenantIdAndRecipientUserId(id, user.tenantId(), userId)
                .orElseThrow(NotificationNotFoundException::new);
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        notification.markRead(now);
        return notification;
    }

    /** Role phải khớp JWT, để user vừa bị đổi role không dùng được token cũ còn hạn. */
    private long requireUserId(CurrentUser user) {
        return notifications.findUserId(user.tenantId(), user.email(), user.role())
                .orElseThrow(UserNotProvisionedException::new);
    }

    public record Inbox(Page<NotificationRow> page, long unreadCount) {
    }
}

package com.hotelbooking.notification.service;

import com.hotelbooking.notification.domain.NotificationRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Biến sự kiện trong notification_outbox thành thông báo cho người dùng, trong một transaction.
 * Service tắt thì sự kiện vẫn nằm trong outbox và được xử lý khi chạy lại, nên không mất thông báo.
 */
@Service
public class NotificationDelivery {

    private static final int BATCH_SIZE = 100;

    private final NotificationRepository notifications;

    public NotificationDelivery(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @Scheduled(fixedDelayString = "${notification.delivery.interval}")
    @Transactional
    public int deliverPending() {
        List<Long> ids = notifications.lockUnprocessed(BATCH_SIZE);
        if (ids.isEmpty()) {
            return 0;
        }
        int created = notifications.createFromOutbox(ids);
        notifications.markProcessed(ids);
        return created;
    }
}

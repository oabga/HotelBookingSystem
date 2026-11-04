package com.hotelbooking.notification.domain;

import java.time.Instant;

/** Dòng notification đọc qua truy vấn native; timestamp trả về dạng Instant. */
public interface NotificationView {
    Long getId();

    String getType();

    String getTitle();

    String getBody();

    Long getReferenceId();

    Instant getReadAt();

    Instant getCreatedAt();
}

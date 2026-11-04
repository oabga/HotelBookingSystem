package com.hotelbooking.support.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, Long> {
}

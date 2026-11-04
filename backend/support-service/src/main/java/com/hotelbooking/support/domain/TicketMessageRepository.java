package com.hotelbooking.support.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketMessageRepository extends JpaRepository<TicketMessage, Long> {

    List<TicketMessage> findByTenantIdAndTicketIdOrderByIdAsc(Long tenantId, Long ticketId);
}

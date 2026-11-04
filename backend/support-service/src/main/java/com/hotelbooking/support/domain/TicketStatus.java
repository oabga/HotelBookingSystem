package com.hotelbooking.support.domain;

/** Chỉ đi một chiều OPEN -> IN_PROGRESS -> RESOLVED -> CLOSED. Mở lại ticket chưa được nhóm chốt (G2). */
public enum TicketStatus {
    OPEN,
    IN_PROGRESS,
    RESOLVED,
    CLOSED;

    public boolean canMoveTo(TicketStatus target) {
        return this != CLOSED && target.ordinal() == ordinal() + 1;
    }
}

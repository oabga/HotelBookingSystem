package com.hotelbooking.support.domain;

import org.junit.jupiter.api.Test;

import static com.hotelbooking.support.domain.TicketStatus.CLOSED;
import static com.hotelbooking.support.domain.TicketStatus.IN_PROGRESS;
import static com.hotelbooking.support.domain.TicketStatus.OPEN;
import static com.hotelbooking.support.domain.TicketStatus.RESOLVED;
import static org.assertj.core.api.Assertions.assertThat;

class TicketStatusTest {

    @Test
    void onlyTheNextStepIsAllowed() {
        for (TicketStatus from : TicketStatus.values()) {
            for (TicketStatus to : TicketStatus.values()) {
                boolean expected = (from == OPEN && to == IN_PROGRESS)
                        || (from == IN_PROGRESS && to == RESOLVED)
                        || (from == RESOLVED && to == CLOSED);
                assertThat(from.canMoveTo(to)).as(from + " -> " + to).isEqualTo(expected);
            }
        }
    }
}

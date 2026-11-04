package com.hotelbooking.support.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.hotelbooking.support.domain.TicketStatus;
import jakarta.validation.constraints.AssertTrue;

import java.util.Arrays;

/** Nhận chuỗi rồi tự đối chiếu, vì Jackson mặc định đổi số 1 thành giá trị enum thứ hai. */
public record UpdateStatusRequest(@JsonDeserialize(using = StrictStringDeserializer.class) String status) {

    @AssertTrue(message = "status must be OPEN, IN_PROGRESS, RESOLVED or CLOSED")
    public boolean isStatusValid() {
        return Arrays.stream(TicketStatus.values()).anyMatch(value -> value.name().equals(status));
    }

    TicketStatus target() {
        return TicketStatus.valueOf(status);
    }
}

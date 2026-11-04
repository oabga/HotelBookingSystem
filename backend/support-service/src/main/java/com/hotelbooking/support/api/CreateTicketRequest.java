package com.hotelbooking.support.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;

/** Trường lạ như tenantId, status hay requester bị bỏ qua: các giá trị đó chỉ lấy từ JWT và database. */
public record CreateTicketRequest(
        @Positive @JsonDeserialize(using = StrictLongDeserializer.class) Long bookingId,
        @JsonDeserialize(using = StrictStringDeserializer.class) String subject,
        @JsonDeserialize(using = StrictStringDeserializer.class) String description) {

    @AssertTrue(message = "subject must be 1-200 characters of plain text on one line")
    public boolean isSubjectValid() {
        return SupportText.isValidLine(subject, SupportText.MAX_SUBJECT);
    }

    @AssertTrue(message = "description must be 1-4000 characters of plain text")
    public boolean isDescriptionValid() {
        return SupportText.isValid(description, SupportText.MAX_BODY);
    }
}

package com.hotelbooking.support.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.AssertTrue;

public record AddMessageRequest(@JsonDeserialize(using = StrictStringDeserializer.class) String body) {

    @AssertTrue(message = "body must be 1-4000 characters of plain text")
    public boolean isBodyValid() {
        return SupportText.isValid(body, SupportText.MAX_BODY);
    }
}

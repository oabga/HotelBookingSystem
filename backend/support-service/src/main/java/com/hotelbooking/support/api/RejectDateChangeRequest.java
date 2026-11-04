package com.hotelbooking.support.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.AssertTrue;

/** Lý do không bắt buộc; nếu có sẽ được đưa vào thông báo gửi cho người dùng. */
public record RejectDateChangeRequest(@JsonDeserialize(using = StrictStringDeserializer.class) String note) {

    @AssertTrue(message = "note must be at most 1000 characters of plain text")
    public boolean isNoteValid() {
        return note == null || note.isBlank() || SupportText.isValid(note, SupportText.MAX_NOTE);
    }

    String normalizedNote() {
        return note == null || note.isBlank() ? null : note.strip();
    }
}

package com.hotelbooking.booking.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * Chỉ nhận chuỗi YYYY-MM-DD có thật. Mặc định Jackson còn nhận mảng [2026,12,1] và chuỗi có giờ,
 * trong khi contract coi ngày có giờ hoặc múi giờ là 400.
 */
class StayDateDeserializer extends JsonDeserializer<LocalDate> {

    private static final Pattern FORMAT = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    @Override
    public LocalDate deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            return (LocalDate) context.handleUnexpectedToken(LocalDate.class, parser);
        }
        String text = parser.getText();
        if (FORMAT.matcher(text).matches()) {
            try {
                return LocalDate.parse(text);
            } catch (DateTimeParseException ex) {
                // Báo lỗi chung ở dưới, không trả nội dung parser cho client.
            }
        }
        return (LocalDate) context.handleWeirdStringValue(LocalDate.class, text, "expected YYYY-MM-DD");
    }
}

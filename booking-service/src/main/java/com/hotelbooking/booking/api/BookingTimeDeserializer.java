package com.hotelbooking.booking.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * Chỉ nhận chuỗi ISO-8601 có múi giờ, như 2026-11-02T12:00:00+07:00. Thời gian không có múi giờ sẽ không
 * xác định được thời điểm thật, còn dạng số thì Jackson mặc định vẫn nhận nên cũng phải chặn.
 * Làm tròn xuống micro giây theo độ chính xác của TIMESTAMPTZ, để giá trị kiểm tra đúng bằng giá trị được lưu.
 */
class BookingTimeDeserializer extends JsonDeserializer<Instant> {

    private static final int MIN_YEAR = 2000;
    private static final int MAX_YEAR = 9999;

    @Override
    public Instant deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            return (Instant) context.handleUnexpectedToken(Instant.class, parser);
        }
        String text = parser.getText();
        try {
            OffsetDateTime time = OffsetDateTime.parse(text);
            if (time.getYear() >= MIN_YEAR && time.getYear() <= MAX_YEAR) {
                return time.toInstant().truncatedTo(ChronoUnit.MICROS);
            }
        } catch (DateTimeParseException ex) {
            // Báo lỗi chung ở dưới, không trả nội dung parser cho client.
        }
        return (Instant) context.handleWeirdStringValue(Instant.class, text, "expected ISO-8601 time with offset");
    }
}

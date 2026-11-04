package com.hotelbooking.support.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

/**
 * Chỉ nhận số nguyên JSON. Mặc định Jackson đổi "1" thành 1 và cắt 1.5 thành 1,
 * khiến request sai kiểu lại đặt nhầm phòng 1 thay vì nhận 400.
 */
class StrictLongDeserializer extends JsonDeserializer<Long> {

    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            return (Long) context.handleUnexpectedToken(Long.class, parser);
        }
        return parser.getLongValue();
    }
}

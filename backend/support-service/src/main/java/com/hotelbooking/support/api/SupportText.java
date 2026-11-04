package com.hotelbooking.support.api;

import java.util.regex.Pattern;

/**
 * Quy tắc cho nội dung người dùng nhập. Nội dung là văn bản thuần: có thẻ HTML thì trả 400 thay vì tự sửa,
 * vì tin nhắn là bản ghi bất biến và không được khác với điều người viết đã gửi.
 */
final class SupportText {

    static final int MAX_BODY = 4000;
    static final int MAX_NOTE = 1000;

    private static final Pattern HTML_TAG = Pattern.compile("<\\s*[a-zA-Z/!?]");

    private SupportText() {
    }

    static boolean isValid(String value, int maxLength) {
        if (value == null) {
            return false;
        }
        String text = value.strip();
        return !text.isEmpty() && text.length() <= maxLength
                && !HTML_TAG.matcher(text).find()
                && text.chars().noneMatch(SupportText::isForbiddenControl);
    }

    private static boolean isForbiddenControl(int c) {
        return Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t';
    }
}

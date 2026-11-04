package com.hotelbooking.support.api;

import com.hotelbooking.support.service.BookingNotFoundException;
import com.hotelbooking.support.service.BookingServiceUnavailableException;
import com.hotelbooking.support.service.InvalidDateChangeException;
import com.hotelbooking.support.service.RequestConflictException;
import com.hotelbooking.support.service.RequestNotFoundException;
import com.hotelbooking.support.service.UserNotProvisionedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Trả mọi lỗi của service bằng ProblemDetail, không kèm SQL, stack trace hay token. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({RequestNotFoundException.class, BookingNotFoundException.class})
    ResponseEntity<Object> handleNotFound(RuntimeException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(RequestConflictException.class)
    ResponseEntity<Object> handleConflict(RequestConflictException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(InvalidDateChangeException.class)
    ResponseEntity<Object> handleInvalidDates(InvalidDateChangeException ex) {
        return problem(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(BookingServiceUnavailableException.class)
    ResponseEntity<Object> handleBookingUnavailable(BookingServiceUnavailableException ex) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(UserNotProvisionedException.class)
    ResponseEntity<Object> handleUserNotProvisioned(UserNotProvisionedException ex) {
        return problem(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    /** Lỗi do Spring MVC tạo (400, 405...) cũng luôn dùng application/problem+json. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers,
                                                          HttpStatusCode statusCode, WebRequest request) {
        HttpHeaders problemHeaders = new HttpHeaders();
        problemHeaders.putAll(headers);
        if (body instanceof ProblemDetail) {
            problemHeaders.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        }
        return new ResponseEntity<>(body, problemHeaders, statusCode);
    }

    private static ResponseEntity<Object> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}

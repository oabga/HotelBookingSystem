package com.hotelbooking.catalog.api;

import com.hotelbooking.catalog.service.CatalogNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class CatalogExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(CatalogExceptionHandler.class);

    @ExceptionHandler(CatalogNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(CatalogNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> badRequest(IllegalArgumentException exception) {
        return problem(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> malformedRequest(Exception exception) {
        return problem(HttpStatus.BAD_REQUEST, "Request parameters or body are invalid");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception exception) {
        log.error("Unexpected Catalog error", exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}

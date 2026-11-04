package com.hotelbooking.support.api;

import com.hotelbooking.support.domain.DateChangeStatus;
import com.hotelbooking.support.security.CurrentUser;
import com.hotelbooking.support.service.DateChangeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/support/date-change-requests")
public class DateChangeController {

    private final DateChangeService service;

    public DateChangeController(DateChangeService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DateChangeResponse create(@AuthenticationPrincipal Jwt jwt,
                                     @Valid @RequestBody CreateDateChangeRequest request) {
        return DateChangeResponse.from(service.create(CurrentUser.from(jwt), jwt.getTokenValue(), request.bookingId(),
                request.checkIn(), request.checkOut(), request.description().strip()));
    }

    @GetMapping
    public DateChangeResponse.PageResponse list(@AuthenticationPrincipal Jwt jwt,
                                                @RequestParam(required = false) DateChangeStatus status,
                                                @RequestParam(defaultValue = "0") @Min(0) int page,
                                                @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return DateChangeResponse.PageResponse.from(service.list(CurrentUser.from(jwt), status, page, size));
    }

    @GetMapping("/{id}")
    public DateChangeResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive Long id) {
        return DateChangeResponse.from(service.get(CurrentUser.from(jwt), id));
    }

    @PostMapping("/{id}/approve")
    public DateChangeResponse approve(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive Long id) {
        return DateChangeResponse.from(service.approve(CurrentUser.from(jwt), jwt.getTokenValue(), id));
    }

    @PostMapping("/{id}/reject")
    public DateChangeResponse reject(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive Long id,
                                     @Valid @RequestBody(required = false) RejectDateChangeRequest request) {
        return DateChangeResponse.from(service.reject(CurrentUser.from(jwt), id,
                request == null ? null : request.normalizedNote()));
    }
}

package com.hotelbooking.support.api;

import com.hotelbooking.support.security.CurrentUser;
import com.hotelbooking.support.service.SupportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/support/tickets")
public class SupportController {

    private final SupportService support;

    public SupportController(SupportService support) {
        this.support = support;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TicketResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateTicketRequest request) {
        return TicketResponse.from(support.create(CurrentUser.from(jwt), request.bookingId(),
                request.subject().strip(), request.description().strip()));
    }

    @GetMapping
    public TicketPageResponse list(@AuthenticationPrincipal Jwt jwt,
                                   @RequestParam(defaultValue = "0") @Min(0) int page,
                                   @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return TicketPageResponse.from(support.list(CurrentUser.from(jwt), page, size));
    }

    @GetMapping("/{id}")
    public TicketDetailResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive Long id) {
        return TicketDetailResponse.from(support.get(CurrentUser.from(jwt), id));
    }

    @PostMapping("/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageResponse addMessage(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive Long id,
                                      @Valid @RequestBody AddMessageRequest request) {
        return MessageResponse.from(support.addMessage(CurrentUser.from(jwt), id, request.body().strip()));
    }

    @PatchMapping("/{id}/status")
    public TicketResponse changeStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable @Positive Long id,
                                       @Valid @RequestBody UpdateStatusRequest request) {
        return TicketResponse.from(support.changeStatus(CurrentUser.from(jwt), id, request.target()));
    }
}

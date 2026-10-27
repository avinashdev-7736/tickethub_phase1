package com.tickethub.booking.web;

import com.tickethub.booking.dto.Dtos.BookingResponse;
import com.tickethub.booking.dto.Dtos.BookingTimeline;
import com.tickethub.booking.dto.Dtos.CreateBookingRequest;
import com.tickethub.booking.dto.Dtos.CreateResult;
import com.tickethub.booking.service.BookingService;
import com.tickethub.common.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/bookings")
public class BookingController {

    private final BookingService service;

    public BookingController(BookingService service) {
        this.service = service;
    }

    /** 201 for a new booking; 200 with the original booking when the same Idempotency-Key is replayed. */
    @PostMapping
    public ResponseEntity<BookingResponse> create(@Valid @RequestBody CreateBookingRequest req,
                                                  @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                  @AuthenticationPrincipal Jwt jwt) {
        CreateResult result = service.create(req, CurrentUser.id(jwt), CurrentUser.email(jwt), idempotencyKey);
        return ResponseEntity.status(result.replay() ? HttpStatus.OK : HttpStatus.CREATED).body(result.booking());
    }

    @GetMapping("/{id}")
    public BookingResponse get(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return service.get(id, CurrentUser.id(jwt));
    }

    /** What happened to this booking and why (owner or ADMIN). */
    @GetMapping("/{id}/timeline")
    public BookingTimeline timeline(@PathVariable String id, @AuthenticationPrincipal Jwt jwt, Authentication auth) {
        return service.timeline(id, CurrentUser.id(jwt), CurrentUser.isAdmin(auth));
    }

    @PostMapping("/{id}/cancel")
    public BookingResponse cancel(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return service.cancel(id, CurrentUser.id(jwt));
    }
}

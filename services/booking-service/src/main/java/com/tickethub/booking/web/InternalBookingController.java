package com.tickethub.booking.web;

import com.tickethub.booking.dto.Dtos.InternalBooking;
import com.tickethub.booking.dto.Dtos.ReservedSeat;
import com.tickethub.booking.service.BookingService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * Service-to-service only (guarded by the internal key; never routed by the gateway).
 * The old REST "payment-result" callback is gone: Payment now tells Booking through events (saga).
 */
@RestController
@RequestMapping("/internal")
public class InternalBookingController {

    private final BookingService service;

    public InternalBookingController(BookingService service) {
        this.service = service;
    }

    @GetMapping("/shows/{showId}/reserved-seats")
    public List<ReservedSeat> reservedSeats(@PathVariable Long showId) {
        return service.reservedSeats(showId);
    }

    @GetMapping("/bookings/{id}")
    public InternalBooking booking(@PathVariable String id) {
        return service.internalGet(id);
    }

    /** Reconciliation: bookings confirmed since the given instant (ISO-8601). */
    @GetMapping("/bookings/confirmed")
    public List<InternalBooking> confirmed(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        return service.confirmedSince(since);
    }
}

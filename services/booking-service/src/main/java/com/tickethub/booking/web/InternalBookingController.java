package com.tickethub.booking.web;

import com.tickethub.booking.dto.Dtos.InternalBooking;
import com.tickethub.booking.dto.Dtos.PaymentResultRequest;
import com.tickethub.booking.dto.Dtos.ReservedSeat;
import com.tickethub.booking.service.BookingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Service-to-service only (guarded by the internal key; never routed by the gateway). */
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

    @PostMapping("/bookings/{id}/payment-result")
    public InternalBooking paymentResult(@PathVariable String id, @Valid @RequestBody PaymentResultRequest req) {
        return service.applyPaymentResult(id, req);
    }
}

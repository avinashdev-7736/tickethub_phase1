package com.tickethub.payment.client;

import com.tickethub.common.error.BusinessException;
import com.tickethub.common.web.InternalApiKeyFilter;
import com.tickethub.payment.dto.Dtos.InternalBooking;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

/**
 * Reads from Booking Service (GET only, so retry is safe). Payment no longer calls Booking to REPORT a result:
 * that is done with events (saga). It still reads a booking to validate a payment request and to reconcile.
 */
@Component
public class BookingClient {

    private static final Logger log = LoggerFactory.getLogger(BookingClient.class);

    private final RestClient rest;
    private final String internalKey;

    public BookingClient(RestClient.Builder lbBuilder,
                         @Value("${tickethub.clients.booking.base-url}") String baseUrl,
                         @Value("${tickethub.internal.key}") String internalKey,
                         @Value("${tickethub.clients.timeout-ms:3000}") int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.rest = lbBuilder.clone().baseUrl(baseUrl).requestFactory(factory).build();
        this.internalKey = internalKey;
    }

    @CircuitBreaker(name = "bookingService", fallbackMethod = "getFallback")
    @Retry(name = "bookingService")
    public InternalBooking getBooking(String bookingId) {
        InternalBooking b = rest.get().uri("/internal/bookings/{id}", bookingId)
                .header(InternalApiKeyFilter.HEADER, internalKey)
                .retrieve()
                .onStatus(s -> s.value() == 404, (req, res) -> {
                    throw BusinessException.notFound("BOOKING_NOT_FOUND", "Booking not found");
                })
                .body(InternalBooking.class);
        if (b == null) {
            throw BusinessException.notFound("BOOKING_NOT_FOUND", "Booking not found");
        }
        return b;
    }

    @SuppressWarnings("unused")
    private InternalBooking getFallback(String bookingId, Throwable t) {
        if (t instanceof BusinessException be) {
            throw be;
        }
        log.warn("Booking service unavailable while reading booking {}: {}", bookingId, t.toString());
        throw BusinessException.unavailable("BOOKING_SERVICE_UNAVAILABLE",
                "Booking information is temporarily unavailable. Please try again shortly.");
    }

    /** Reconciliation: bookings confirmed since the given instant. */
    @CircuitBreaker(name = "bookingService")
    @Retry(name = "bookingService")
    public List<InternalBooking> confirmedSince(Instant since) {
        List<InternalBooking> list = rest.get().uri("/internal/bookings/confirmed?since={since}", since.toString())
                .header(InternalApiKeyFilter.HEADER, internalKey)
                .retrieve()
                .body(new ParameterizedTypeReference<List<InternalBooking>>() { });
        return list == null ? List.of() : list;
    }
}

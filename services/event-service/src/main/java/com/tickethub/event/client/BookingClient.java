package com.tickethub.event.client;

import com.tickethub.common.error.BusinessException;
import com.tickethub.common.web.InternalApiKeyFilter;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads reservation state from Booking Service (the owner of seat state). Timeout + bounded retry + circuit breaker. */
@Component
public class BookingClient {

    private static final Logger log = LoggerFactory.getLogger(BookingClient.class);

    public record ReservedSeat(Long seatId, String state) { }

    private final RestClient rest;
    private final String internalKey;

    public BookingClient(RestClient.Builder lbBuilder,
                         @Value("${tickethub.clients.booking.base-url}") String baseUrl,
                         @Value("${tickethub.internal.key}") String internalKey,
                         @Value("${tickethub.clients.timeout-ms:2000}") int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.rest = lbBuilder.clone().baseUrl(baseUrl).requestFactory(factory).build();
        this.internalKey = internalKey;
    }

    @CircuitBreaker(name = "bookingService", fallbackMethod = "reservedSeatsFallback")
    @Retry(name = "bookingService")
    public Map<Long, String> reservedSeats(Long showId) {
        List<ReservedSeat> list = rest.get()
                .uri("/internal/shows/{showId}/reserved-seats", showId)
                .header(InternalApiKeyFilter.HEADER, internalKey)
                .retrieve()
                .body(new ParameterizedTypeReference<List<ReservedSeat>>() { });
        Map<Long, String> result = new HashMap<>();
        if (list != null) {
            list.forEach(r -> result.put(r.seatId(), r.state()));
        }
        return result;
    }

    /** Never guess availability: if Booking is unreachable, say so instead of showing every seat as free. */
    @SuppressWarnings("unused")
    private Map<Long, String> reservedSeatsFallback(Long showId, Throwable t) {
        if (t instanceof BusinessException be) {
            throw be;
        }
        log.warn("Booking service unavailable while reading seat state for show {}: {}", showId, t.toString());
        throw BusinessException.unavailable("SEAT_STATE_UNAVAILABLE",
                "Seat availability is temporarily unavailable. Please try again shortly.");
    }
}

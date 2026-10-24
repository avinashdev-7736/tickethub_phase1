package com.tickethub.booking.client;

import com.tickethub.booking.dto.Dtos.ShowInfo;
import com.tickethub.common.error.BusinessException;
import com.tickethub.common.web.InternalApiKeyFilter;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Reads show data (price, status, seat layout) from Event/Show Service. GET only, so retry is safe. */
@Component
public class EventClient {

    private static final Logger log = LoggerFactory.getLogger(EventClient.class);

    private final RestClient rest;
    private final String internalKey;

    public EventClient(RestClient.Builder lbBuilder,
                       @Value("${tickethub.clients.event.base-url}") String baseUrl,
                       @Value("${tickethub.internal.key}") String internalKey,
                       @Value("${tickethub.clients.timeout-ms:2000}") int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.rest = lbBuilder.clone().baseUrl(baseUrl).requestFactory(factory).build();
        this.internalKey = internalKey;
    }

    @CircuitBreaker(name = "eventService", fallbackMethod = "getShowFallback")
    @Retry(name = "eventService")
    public ShowInfo getShow(Long showId) {
        ShowInfo info = rest.get()
                .uri("/internal/shows/{id}", showId)
                .header(InternalApiKeyFilter.HEADER, internalKey)
                .retrieve()
                .onStatus(status -> status.value() == 404, (req, res) -> {
                    throw BusinessException.notFound("SHOW_NOT_FOUND", "Show not found");
                })
                .body(ShowInfo.class);
        if (info == null) {
            throw BusinessException.notFound("SHOW_NOT_FOUND", "Show not found");
        }
        return info;
    }

    @SuppressWarnings("unused")
    private ShowInfo getShowFallback(Long showId, Throwable t) {
        if (t instanceof BusinessException be) {
            throw be;
        }
        log.warn("Event service unavailable while reading show {}: {}", showId, t.toString());
        throw BusinessException.unavailable("EVENT_SERVICE_UNAVAILABLE",
                "Show information is temporarily unavailable. Please try again shortly.");
    }
}

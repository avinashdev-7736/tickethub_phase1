package com.tickethub.payment.provider;

import org.springframework.http.HttpHeaders;

import java.math.BigDecimal;

/** The payment service talks to a provider only through this interface (simulated, or a real sandbox). */
public interface PaymentProvider {

    enum Status { SUCCESS, FAILED, PENDING }

    record Result(Status status, String reference, String reason) { }

    /** A verified, de-duplicable provider callback. */
    record WebhookEvent(String eventId, String providerReference, Status status, String reason) { }

    String name();

    Result initiate(String paymentId, BigDecimal amount, String currency, String bookingId);

    /**
     * Verifies the signature and parses the callback.
     * @return null when the event type is not relevant; throws BusinessException (401) on a bad signature.
     */
    WebhookEvent parseWebhook(String rawBody, HttpHeaders headers);
}

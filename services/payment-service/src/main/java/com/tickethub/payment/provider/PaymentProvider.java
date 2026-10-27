package com.tickethub.payment.provider;

import org.springframework.http.HttpHeaders;

import java.math.BigDecimal;

/** The payment service talks to a provider only through this interface (simulated, or a real sandbox). */
public interface PaymentProvider {

    /** UNKNOWN = the provider has no record or did not answer; it is never a final answer. */
    enum Status { SUCCESS, FAILED, PENDING, AUTHORIZED, UNKNOWN }

    record Result(Status status, String reference, String reason) { }

    /** A verified, de-duplicable provider callback. */
    record WebhookEvent(String eventId, String providerReference, String providerPaymentId, Status status, String reason) { }

    record RefundResult(String refundId) { }

    String name();

    /** May throw {@link ProviderTimeoutException}. */
    Result initiate(String paymentId, BigDecimal amount, String currency, String bookingId);

    /** Ask the provider what really happened (source of truth) for a reference. */
    Result fetch(String providerReference);

    /** Refund a captured payment. Must not be called twice for the same payment (the service guarantees that). */
    RefundResult refund(String paymentId, String providerReference, String providerPaymentId, BigDecimal amount, String currency);

    /**
     * Verifies the signature and parses the callback.
     * @return null when the event type is not relevant; throws BusinessException (401) on a bad signature.
     */
    WebhookEvent parseWebhook(String rawBody, HttpHeaders headers);
}

package com.tickethub.payment.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickethub.common.error.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Controllable fake provider that also keeps its own "truth" per reference, like a real provider would, so that
 * reconciliation and timeout recovery can be demonstrated.
 * Modes: SUCCESS, FAILURE, RANDOM, PENDING (stuck),
 *        TIMEOUT_CAPTURED (money taken, answer lost), TIMEOUT_NOT_CAPTURED (nothing happened, answer lost).
 * Signed callbacks: header X-Simulator-Signature = HMAC-SHA256(body, payment.webhook.secret), body
 * {"eventId":"..","providerReference":"..","providerPaymentId":"..","status":"SUCCESS|FAILED|AUTHORIZED","reason":".."}.
 */
@Component
@ConditionalOnProperty(name = "payment.provider", havingValue = "simulated", matchIfMissing = true)
public class SimulatedPaymentProvider implements PaymentProvider {

    public enum Mode { SUCCESS, FAILURE, RANDOM, PENDING, TIMEOUT_CAPTURED, TIMEOUT_NOT_CAPTURED }

    public static final String SIGNATURE_HEADER = "X-Simulator-Signature";

    private final AtomicReference<Mode> mode = new AtomicReference<>();
    private volatile long delayMs;
    private final double failureRate;
    private final String webhookSecret;
    private final ObjectMapper mapper;
    private final Map<String, Status> truth = new ConcurrentHashMap<>();
    private final Map<String, String> refunds = new ConcurrentHashMap<>();

    public SimulatedPaymentProvider(@Value("${payment.simulator.mode:SUCCESS}") Mode mode,
                                    @Value("${payment.simulator.delay-ms:0}") long delayMs,
                                    @Value("${payment.simulator.failure-rate:0.3}") double failureRate,
                                    @Value("${payment.webhook.secret}") String webhookSecret,
                                    ObjectMapper mapper) {
        this.mode.set(mode);
        this.delayMs = delayMs;
        this.failureRate = failureRate;
        this.webhookSecret = webhookSecret;
        this.mapper = mapper;
    }

    public void configure(Mode newMode, long newDelayMs) {
        this.mode.set(newMode);
        this.delayMs = Math.max(0, newDelayMs);
    }

    public Mode mode() {
        return mode.get();
    }

    /** Test/demo helper: the provider captured the money but no callback was ever delivered (reconciliation must find it). */
    public void silentCapture(String reference) {
        truth.put(reference, Status.SUCCESS);
    }

    public int refundCount() {
        return refunds.size();
    }

    @Override
    public String name() {
        return "simulated";
    }

    @Override
    public Result initiate(String paymentId, BigDecimal amount, String currency, String bookingId) {
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs); // slow provider
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        String ref = "sim_" + paymentId;
        switch (mode.get()) {
            case SUCCESS -> {
                truth.put(ref, Status.SUCCESS);
                return new Result(Status.SUCCESS, ref, null);
            }
            case FAILURE -> {
                truth.put(ref, Status.FAILED);
                return new Result(Status.FAILED, ref, "SIMULATED_DECLINE");
            }
            case PENDING -> {
                truth.put(ref, Status.PENDING);
                return new Result(Status.PENDING, ref, null);
            }
            case TIMEOUT_CAPTURED -> {
                truth.put(ref, Status.SUCCESS);
                throw new ProviderTimeoutException(ref, "simulated timeout (payment WAS captured)", null);
            }
            case TIMEOUT_NOT_CAPTURED -> {
                truth.put(ref, Status.FAILED);
                throw new ProviderTimeoutException(ref, "simulated timeout (payment was NOT captured)", null);
            }
            default -> {
                if (ThreadLocalRandom.current().nextDouble() < failureRate) {
                    truth.put(ref, Status.FAILED);
                    return new Result(Status.FAILED, ref, "SIMULATED_RANDOM_DECLINE");
                }
                truth.put(ref, Status.SUCCESS);
                return new Result(Status.SUCCESS, ref, null);
            }
        }
    }

    @Override
    public Result fetch(String providerReference) {
        Status s = truth.getOrDefault(providerReference, Status.UNKNOWN);
        return new Result(s, providerReference, null);
    }

    @Override
    public RefundResult refund(String paymentId, String providerReference, String providerPaymentId, BigDecimal amount, String currency) {
        // idempotent by payment id: asking twice never refunds twice
        String refundId = refunds.computeIfAbsent(paymentId, id -> "rf_" + id);
        return new RefundResult(refundId);
    }

    @Override
    public WebhookEvent parseWebhook(String rawBody, HttpHeaders headers) {
        String expected = Hmac.sha256Hex(webhookSecret, rawBody);
        if (!Hmac.matches(expected, headers.getFirst(SIGNATURE_HEADER))) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", "Webhook signature verification failed");
        }
        try {
            JsonNode n = mapper.readTree(rawBody);
            String eventId = text(n, "eventId");
            String ref = text(n, "providerReference");
            String status = text(n, "status");
            if (eventId == null || ref == null || status == null) {
                throw BusinessException.badRequest("INVALID_WEBHOOK", "eventId, providerReference and status are required");
            }
            Status s = switch (status.toUpperCase()) {
                case "SUCCESS" -> Status.SUCCESS;
                case "AUTHORIZED" -> Status.AUTHORIZED;
                default -> Status.FAILED;
            };
            return new WebhookEvent(eventId, ref, text(n, "providerPaymentId"), s, text(n, "reason"));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw BusinessException.badRequest("INVALID_WEBHOOK", "Webhook body is not valid JSON");
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}

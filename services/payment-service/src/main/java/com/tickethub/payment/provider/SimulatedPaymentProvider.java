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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Controllable fake provider. Behaviour can be switched at runtime (ADMIN endpoint) to demonstrate
 * success, failure, slow payment and "stuck pending" (timeout) scenarios.
 * Signed callbacks: header X-Simulator-Signature = HMAC-SHA256(body, payment.webhook.secret), body
 * {"eventId":"..","providerReference":"..","status":"SUCCESS|FAILED","reason":".."}.
 */
@Component
@ConditionalOnProperty(name = "payment.provider", havingValue = "simulated", matchIfMissing = true)
public class SimulatedPaymentProvider implements PaymentProvider {

    public enum Mode { SUCCESS, FAILURE, RANDOM, PENDING }

    public static final String SIGNATURE_HEADER = "X-Simulator-Signature";

    private final AtomicReference<Mode> mode = new AtomicReference<>();
    private volatile long delayMs;
    private final double failureRate;
    private final String webhookSecret;
    private final ObjectMapper mapper;

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
        return switch (mode.get()) {
            case SUCCESS -> new Result(Status.SUCCESS, ref, null);
            case FAILURE -> new Result(Status.FAILED, ref, "SIMULATED_DECLINE");
            case PENDING -> new Result(Status.PENDING, ref, null);
            case RANDOM -> ThreadLocalRandom.current().nextDouble() < failureRate
                    ? new Result(Status.FAILED, ref, "SIMULATED_RANDOM_DECLINE")
                    : new Result(Status.SUCCESS, ref, null);
        };
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
            Status s = "SUCCESS".equalsIgnoreCase(status) ? Status.SUCCESS : Status.FAILED;
            return new WebhookEvent(eventId, ref, s, text(n, "reason"));
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

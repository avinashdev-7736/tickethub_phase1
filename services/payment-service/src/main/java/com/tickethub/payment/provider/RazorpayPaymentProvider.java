package com.tickethub.payment.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickethub.common.error.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Razorpay SANDBOX integration (use TEST keys only). Creates an order and returns PENDING; the final result arrives through the
 * signed webhook (payment.authorized / payment.captured / payment.failed). A timeout calling Razorpay becomes
 * {@link ProviderTimeoutException} so the payment is UNKNOWN and gets resolved by fetch/reconciliation, never guessed as FAILED.
 * NOTE: written against the public Razorpay REST docs but NOT exercised against the live sandbox from the build environment.
 */
@Component
@ConditionalOnProperty(name = "payment.provider", havingValue = "razorpay")
public class RazorpayPaymentProvider implements PaymentProvider {

    private final RestClient rest;
    private final String webhookSecret;
    private final ObjectMapper mapper;

    public RazorpayPaymentProvider(@Value("${payment.razorpay.base-url}") String baseUrl,
                                   @Value("${payment.razorpay.key-id}") String keyId,
                                   @Value("${payment.razorpay.key-secret}") String keySecret,
                                   @Value("${payment.webhook.secret}") String webhookSecret,
                                   ObjectMapper mapper) {
        if (keyId.isBlank() || keySecret.isBlank()) {
            throw new IllegalStateException("payment.provider=razorpay requires PAYMENT_RAZORPAY_KEY_ID and PAYMENT_RAZORPAY_KEY_SECRET");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        String basic = Base64.getEncoder().encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
        this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic).build();
        this.webhookSecret = webhookSecret;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "razorpay";
    }

    @Override
    public Result initiate(String paymentId, BigDecimal amount, String currency, String bookingId) {
        Map<String, Object> body = Map.of(
                "amount", amount.movePointRight(2).longValueExact(), // smallest currency unit (paise)
                "currency", currency,
                "receipt", paymentId,
                "notes", Map.of("bookingId", bookingId, "paymentId", paymentId));
        try {
            JsonNode response = rest.post().uri("/v1/orders")
                    .contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().body(JsonNode.class);
            if (response == null || response.get("id") == null) {
                return new Result(Status.FAILED, null, "PROVIDER_BAD_RESPONSE");
            }
            return new Result(Status.PENDING, response.get("id").asText(), null);
        } catch (ResourceAccessException e) {
            throw new ProviderTimeoutException(null, "Razorpay did not answer: " + e.getMessage(), e);
        }
    }

    @Override
    public Result fetch(String providerReference) {
        try {
            JsonNode payments = rest.get().uri("/v1/orders/{id}/payments", providerReference).retrieve().body(JsonNode.class);
            Status best = Status.PENDING;
            if (payments != null && payments.path("items").isArray()) {
                for (JsonNode p : payments.path("items")) {
                    String st = p.path("status").asText("");
                    if ("captured".equals(st)) {
                        return new Result(Status.SUCCESS, providerReference, null);
                    }
                    if ("authorized".equals(st)) {
                        best = Status.AUTHORIZED;
                    } else if ("failed".equals(st) && best == Status.PENDING) {
                        best = Status.FAILED;
                    }
                }
            }
            return new Result(best, providerReference, null);
        } catch (ResourceAccessException e) {
            return new Result(Status.UNKNOWN, providerReference, "provider unreachable");
        }
    }

    @Override
    public RefundResult refund(String paymentId, String providerReference, String providerPaymentId, BigDecimal amount, String currency) {
        if (providerPaymentId == null) {
            throw new IllegalStateException("Cannot refund yet: the provider payment id is not known (no callback received)");
        }
        JsonNode response = rest.post().uri("/v1/payments/{id}/refund", providerPaymentId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("amount", amount.movePointRight(2).longValueExact(), "receipt", paymentId))
                .retrieve().body(JsonNode.class);
        if (response == null || response.get("id") == null) {
            throw new IllegalStateException("Provider returned no refund id");
        }
        return new RefundResult(response.get("id").asText());
    }

    @Override
    public WebhookEvent parseWebhook(String rawBody, HttpHeaders headers) {
        String expected = Hmac.sha256Hex(webhookSecret, rawBody);
        if (!Hmac.matches(expected, headers.getFirst("X-Razorpay-Signature"))) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", "Webhook signature verification failed");
        }
        try {
            JsonNode root = mapper.readTree(rawBody);
            String event = root.path("event").asText("");
            Status status;
            switch (event) {
                case "payment.authorized" -> status = Status.AUTHORIZED;
                case "payment.captured" -> status = Status.SUCCESS;
                case "payment.failed" -> status = Status.FAILED;
                default -> {
                    return null; // other event types are ignored
                }
            }
            JsonNode entity = root.path("payload").path("payment").path("entity");
            String orderId = entity.path("order_id").asText(null);
            if (orderId == null) {
                throw BusinessException.badRequest("INVALID_WEBHOOK", "order_id missing");
            }
            String eventId = headers.getFirst("X-Razorpay-Event-Id");
            if (eventId == null || eventId.isBlank()) {
                eventId = Hmac.sha256Hex("dedupe", rawBody); // fallback: identical body = same event
            }
            return new WebhookEvent(eventId, orderId, entity.path("id").asText(null), status, entity.path("error_description").asText(null));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw BusinessException.badRequest("INVALID_WEBHOOK", "Webhook body is not valid JSON");
        }
    }
}

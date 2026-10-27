package com.tickethub.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickethub.common.error.BusinessException;
import com.tickethub.common.messaging.TransientProcessingException;
import com.tickethub.payment.client.BookingClient;
import com.tickethub.payment.domain.OutboxEvent;
import com.tickethub.payment.domain.Payment;
import com.tickethub.payment.dto.Dtos.FindingView;
import com.tickethub.payment.dto.Dtos.InternalBooking;
import com.tickethub.payment.dto.Dtos.PaymentResponse;
import com.tickethub.payment.dto.Dtos.ReconciliationReport;
import com.tickethub.payment.provider.PaymentProvider;
import com.tickethub.payment.provider.SimulatedPaymentProvider;
import com.tickethub.payment.repo.OutboxRepository;
import com.tickethub.payment.repo.PaymentRepository;
import com.tickethub.payment.repo.WebhookRecordRepository;
import com.tickethub.payment.service.OutboxRelay;
import com.tickethub.payment.service.PaymentService;
import com.tickethub.payment.service.ReconciliationService;
import com.tickethub.payment.service.RefundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpHeaders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:paymenttest;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.cloud.config.enabled=false",
        "spring.config.import=",
        "eureka.client.enabled=false",
        "tickethub.kafka.topic.auto-create=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.listener.auto-startup=false",
        "payment.webhook.secret=test-secret",
        "payment.reconcile.min-age-seconds=0",
        "payment.reconcile.interval-ms=3600000",
        "outbox.relay.interval-ms=3600000"
})
class PaymentFlowTest {

    @Autowired PaymentService service;
    @Autowired RefundService refunds;
    @Autowired ReconciliationService reconciliation;
    @Autowired OutboxRelay relay;
    @Autowired PaymentRepository payments;
    @Autowired OutboxRepository outbox;
    @Autowired WebhookRecordRepository webhooks;
    @Autowired ObjectMapper mapper;
    @SpyBean SimulatedPaymentProvider provider;
    @MockBean BookingClient bookingClient;
    @MockBean com.tickethub.payment.service.MessageSender sender;

    @BeforeEach
    void defaults() {
        provider.configure(SimulatedPaymentProvider.Mode.SUCCESS, 0);
        // any booking the reconciliation looks at is "fine" unless a test says otherwise
        when(bookingClient.getBooking(anyString())).thenAnswer(inv ->
                new InternalBooking(inv.getArgument(0), "someone", "CONFIRMED", new BigDecimal("250.00"), "INR",
                        Instant.now().plus(5, ChronoUnit.MINUTES), 1L, "NONE"));
        when(bookingClient.confirmedSince(any())).thenReturn(List.of());
    }

    private void booking(String id, String customer, String status, Instant expires) {
        when(bookingClient.getBooking(eq(id))).thenReturn(
                new InternalBooking(id, customer, status, new BigDecimal("250.00"), "INR", expires, 1L, "NONE"));
    }

    private void payableBooking(String id, String customer) {
        booking(id, customer, "PENDING_PAYMENT", Instant.now().plus(5, ChronoUnit.MINUTES));
    }

    private List<JsonNode> eventsOf(String bookingId) throws Exception {
        List<JsonNode> list = new ArrayList<>();
        for (OutboxEvent e : outbox.findByMessageKeyOrderByIdAsc(bookingId)) {
            list.add(mapper.readTree(e.getPayload()));
        }
        return list;
    }

    private static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private void webhook(String eventId, String ref, String status) throws Exception {
        String body = "{\"eventId\":\"" + eventId + "\",\"providerReference\":\"" + ref + "\",\"providerPaymentId\":\"pay_x\",\"status\":\"" + status + "\"}";
        HttpHeaders headers = new HttpHeaders();
        headers.add(SimulatedPaymentProvider.SIGNATURE_HEADER, sign(body));
        service.handleWebhook(body, headers);
    }

    private String backdate(String paymentId) throws InterruptedException {
        Thread.sleep(5);
        return paymentId;
    }

    // ---------------------------------------------------------------- initiate + outbox (scenarios 3, 4)

    @Test
    void successfulPaymentUsesBookingAmountAndPublishesPaymentSucceededThroughTheOutbox() throws Exception {
        payableBooking("b1", "u1");
        PaymentResponse r = service.initiate("b1", "u1");
        assertEquals("SUCCESS", r.status());
        assertEquals(0, new BigDecimal("250.00").compareTo(r.amount()));
        List<JsonNode> out = eventsOf("b1");
        assertEquals(1, out.size());
        assertEquals("PaymentSucceeded", out.get(0).get("eventType").asText());
        assertEquals(1, out.get(0).get("aggregateVersion").asInt());
        assertEquals(r.id(), out.get(0).get("paymentId").asText());
        verify(bookingClient, never()).confirmedSince(any());
        verifyNoInteractions(sender); // nothing is sent to Kafka inside the payment transaction
    }

    @Test
    void failedPaymentPublishesPaymentFailed() throws Exception {
        provider.configure(SimulatedPaymentProvider.Mode.FAILURE, 0);
        payableBooking("b2", "u2");
        PaymentResponse r = service.initiate("b2", "u2");
        assertEquals("FAILED", r.status());
        assertEquals("PaymentFailed", eventsOf("b2").get(0).get("eventType").asText());
    }

    @Test
    void repeatedInitiationReturnsSamePaymentAndOneEvent() throws Exception {
        payableBooking("b3", "u3");
        PaymentResponse a = service.initiate("b3", "u3");
        PaymentResponse b = service.initiate("b3", "u3");
        assertEquals(a.id(), b.id());
        assertEquals(1, eventsOf("b3").size());
    }

    @Test
    void cannotPayAnotherCustomersOrExpiredBooking() {
        payableBooking("b4", "owner");
        assertEquals("BOOKING_NOT_FOUND", assertThrows(BusinessException.class, () -> service.initiate("b4", "intruder")).getCode());
        booking("b5", "u5", "PENDING_PAYMENT", Instant.now().minus(1, ChronoUnit.MINUTES));
        assertEquals("BOOKING_NOT_PAYABLE", assertThrows(BusinessException.class, () -> service.initiate("b5", "u5")).getCode());
    }

    @Test
    void outboxRelayPublishesInOrderToThePaymentEventsTopic() throws Exception {
        payableBooking("b6", "u6");
        service.initiate("b6", "u6");
        doThrow(new java.io.IOException("kafka down")).when(sender).send(any(), any(), any(), any());
        assertEquals(0, relay.relayOnce());                         // Kafka down: nothing published, nothing lost
        reset(sender);
        relay.relayOnce();
        verify(sender, atLeastOnce()).send(eq("payment.events"), eq("b6"), any(), any());
    }

    // ---------------------------------------------------------------- scenario 9: webhooks and the state machine

    @Test
    void duplicateAndReorderedSignedWebhooksNeverMovePaymentBackwards() throws Exception {
        provider.configure(SimulatedPaymentProvider.Mode.PENDING, 0);
        payableBooking("b7", "u7");
        PaymentResponse pending = service.initiate("b7", "u7");
        assertEquals("PENDING", pending.status());
        String ref = pending.providerReference();

        webhook("w-captured", ref, "SUCCESS");
        webhook("w-captured", ref, "SUCCESS");        // duplicate delivery
        webhook("w-authorized", ref, "AUTHORIZED");   // arrives late: older state than SUCCESS
        webhook("w-failed", ref, "FAILED");           // contradicting late event
        assertEquals("SUCCESS", service.get(pending.id(), "u7", false).status());
        assertEquals(1, eventsOf("b7").size());       // exactly one PaymentSucceeded
    }

    @Test
    void authorizedThenCapturedWalksForward() throws Exception {
        provider.configure(SimulatedPaymentProvider.Mode.PENDING, 0);
        payableBooking("b8", "u8");
        PaymentResponse p = service.initiate("b8", "u8");
        webhook("w8-a", p.providerReference(), "AUTHORIZED");
        assertEquals("AUTHORIZED", service.get(p.id(), "u8", false).status());
        assertTrue(eventsOf("b8").isEmpty());          // authorized is not a business outcome yet
        webhook("w8-c", p.providerReference(), "SUCCESS");
        assertEquals("SUCCESS", service.get(p.id(), "u8", false).status());
        assertEquals(1, eventsOf("b8").size());
    }

    @Test
    void webhookWithBadSignatureIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(SimulatedPaymentProvider.SIGNATURE_HEADER, "deadbeef");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.handleWebhook("{\"eventId\":\"x\",\"providerReference\":\"y\",\"status\":\"SUCCESS\"}", headers));
        assertEquals("INVALID_SIGNATURE", ex.getCode());
    }

    @Test
    void webhookForUnknownPaymentIsRefusedAndNotRecordedSoTheProviderRetries() {
        assertThrows(BusinessException.class, () -> webhook("w-unknown", "no_such_ref", "SUCCESS"));
        assertFalse(webhooks.existsByEventId("w-unknown"));
    }

    @Test
    void timeoutWithMoneyCapturedBecomesUnknownThenSuccessWhenResolved() throws Exception {
        provider.configure(SimulatedPaymentProvider.Mode.TIMEOUT_CAPTURED, 0);
        payableBooking("b9", "u9");
        PaymentResponse r = service.initiate("b9", "u9");
        assertEquals("UNKNOWN", r.status());            // NOT failed: we do not know yet
        assertTrue(eventsOf("b9").isEmpty());

        PaymentResponse resolved = service.resolve(r.id(), "u9", false);
        assertEquals("SUCCESS", resolved.status());
        assertEquals("PaymentSucceeded", eventsOf("b9").get(0).get("eventType").asText());
    }

    @Test
    void timeoutWithNothingCapturedBecomesFailedWhenResolved() throws Exception {
        provider.configure(SimulatedPaymentProvider.Mode.TIMEOUT_NOT_CAPTURED, 0);
        payableBooking("b10", "u10");
        PaymentResponse r = service.initiate("b10", "u10");
        assertEquals("UNKNOWN", r.status());
        assertEquals("FAILED", service.resolve(r.id(), "u10", false).status());
        assertEquals("PaymentFailed", eventsOf("b10").get(0).get("eventType").asText());
    }

    @Test
    void manualCompletionIsForwardOnlyToo() throws Exception {
        provider.configure(SimulatedPaymentProvider.Mode.PENDING, 0);
        payableBooking("b11", "u11");
        PaymentResponse p = service.initiate("b11", "u11");
        service.completeManually(p.id(), PaymentProvider.Status.SUCCESS, null);
        service.completeManually(p.id(), PaymentProvider.Status.FAILED, "late");
        assertEquals("SUCCESS", service.get(p.id(), "u11", false).status());
        assertEquals(1, eventsOf("b11").size());
    }

    // ---------------------------------------------------------------- scenario 5: refunds exactly once

    private PaymentResponse paid(String bookingId, String customer) {
        payableBooking(bookingId, customer);
        PaymentResponse r = service.initiate(bookingId, customer);
        assertEquals("SUCCESS", r.status());
        return r;
    }

    @Test
    void rejectedBookingIsRefundedOnceEvenIfTheEventArrivesManyTimes() throws Exception {
        PaymentResponse r = paid("b12", "u12");
        int before = provider.refundCount();
        String rejected = "{\"eventId\":\"br-1\",\"eventType\":\"BookingRejected\",\"aggregateVersion\":1,\"bookingId\":\"b12\",\"reason\":\"BOOKING_EXPIRED\"}";
        refunds.onBookingEvent(rejected);
        refunds.onBookingEvent(rejected); // duplicate
        refunds.onBookingEvent(rejected.replace("br-1", "br-2").replace("BookingRejected", "BookingCancelled")); // another event, same payment

        assertEquals("REFUNDED", service.get(r.id(), "u12", false).status());
        assertEquals(before + 1, provider.refundCount());
        List<JsonNode> events = eventsOf("b12");
        assertEquals("PaymentRefunded", events.get(1).get("eventType").asText());
        assertEquals(2, events.get(1).get("aggregateVersion").asInt());
        assertEquals(2, events.size()); // succeeded + refunded, nothing more
    }

    @Test
    void parallelRefundRequestsRefundExactlyOnce() throws Exception {
        PaymentResponse r = paid("b13", "u13");
        int before = provider.refundCount();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    refunds.refundFor("b13", "par-" + n, "TEST");
                } catch (RuntimeException ignored) {
                    // a loser of a race may see a constraint error; what matters is the money
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        assertEquals("REFUNDED", service.get(r.id(), "u13", false).status());
        assertEquals(before + 1, provider.refundCount());
        assertEquals(1, eventsOf("b13").stream().filter(e -> "PaymentRefunded".equals(e.get("eventType").asText())).count());
    }

    @Test
    void failedRefundReleasesTheClaimAndTheRetrySucceeds() throws Exception {
        PaymentResponse r = paid("b14", "u14");
        doThrow(new IllegalStateException("provider down")).when(provider).refund(any(), any(), any(), any(), any());
        assertThrows(TransientProcessingException.class, () -> refunds.refundFor("b14", "rf-1", "TEST"));
        assertEquals("SUCCESS", service.get(r.id(), "u14", false).status()); // claim released
        assertEquals(1, eventsOf("b14").size());                                // no PaymentRefunded yet

        reset(provider);                                                         // provider is back (real behaviour again)
        provider.configure(SimulatedPaymentProvider.Mode.SUCCESS, 0);
        assertTrue(refunds.refundFor("b14", "rf-1", "TEST"));                    // the redelivered event now works
        assertEquals("REFUNDED", service.get(r.id(), "u14", false).status());
    }

    // ---------------------------------------------------------------- scenario 8: reconciliation

    private List<FindingView> findingsFor(ReconciliationReport report, String bookingId) {
        return report.findings().stream().filter(f -> bookingId.equals(f.bookingId())).toList();
    }

    @Test
    void reconciliationFindsAPaymentTheProviderCapturedButNoCallbackReported() throws Exception {
        provider.configure(SimulatedPaymentProvider.Mode.PENDING, 0);
        payableBooking("r1", "ur1");
        PaymentResponse p = service.initiate("r1", "ur1");
        assertEquals("PENDING", p.status());
        provider.silentCapture(p.providerReference());   // money moved, nobody told us
        Thread.sleep(10);

        ReconciliationReport report = reconciliation.run();
        List<FindingView> mine = findingsFor(report, "r1");
        assertEquals(1, mine.size());
        assertEquals("UNRESOLVED_RESOLVED", mine.get(0).kind());
        assertEquals("FIXED", mine.get(0).action());
        assertEquals("SUCCESS", service.get(p.id(), "ur1", false).status());
        assertEquals("PaymentSucceeded", eventsOf("r1").get(0).get("eventType").asText());
    }

    @Test
    void reconciliationRefundsAPaymentWhoseBookingNoLongerExists() throws Exception {
        PaymentResponse p = paid("r2", "ur2");
        booking("r2", "ur2", "EXPIRED", Instant.now().minus(1, ChronoUnit.HOURS)); // customer paid, booking died
        Thread.sleep(10);

        List<FindingView> mine = findingsFor(reconciliation.run(), "r2");
        assertEquals(1, mine.size());
        assertEquals("PAID_BUT_BOOKING_EXPIRED", mine.get(0).kind());
        assertEquals("FIXED", mine.get(0).action());
        assertEquals("REFUNDED", service.get(p.id(), "ur2", false).status());
        assertTrue(findingsFor(reconciliation.run(), "r2").isEmpty()); // second run: already fixed, nothing to report
    }

    @Test
    void reconciliationRepublishesPaymentSucceededWhenBookingNeverHeardOfIt() throws Exception {
        paid("r3", "ur3");
        booking("r3", "ur3", "PENDING_PAYMENT", Instant.now().plus(5, ChronoUnit.MINUTES)); // still waiting for payment
        Thread.sleep(10);

        List<FindingView> mine = findingsFor(reconciliation.run(), "r3");
        assertEquals("PAYMENT_NOT_APPLIED", mine.get(0).kind());
        assertEquals(2, eventsOf("r3").size());                                  // original + republished
        assertEquals(1, eventsOf("r3").get(1).get("aggregateVersion").asInt()); // same version: Booking treats extras as stale
    }

    @Test
    void reconciliationFlagsAConfirmedBookingWithNoPayment() {
        when(bookingClient.confirmedSince(any())).thenReturn(List.of(
                new InternalBooking("orphan-1", "someone", "CONFIRMED", new BigDecimal("100.00"), "INR",
                        Instant.now().plus(1, ChronoUnit.HOURS), 1L, "NONE")));
        List<FindingView> mine = findingsFor(reconciliation.run(), "orphan-1");
        assertEquals("CONFIRMED_WITHOUT_PAYMENT", mine.get(0).kind());
        assertEquals("FLAGGED", mine.get(0).action());                           // never guess about money
    }
}

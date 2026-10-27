package com.tickethub.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tickethub.booking.client.EventClient;
import com.tickethub.booking.domain.Booking;
import com.tickethub.booking.domain.OutboxEvent;
import com.tickethub.booking.dto.Dtos.*;
import com.tickethub.booking.repo.BookingRepository;
import com.tickethub.booking.repo.OutboxRepository;
import com.tickethub.booking.repo.SeatReservationRepository;
import com.tickethub.booking.service.BookingSagaService;
import com.tickethub.booking.service.BookingService;
import com.tickethub.booking.service.MessageSender;
import com.tickethub.booking.service.OutboxRelay;
import com.tickethub.common.error.BusinessException;
import com.tickethub.common.messaging.InvalidEventException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:bookingtest;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.cloud.config.enabled=false",
        "spring.config.import=",
        "eureka.client.enabled=false",
        "tickethub.kafka.topic.auto-create=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.listener.auto-startup=false",
        "booking.scan-interval-ms=3600000",
        "outbox.relay.interval-ms=3600000"
})
class BookingFlowTest {

    @Autowired BookingService service;
    @Autowired BookingSagaService saga;
    @Autowired OutboxRelay relay;
    @Autowired BookingRepository bookings;
    @Autowired OutboxRepository outbox;
    @Autowired SeatReservationRepository reservations;
    @Autowired ObjectMapper mapper;
    @MockBean EventClient eventClient;
    @MockBean MessageSender sender;

    @BeforeEach
    void stubShow() {
        when(eventClient.getShow(anyLong())).thenAnswer(inv -> new ShowInfo(inv.getArgument(0), "OPEN", "Test Show",
                Instant.now().plus(1, ChronoUnit.DAYS), new BigDecimal("100.00"), "INR",
                List.of(new ShowSeat(1L, "A1", "STANDARD"), new ShowSeat(2L, "A2", "VIP"), new ShowSeat(3L, "A3", "STANDARD"))));
    }

    private BookingResponse book(long showId, String customer, Long... seats) {
        return service.create(new CreateBookingRequest(showId, List.of(seats)), customer, customer + "@example.com", null).booking();
    }

    private static String payEvent(String type, String eventId, String bookingId, int version) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + type + "\",\"schemaVersion\":2,\"aggregateVersion\":" + version
                + ",\"bookingId\":\"" + bookingId + "\",\"paymentId\":\"pay-1\",\"amount\":100.00,\"currency\":\"INR\",\"reason\":\"declined\"}";
    }

    private List<OutboxEvent> events(String bookingId) {
        return outbox.findByMessageKeyOrderByIdAsc(bookingId);
    }

    private Booking reload(String id) {
        return bookings.findById(id).orElseThrow();
    }

    // ---------------------------------------------------------------- phase 1 guarantees still hold

    @Test
    void exactlyOneOfTwoConcurrentRequestsWinsTheSeat() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> a = () -> attempt(start, "cust-a");
        Callable<String> b = () -> attempt(start, "cust-b");
        Future<String> f1 = pool.submit(a);
        Future<String> f2 = pool.submit(b);
        start.countDown();
        List<String> results = List.of(f1.get(), f2.get());
        pool.shutdown();
        assertEquals(1, results.stream().filter("OK"::equals).count(), results.toString());
        assertEquals(1, results.stream().filter("SEAT_UNAVAILABLE"::equals).count(), results.toString());
    }

    private String attempt(CountDownLatch start, String customer) throws InterruptedException {
        start.await();
        try {
            book(101L, customer, 1L);
            return "OK";
        } catch (BusinessException e) {
            return e.getCode();
        }
    }

    @Test
    void amountIsComputedByServerAndCategoryIsHighestSeatType() {
        BookingResponse r = book(102L, "cust-c", 1L, 2L);
        assertEquals(0, new BigDecimal("200.00").compareTo(r.amount()));
        assertEquals("VIP", r.seatCategory());
        assertEquals("PENDING_PAYMENT", r.status());
    }

    @Test
    void sameIdempotencyKeyReturnsOriginalBooking() {
        CreateBookingRequest req = new CreateBookingRequest(103L, List.of(1L));
        CreateResult first = service.create(req, "cust-d", "d@example.com", "key-1");
        CreateResult second = service.create(req, "cust-d", "d@example.com", "key-1");
        assertFalse(first.replay());
        assertTrue(second.replay());
        assertEquals(first.booking().id(), second.booking().id());
    }

    @Test
    void otherCustomerCannotSeeBookingOrItsTimeline() {
        BookingResponse r = book(106L, "cust-h", 1L);
        assertEquals("BOOKING_NOT_FOUND", assertThrows(BusinessException.class, () -> service.get(r.id(), "someone-else")).getCode());
        assertEquals("BOOKING_NOT_FOUND", assertThrows(BusinessException.class, () -> service.timeline(r.id(), "someone-else", false)).getCode());
        assertNotNull(service.timeline(r.id(), "someone-else", true)); // ADMIN may look
    }

    @Test
    void cancelUnpaidReleasesSeats() {
        BookingResponse r = book(107L, "cust-i", 1L);
        service.cancel(r.id(), "cust-i");
        assertTrue(reservations.findByShowId(107L).isEmpty());
        assertEquals(Booking.Status.CANCELLED, reload(r.id()).getStatus());
    }

    // ---------------------------------------------------------------- scenario 4: saga input

    @Test
    void paymentSucceededConfirmsBookingAndWritesBookingConfirmedToTheOutbox() throws Exception {
        BookingResponse r = book(110L, "cust-e", 1L, 2L);
        saga.handle(payEvent("PaymentSucceeded", "ev-s1", r.id(), 1));
        assertEquals(Booking.Status.CONFIRMED, reload(r.id()).getStatus());
        List<OutboxEvent> out = events(r.id());
        assertEquals(1, out.size());
        JsonNode json = mapper.readTree(out.get(0).getPayload());
        assertEquals("BookingConfirmed", json.get("eventType").asText());
        assertEquals(1, json.get("aggregateVersion").asInt());
        assertEquals(2, json.get("schemaVersion").asInt());
        assertTrue(json.has("totalAmount"));          // v2 field
        assertTrue(json.has("amount"));               // v1 name still sent during migration
        assertEquals("VIP", json.get("seatCategory").asText());
        assertEquals("cust-e@example.com", json.get("recipientEmail").asText());
        verifyNoInteractions(sender);                 // nothing went to Kafka inside the business transaction
    }

    @Test
    void duplicatePaymentEventIsAppliedOnce() {
        BookingResponse r = book(111L, "cust-f", 1L);
        String payload = payEvent("PaymentSucceeded", "ev-dup", r.id(), 1);
        saga.handle(payload);
        saga.handle(payload);
        saga.handle(payload);
        assertEquals(1, events(r.id()).size());
        assertEquals(Booking.Status.CONFIRMED, reload(r.id()).getStatus());
    }

    @Test
    void paymentFailedReleasesSeatsSoTheyCanBeBookedAgain() {
        BookingResponse r = book(112L, "cust-g", 3L);
        saga.handle(payEvent("PaymentFailed", "ev-f1", r.id(), 1));
        assertEquals(Booking.Status.PAYMENT_FAILED, reload(r.id()).getStatus());
        assertTrue(events(r.id()).isEmpty());
        assertDoesNotThrow(() -> book(112L, "cust-g2", 3L));
    }

    // ---------------------------------------------------------------- scenario 5: compensation

    @Test
    void paymentAfterExpiryRejectsTheBookingAndRequestsARefund() throws Exception {
        BookingResponse r = book(113L, "cust-x", 1L);
        Booking b = reload(r.id());
        b.setExpiresAt(Instant.now().minusSeconds(60));
        bookings.save(b);
        assertTrue(service.expireOne(r.id()));
        assertTrue(reservations.findByShowId(113L).isEmpty());

        saga.handle(payEvent("PaymentSucceeded", "ev-late", r.id(), 1)); // money arrived anyway
        Booking after = reload(r.id());
        assertEquals(Booking.Status.REJECTED, after.getStatus());
        assertEquals(Booking.RefundState.PENDING, after.getRefundState());
        JsonNode rejected = mapper.readTree(events(r.id()).get(0).getPayload());
        assertEquals("BookingRejected", rejected.get("eventType").asText());
        assertEquals("BOOKING_EXPIRED", rejected.get("reason").asText());

        saga.handle(payEvent("PaymentRefunded", "ev-ref", r.id(), 2));
        assertEquals(Booking.RefundState.REFUNDED, reload(r.id()).getRefundState());
    }

    @Test
    void cancellingAConfirmedBookingReleasesSeatsAndEndsCancelledAfterTheRefund() throws Exception {
        BookingResponse r = book(114L, "cust-y", 1L);
        saga.handle(payEvent("PaymentSucceeded", "ev-c1", r.id(), 1));

        BookingResponse cancelling = service.cancel(r.id(), "cust-y");
        assertEquals("CANCELLING", cancelling.status());
        assertEquals("PENDING", cancelling.refundState());
        assertTrue(reservations.findByShowId(114L).isEmpty());          // seats are free immediately
        List<OutboxEvent> out = events(r.id());
        assertEquals(2, out.size());
        JsonNode cancelled = mapper.readTree(out.get(1).getPayload());
        assertEquals("BookingCancelled", cancelled.get("eventType").asText());
        assertEquals(2, cancelled.get("aggregateVersion").asInt());     // version goes up with every event

        service.cancel(r.id(), "cust-y");                                // repeating cancel changes nothing
        assertEquals(2, events(r.id()).size());

        saga.handle(payEvent("PaymentRefunded", "ev-c2", r.id(), 2));
        Booking done = reload(r.id());
        assertEquals(Booking.Status.CANCELLED, done.getStatus());
        assertEquals(Booking.RefundState.REFUNDED, done.getRefundState());
    }

    // ---------------------------------------------------------------- scenario 6: ordering

    @Test
    void staleAndReplayedPaymentEventsAreIgnored() {
        BookingResponse r = book(115L, "cust-z", 1L);
        saga.handle(payEvent("PaymentSucceeded", "ev-o1", r.id(), 1));
        service.cancel(r.id(), "cust-z");
        saga.handle(payEvent("PaymentRefunded", "ev-o2", r.id(), 2));
        int before = events(r.id()).size();

        saga.handle(payEvent("PaymentSucceeded", "ev-o3-resent", r.id(), 1)); // old version, new event id
        assertEquals(Booking.Status.CANCELLED, reload(r.id()).getStatus());
        assertEquals(before, events(r.id()).size());
        assertEquals(2, reload(r.id()).currentPaymentSeq());
    }

    // ---------------------------------------------------------------- scenario 3: outbox

    @Test
    void confirmedEventSurvivesAKafkaOutageAndIsPublishedInOrderAfterwards() throws Exception {
        outbox.deleteAll();
        BookingResponse r = book(116L, "cust-k", 1L);
        saga.handle(payEvent("PaymentSucceeded", "ev-k1", r.id(), 1));
        service.cancel(r.id(), "cust-k");

        doThrow(new java.io.IOException("kafka down")).when(sender).send(any(), any(), any(), any());
        assertEquals(0, relay.relayOnce());                                   // nothing published, nothing lost
        assertTrue(events(r.id()).stream().allMatch(e -> e.getPublishedAt() == null));
        assertEquals(Booking.Status.CANCELLING, reload(r.id()).getStatus());  // the business state was already committed

        reset(sender);                                                        // Kafka is back
        assertEquals(2, relay.relayOnce());
        verify(sender, times(2)).send(eq("booking.events"), eq(r.id()), any(), any());
        assertTrue(events(r.id()).stream().allMatch(e -> e.getPublishedAt() != null));
        assertEquals(0, relay.relayOnce());                                   // nothing is sent twice
    }

    // ---------------------------------------------------------------- scenario 7: timeline

    @Test
    void timelineExplainsWhatHappenedAndWhy() {
        BookingResponse r = book(117L, "cust-t", 1L);
        saga.handle(payEvent("PaymentSucceeded", "ev-t1", r.id(), 1));
        BookingTimeline t = service.timeline(r.id(), "cust-t", false);
        assertEquals("CONFIRMED", t.status());
        assertEquals(2, t.entries().size());
        assertEquals("PENDING_PAYMENT", t.entries().get(1).from());
        assertEquals("CONFIRMED", t.entries().get(1).to());
        assertNotNull(t.entries().get(1).reason());
    }

    // ---------------------------------------------------------------- scenario 1: bad data

    @Test
    void unreadableOrIncompletePaymentEventsAreInvalidAndNeverRetried() {
        assertThrows(InvalidEventException.class, () -> saga.handle("not json"));
        assertThrows(InvalidEventException.class, () -> saga.handle("{\"eventType\":\"PaymentSucceeded\"}"));
        assertDoesNotThrow(() -> saga.handle(payEvent("SomethingElse", "ev-x", "b", 1))); // unknown types are skipped
    }
}

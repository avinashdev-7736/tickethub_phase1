package com.tickethub.notification;

import com.tickethub.common.messaging.InvalidEventException;
import com.tickethub.common.messaging.TransientProcessingException;
import com.tickethub.notification.domain.Notification;
import com.tickethub.notification.repo.NotificationRepository;
import com.tickethub.notification.service.BookingEvent;
import com.tickethub.notification.service.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:notificationtest;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.cloud.config.enabled=false",
        "spring.config.import=",
        "eureka.client.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "tickethub.kafka.topic.auto-create=false",
        "management.health.mail.enabled=false"
})
class NotificationFlowTest {

    @Autowired NotificationService service;
    @Autowired NotificationRepository repo;
    @Autowired ObjectMapper mapper;
    @Autowired CircuitBreakerRegistry breakers;
    @MockBean JavaMailSender mailSender;

    @BeforeEach
    void resetMailAndBreaker() {
        reset(mailSender);
        breakers.circuitBreaker("mail").reset();
    }

    static String event(String type, int version, String eventId, String bookingId, String email) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + type + "\",\"schemaVersion\":2,\"aggregateVersion\":" + version
                + ",\"bookingId\":\"" + bookingId + "\",\"customerId\":\"c1\",\"recipientEmail\":" + (email == null ? "null" : "\"" + email + "\"")
                + ",\"showId\":1,\"eventTitle\":\"Show\",\"showStartTime\":\"2026-11-01T10:00:00Z\",\"seats\":[\"A1\",\"A2\"],"
                + "\"amount\":200.00,\"totalAmount\":200.00,\"currency\":\"INR\",\"seatCategory\":\"STANDARD\",\"reason\":\"test\","
                + "\"occurredAt\":\"2026-10-24T10:00:00Z\",\"someFutureField\":\"ignored\"}";
    }

    @Test
    void sendsEmailAndMarksSent() {
        service.handle(event("BookingConfirmed", 1, "e-1", "b-1", "a@b.com"));
        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
        assertEquals(Notification.Status.SENT, repo.findByEventId("e-1").orElseThrow().getStatus());
    }

    @Test
    void duplicateEventIsNotSentTwice() {
        String payload = event("BookingConfirmed", 1, "e-2", "b-2", "a@b.com");
        service.handle(payload);
        service.handle(payload);
        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
        assertEquals(1, repo.findAll().stream().filter(n -> n.getEventId().equals("e-2")).count());
    }

    @Test
    void sameEventDeliveredToManyThreadsAtOnceSendsExactlyOneEmail() throws Exception {
        String payload = event("BookingConfirmed", 1, "e-par", "b-par", "a@b.com");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    service.handle(payload);
                } catch (TransientProcessingException ignoredLoserOfTheRegistrationRace) {
                    // a thread that lost the registration race is told to retry; the retry would then be a no-op duplicate
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        service.handle(payload); // a late redelivery must also be a no-op
        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
        assertEquals(Notification.Status.SENT, repo.findByEventId("e-par").orElseThrow().getStatus());
    }

    @Test
    void staleEventArrivingAfterNewerOneIsIgnored() {
        // Cancelled (version 2) overtakes Confirmed (version 1): the "confirmed" email must NOT be sent after "cancelled".
        service.handle(event("BookingCancelled", 2, "e-c2", "b-ord", "a@b.com"));
        service.handle(event("BookingConfirmed", 1, "e-c1", "b-ord", "a@b.com"));
        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
        assertTrue(repo.findByEventId("e-c1").isEmpty());
        assertEquals(Notification.Status.SENT, repo.findByEventId("e-c2").orElseThrow().getStatus());
    }

    @Test
    void providerDownMarksRetryingThenSucceedsExactlyOnceOnRetry() {
        String payload = event("BookingConfirmed", 1, "e-retry", "b-retry", "a@b.com");
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));
        assertThrows(TransientProcessingException.class, () -> service.handle(payload));
        assertEquals(Notification.Status.RETRYING, repo.findByEventId("e-retry").orElseThrow().getStatus());

        reset(mailSender); // provider is back; the retry topic redelivers the same message
        service.handle(payload);
        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
        assertEquals(Notification.Status.SENT, repo.findByEventId("e-retry").orElseThrow().getStatus());
        service.handle(payload); // and one more redelivery changes nothing
        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    }

    @Test
    void circuitBreakerOpensSoTheDeadProviderIsNotHammered() {
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));
        for (int i = 0; i < 8; i++) {
            final int n = i;
            assertThrows(TransientProcessingException.class,
                    () -> service.handle(event("BookingConfirmed", 1, "e-cb-" + n, "b-cb-" + n, "a@b.com")));
        }
        verify(mailSender, atMost(5)).send(any(SimpleMailMessage.class)); // far fewer real calls than the 8 attempts
    }

    @Test
    void malformedAndIncompleteMessagesAreInvalidNotRetried() {
        assertThrows(InvalidEventException.class, () -> service.handle("not json"));
        assertThrows(InvalidEventException.class, () -> service.handle(event("BookingConfirmed", 1, "e-noemail", "b-ne", null)));
        assertThrows(InvalidEventException.class, () -> service.handle("{\"eventType\":\"BookingConfirmed\",\"bookingId\":\"x\"}"));
        verifyNoInteractions(mailSender);
    }

    @Test
    void unknownEventTypesAreIgnored() {
        service.handle(event("SomethingNew", 1, "e-new", "b-new", "a@b.com"));
        verifyNoInteractions(mailSender);
    }

    @Test
    void contractV1AndV2BothParse() throws Exception {
        String v1 = "{\"eventId\":\"x\",\"eventType\":\"BookingConfirmed\",\"bookingId\":\"b\",\"recipientEmail\":\"a@b.com\",\"amount\":150.00,\"currency\":\"INR\"}";
        String v2 = event("BookingConfirmed", 1, "y", "b", "a@b.com");
        BookingEvent e1 = mapper.readValue(v1, BookingEvent.class);
        BookingEvent e2 = mapper.readValue(v2, BookingEvent.class);
        assertEquals(0, new java.math.BigDecimal("150.00").compareTo(e1.effectiveAmount()));
        assertEquals(0, new java.math.BigDecimal("200.00").compareTo(e2.effectiveAmount()));
        assertEquals("STANDARD", e2.seatCategory());
        assertNull(e1.seatCategory());
    }
}

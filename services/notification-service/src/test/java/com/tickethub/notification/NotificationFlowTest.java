package com.tickethub.notification;

import com.tickethub.notification.domain.Notification;
import com.tickethub.notification.repo.NotificationRepository;
import com.tickethub.notification.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

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
        "management.health.mail.enabled=false",
        "spring.kafka.listener.auto-startup=false"
})
class NotificationFlowTest {

    @Autowired NotificationService service;
    @Autowired NotificationRepository repo;
    @MockBean JavaMailSender mailSender;

    private String event(String eventId, String bookingId) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"BookingConfirmed\",\"bookingId\":\"" + bookingId
                + "\",\"customerId\":\"c1\",\"recipientEmail\":\"a@b.com\",\"showId\":1,\"eventTitle\":\"Show\","
                + "\"showStartTime\":\"2026-11-01T10:00:00Z\",\"seats\":[\"A1\",\"A2\"],\"amount\":200.00,"
                + "\"currency\":\"INR\",\"occurredAt\":\"2026-10-24T10:00:00Z\"}";
    }

    @Test
    void sendsEmailAndMarksSent() {
        service.handle(event("e-1", "b-1"));
        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
        assertEquals(Notification.Status.SENT, repo.findByEventId("e-1").orElseThrow().getStatus());
    }

    @Test
    void duplicateEventIsNotSentTwice() {
        service.handle(event("e-2", "b-2"));
        service.handle(event("e-2", "b-2"));
        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
        assertEquals(1, repo.findAll().stream().filter(n -> n.getEventId().equals("e-2")).count());
    }

    @Test
    void emailFailureIsRecordedAndDoesNotThrow() {
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));
        assertDoesNotThrow(() -> service.handle(event("e-3", "b-3")));
        Notification n = repo.findByEventId("e-3").orElseThrow();
        assertEquals(Notification.Status.FAILED, n.getStatus());
        assertNotNull(n.getLastError());
        reset(mailSender);
    }

    @Test
    void malformedMessageIsDroppedWithoutError() {
        assertDoesNotThrow(() -> service.handle("not json"));
        assertDoesNotThrow(() -> service.handle("{\"bookingId\":\"x\"}"));
    }
}
